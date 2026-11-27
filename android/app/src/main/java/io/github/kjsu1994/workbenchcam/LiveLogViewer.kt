package io.github.kjsu1994.workbenchcam

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

internal enum class LogDisplayDensity(val label: String) {
    SUMMARY("요약"),
    COMPACT("1줄"),
    FULL("전체")
}

internal enum class DetailViewMode(val label: String) {
    TEXT("TEXT"),
    HEX_DUMP("HEX 덤프"),
    JSON("JSON"),
    DISSECT("자동 해독")
}

internal data class LogEntry(
    val seq: Int,
    val header: String,
    val body: String,
    val accentColor: Int,
    val compactPreview: String = "",
    val rawBytes: ByteArray? = null,
    val fullText: String = body,
    val truncatedExtraBytes: Int = 0,
    val dedupKey: String = "",
    val firstSeq: Int = seq,
    val repeatCount: Int = 1,
    val isUdp: Boolean = false,
    val srcPort: Int = 0,
    val dstPort: Int = 0,
    val replayIp: String = "",
    val replayPort: Int = 0,
    val highlighted: Boolean = false
) {
    fun displayIndexLabel(): String = if (repeatCount > 1) "#$firstSeq~$seq (×$repeatCount)" else "#$seq"

    fun toClipboardText(): String {
        val content = if (rawBytes != null) {
            LogFormatter.formatReadableText(rawBytes)
        } else {
            fullText.ifEmpty { body }
        }
        return if (content.isEmpty()) "${displayIndexLabel()} $header" else "${displayIndexLabel()} $header\n$content"
    }
}

internal object LogFormatter {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /**
     * Wireshark-style canonical hex dump (default 8 bytes/line so portrait phones never wrap awkwardly).
     */
    fun formatCanonicalHexDump(data: ByteArray, bytesPerLine: Int = 8, maxBytes: Int = 32768): String {
        if (data.isEmpty()) return "(빈 페이로드 · 0B)"
        val limit = minOf(data.size, maxBytes)
        val sb = StringBuilder(limit * 5)
        var offset = 0
        while (offset < limit) {
            if (offset > 0) sb.append('\n')
            // 4-digit hex offset (expands automatically past 64KB)
            val offHex = offset.toString(16).uppercase().padStart(4, '0')
            sb.append(offHex).append("  ")

            val lineEnd = minOf(offset + bytesPerLine, limit)
            for (i in offset until offset + bytesPerLine) {
                if (i < lineEnd) {
                    val b = data[i].toInt() and 0xFF
                    sb.append(HEX_CHARS[b ushr 4]).append(HEX_CHARS[b and 0x0F]).append(' ')
                } else {
                    sb.append("   ")
                }
                if (bytesPerLine >= 8 && i == offset + (bytesPerLine / 2) - 1) {
                    sb.append(' ')
                }
            }
            sb.append("|")
            for (i in offset until lineEnd) {
                val c = data[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('|')
            offset += bytesPerLine
        }
        if (data.size > limit) {
            sb.append("\n\n... (+${data.size - limit}B 생략 · [전체 복사] 또는 .pcap 저장으로 전체 확인)")
        }
        return sb.toString()
    }

    /**
     * Decodes UTF-8 while keeping real newlines/tabs readable and replacing binary control bytes with '.'.
     */
    fun formatReadableText(data: ByteArray, maxBytes: Int = 65536): String {
        if (data.isEmpty()) return "(빈 페이로드 · 0B)"
        val limit = minOf(data.size, maxBytes)
        val decoded = String(data, 0, limit, Charsets.UTF_8)
        val sb = StringBuilder(decoded.length)
        var i = 0
        while (i < decoded.length) {
            val ch = decoded[i]
            when {
                ch == '\r' -> {
                    if (i + 1 < decoded.length && decoded[i + 1] == '\n') {
                        sb.append('\n')
                        i++
                    } else {
                        sb.append('\n')
                    }
                }
                ch == '\n' || ch == '\t' -> sb.append(ch)
                ch.code < 0x20 || ch.code == 0x7F -> sb.append('.')
                else -> sb.append(ch)
            }
            i++
        }
        if (data.size > limit) {
            sb.append("\n\n... (+${data.size - limit}B 생략)")
        }
        return sb.toString()
    }

    /**
     * Single-line preview for compact mode (strips line breaks cleanly).
     */
    fun compactOneLine(data: ByteArray, maxChars: Int = 80): String {
        if (data.isEmpty()) return "(0B)"
        val ascii = HexCodec.bytesToAscii(data, maxChars)
        val printableRatio = ascii.count { it != '.' }.toFloat() / ascii.length.coerceAtLeast(1)
        return if (printableRatio >= 0.45f) {
            ascii.replace('\n', ' ').replace('\r', ' ')
        } else {
            HexCodec.bytesToHex(data, 20)
        }
    }

    /**
     * Pretty-prints JSON objects/arrays (including JSON body embedded after HTTP headers).
     * Returns null if no valid JSON structure is found.
     */
    fun tryPrettyJson(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        val startIdx = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> 0
            else -> {
                val objIdx = trimmed.indexOf('{')
                val arrIdx = trimmed.indexOf('[')
                val candidate = listOf(objIdx, arrIdx).filter { it >= 0 }.minOrNull() ?: return null
                // Only extract embedded JSON if preceded by whitespace/newline (e.g. HTTP body)
                if (candidate > 0 && !trimmed[candidate - 1].isWhitespace()) return null
                candidate
            }
        }
        val prefix = if (startIdx > 0) trimmed.substring(0, startIdx).trimEnd() else ""
        val jsonPart = trimmed.substring(startIdx).trim()
        val openCh = jsonPart.firstOrNull() ?: return null
        val expectedClose = if (openCh == '{') '}' else ']'
        val endIdx = jsonPart.lastIndexOf(expectedClose)
        if (endIdx <= 0) return null
        val candidate = jsonPart.substring(0, endIdx + 1)

        val sb = StringBuilder(candidate.length + 64)
        var indent = 0
        var inString = false
        var escaped = false
        for (ch in candidate) {
            if (inString) {
                sb.append(ch)
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    sb.append(ch)
                }
                '{', '[' -> {
                    sb.append(ch).append('\n')
                    indent++
                    repeat(indent) { sb.append("  ") }
                }
                '}', ']' -> {
                    sb.append('\n')
                    indent = (indent - 1).coerceAtLeast(0)
                    repeat(indent) { sb.append("  ") }
                    sb.append(ch)
                }
                ',' -> {
                    sb.append(",\n")
                    repeat(indent) { sb.append("  ") }
                }
                ':' -> sb.append(": ")
                ' ', '\n', '\r', '\t' -> { /* skip outside strings */ }
                else -> sb.append(ch)
            }
        }
        if (inString || indent != 0) return null
        return if (prefix.isNotEmpty()) "$prefix\n\n$sb" else sb.toString()
    }
}

internal object LogFilter {
    const val MIN_FONT_SP = 9.5f
    const val MAX_FONT_SP = 15.5f
    const val DEFAULT_FONT_SP = 11.5f

    fun clampFontSize(sp: Float): Float = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    /**
     * Multi-token case-insensitive matcher supporting `-exclude` tokens.
     * Example: `C→T -PING -STATUS` includes entries matching `C→T` and hides entries matching `PING` or `STATUS`.
     */
    fun matchesQuery(header: String, body: String, query: String, extraText: String = ""): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            if (token.startsWith("-") && token.length > 1) {
                val neg = token.substring(1)
                if (header.contains(neg, ignoreCase = true) ||
                    body.contains(neg, ignoreCase = true) ||
                    (extraText.isNotEmpty() && extraText.contains(neg, ignoreCase = true))
                ) {
                    return false
                }
            } else {
                val hit = header.contains(token, ignoreCase = true) ||
                    body.contains(token, ignoreCase = true) ||
                    (extraText.isNotEmpty() && extraText.contains(token, ignoreCase = true))
                if (!hit) return false
            }
        }
        return true
    }

    fun filterEntries(entries: List<LogEntry>, query: String): List<LogEntry> {
        if (query.isBlank()) return entries
        return entries.filter { entry ->
            val extra = if (entry.fullText != entry.body) entry.fullText else ""
            matchesQuery(entry.header, entry.body, query, extra)
        }
    }

    fun parseWatchKeywords(csv: String): List<String> =
        csv.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun matchesAnyWatchKeyword(
        header: String,
        body: String,
        fullText: String,
        keywords: List<String>
    ): Boolean {
        if (keywords.isEmpty()) return false
        return keywords.any { kw ->
            header.contains(kw, ignoreCase = true) ||
                body.contains(kw, ignoreCase = true) ||
                (fullText.isNotEmpty() && fullText.contains(kw, ignoreCase = true))
        }
    }

    /**
     * Collapses consecutive entries with identical `dedupKey` into a single `×N` grouped row
     * without discarding the underlying raw packets (so `.pcap` export remains 100% intact).
     */
    fun collapseConsecutiveDuplicates(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 1) return entries
        val out = ArrayList<LogEntry>(entries.size)
        for (entry in entries) {
            val prev = out.lastOrNull()
            if (prev != null && entry.dedupKey.isNotEmpty() && prev.dedupKey == entry.dedupKey) {
                out[out.lastIndex] = entry.copy(
                    firstSeq = prev.firstSeq,
                    repeatCount = prev.repeatCount + entry.repeatCount
                )
            } else {
                out.add(entry)
            }
        }
        return out
    }
}

/**
 * ListView that claims vertical touch gestures when nested inside a parent ScrollView
 * so users can freely scroll log history up/down without fighting the outer page.
 */
private class LogListView(
    context: Context,
    private val nestedInScrollView: Boolean
) : ListView(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (nestedInScrollView) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (canScrollVertically(-1) || canScrollVertically(1)) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

private class LogEntryAdapter(
    private val context: Context
) : BaseAdapter() {
    private var items: List<LogEntry> = emptyList()
    var bodyFontSp: Float = LogFilter.DEFAULT_FONT_SP
    var density: LogDisplayDensity = LogDisplayDensity.SUMMARY

    fun replaceItems(newItems: List<LogEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): LogEntry = items[position]
    override fun getItemId(position: Int): Long = items[position].seq.toLong()

    private class Holder(
        val root: LinearLayout,
        val headerView: TextView,
        val bodyView: TextView,
        val truncHintView: TextView
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: Holder
        val view: View
        if (convertView == null) {
            val headerTv = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val bodyTv = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(UiStyle.INK)
                setPadding(UiStyle.dp(context, 6), UiStyle.dp(context, 2), 0, 0)
            }
            val truncTv = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(UiStyle.CYAN)
                setPadding(UiStyle.dp(context, 6), UiStyle.dp(context, 2), 0, 0)
                visibility = View.GONE
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val hPad = UiStyle.dp(context, 10)
                val vPad = UiStyle.dp(context, 5)
                setPadding(hPad, vPad, hPad, vPad)
                addView(headerTv, LinearLayout.LayoutParams(-1, -2))
                addView(bodyTv, LinearLayout.LayoutParams(-1, -2))
                addView(truncTv, LinearLayout.LayoutParams(-1, -2))
            }
            holder = Holder(row, headerTv, bodyTv, truncTv)
            row.tag = holder
            view = row
        } else {
            view = convertView
            holder = convertView.tag as Holder
        }

        val entry = items[position]
        val compact = (density == LogDisplayDensity.COMPACT)
        val vPad = UiStyle.dp(context, if (compact) 4 else 6)
        val hPad = UiStyle.dp(context, 10)
        holder.root.setPadding(hPad, vPad, hPad, vPad)
        holder.root.setBackgroundColor(if (entry.highlighted) 0x554B181C else 0x00000000)
        val alertPrefix = if (entry.highlighted) "⚠ " else ""
        val headerColor = if (entry.highlighted) UiStyle.DANGER else entry.accentColor

        if (compact) {
            val preview = entry.compactPreview.ifEmpty { entry.body.replace('\n', ' ') }
            val line = if (preview.isNotEmpty()) {
                "$alertPrefix${entry.displayIndexLabel()} ${entry.header}  |  $preview"
            } else {
                "$alertPrefix${entry.displayIndexLabel()} ${entry.header}"
            }
            holder.headerView.text = line
            holder.headerView.setTextColor(headerColor)
            holder.headerView.textSize = (bodyFontSp - 0.5f).coerceAtLeast(9f)
            holder.headerView.maxLines = 1
            holder.headerView.ellipsize = TextUtils.TruncateAt.END
            holder.bodyView.visibility = View.GONE
            holder.truncHintView.visibility = View.GONE
            return view
        }

        holder.headerView.maxLines = 3
        holder.headerView.ellipsize = null
        holder.headerView.text = "$alertPrefix${entry.displayIndexLabel()} ${entry.header}"
        holder.headerView.setTextColor(headerColor)
        holder.headerView.textSize = (bodyFontSp - 0.5f).coerceAtLeast(9f)

        if (entry.body.isEmpty()) {
            holder.bodyView.visibility = View.GONE
        } else {
            holder.bodyView.visibility = View.VISIBLE
            holder.bodyView.text = entry.body
            holder.bodyView.textSize = bodyFontSp
        }

        if (entry.truncatedExtraBytes > 0) {
            holder.truncHintView.visibility = View.VISIBLE
            holder.truncHintView.textSize = (bodyFontSp - 1f).coerceAtLeast(9f)
            holder.truncHintView.text = "⋯ +${entry.truncatedExtraBytes}B 더 있음 · 탭하여 100% 전체 보기"
        } else {
            holder.truncHintView.visibility = View.GONE
        }
        return view
    }
}

/**
 * Scrollable, high-throughput terminal log viewer with chronological ordering,
 * touch-aware auto-follow pause/resume, floating jump-to-latest pill,
 * tap-to-inspect detail viewer, and long-press full copy.
 */
internal class LiveLogPanel(
    private val context: Context,
    private var emptyPlaceholder: String,
    inlineMode: Boolean,
    private val onInspectEntry: (entries: List<LogEntry>, index: Int) -> Unit,
    private val onLongCopyEntry: (LogEntry) -> Unit
) {
    val root: FrameLayout
    private val listView: LogListView
    private val adapter = LogEntryAdapter(context)
    private val emptyView: TextView
    private val jumpLatestBtn: TextView

    private var autoFollow = true
    private var userInteracting = false
    private var unreadWhilePaused = 0
    private var currentEntries: List<LogEntry> = emptyList()

    var onAutoFollowChanged: ((Boolean) -> Unit)? = null

    init {
        listView = LogListView(context, nestedInScrollView = inlineMode).apply {
            adapter = this@LiveLogPanel.adapter
            divider = ColorDrawable(0x4424322D)
            dividerHeight = UiStyle.dp(context, 1).coerceAtLeast(1)
            transcriptMode = AbsListView.TRANSCRIPT_MODE_DISABLED
            isFastScrollEnabled = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ ->
                if (position in currentEntries.indices) {
                    onInspectEntry(currentEntries, position)
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (position in currentEntries.indices) {
                    onLongCopyEntry(currentEntries[position])
                    true
                } else {
                    false
                }
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                    userInteracting = (
                        scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL ||
                            scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                        )
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        val atBottom = isAtBottom(view)
                        if (atBottom && !autoFollow) {
                            setAutoFollow(true, jumpNow = false)
                        }
                    }
                }

                override fun onScroll(
                    view: AbsListView,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {
                    if (!userInteracting || totalItemCount <= 0) return
                    val atBottom = isAtBottom(view)
                    if (!atBottom && autoFollow) {
                        setAutoFollow(false, jumpNow = false)
                    } else if (atBottom && !autoFollow) {
                        setAutoFollow(true, jumpNow = false)
                    }
                }
            })
        }

        emptyView = TextView(context).apply {
            text = emptyPlaceholder
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(UiStyle.MUTED)
            gravity = Gravity.CENTER_VERTICAL
            val p = UiStyle.dp(context, 12)
            setPadding(p, p, p, p)
        }

        jumpLatestBtn = UiStyle.compactChipButton(
            context,
            "↓ 최신으로",
            UiStyle.ACCENT,
            UiStyle.ON_ACCENT,
            null
        ) {
            setAutoFollow(true, jumpNow = true)
        }.apply {
            visibility = View.GONE
            elevation = UiStyle.dp(context, 4).toFloat()
        }

        root = FrameLayout(context).apply {
            background = if (inlineMode) {
                UiStyle.rounded(context, 0xff060908.toInt(), 12, UiStyle.BORDER)
            } else {
                ColorDrawable(0xff060908.toInt())
            }
            clipToOutline = inlineMode
            addView(listView, FrameLayout.LayoutParams(-1, -1))
            addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            addView(
                jumpLatestBtn,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = UiStyle.dp(context, 10)
                    bottomMargin = UiStyle.dp(context, 10)
                }
            )
        }
    }

    private fun isAtBottom(view: AbsListView): Boolean {
        if (view.count == 0) return true
        return !view.canScrollVertically(1)
    }

    fun isAutoFollow(): Boolean = autoFollow

    fun setEmptyText(text: String) {
        emptyPlaceholder = text
        emptyView.text = text
    }

    fun setFontSizeSp(sp: Float) {
        adapter.bodyFontSp = LogFilter.clampFontSize(sp)
        adapter.notifyDataSetChanged()
    }

    fun getFontSizeSp(): Float = adapter.bodyFontSp

    fun setDisplayDensity(density: LogDisplayDensity) {
        if (adapter.density != density) {
            adapter.density = density
            adapter.notifyDataSetChanged()
        }
    }

    fun setAutoFollow(enabled: Boolean, jumpNow: Boolean = enabled) {
        val changed = (autoFollow != enabled)
        autoFollow = enabled
        if (enabled) {
            unreadWhilePaused = 0
            jumpLatestBtn.visibility = View.GONE
            if (jumpNow && currentEntries.isNotEmpty()) {
                listView.setSelection(currentEntries.lastIndex)
            }
        } else {
            updateJumpPill()
        }
        if (changed) onAutoFollowChanged?.invoke(enabled)
    }

    fun submitEntries(entries: List<LogEntry>, addedDelta: Int = 0) {
        val firstVisible = listView.firstVisiblePosition
        val topOffset = listView.getChildAt(0)?.top ?: 0
        val prevAnchorSeq = currentEntries.getOrNull(firstVisible)?.seq

        currentEntries = entries
        adapter.replaceItems(entries)

        if (entries.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listView.visibility = View.GONE
            unreadWhilePaused = 0
            jumpLatestBtn.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        listView.visibility = View.VISIBLE

        if (autoFollow) {
            unreadWhilePaused = 0
            jumpLatestBtn.visibility = View.GONE
            listView.setSelection(entries.lastIndex)
        } else {
            if (addedDelta > 0) unreadWhilePaused += addedDelta
            if (prevAnchorSeq != null) {
                val anchorIdx = entries.indexOfFirst { it.seq == prevAnchorSeq }
                if (anchorIdx >= 0) {
                    listView.setSelectionFromTop(anchorIdx, topOffset)
                }
            }
            updateJumpPill()
        }
    }

    private fun updateJumpPill() {
        if (autoFollow || currentEntries.isEmpty()) {
            jumpLatestBtn.visibility = View.GONE
            return
        }
        jumpLatestBtn.text = if (unreadWhilePaused > 0) {
            "↓ 최신 (+$unreadWhilePaused)"
        } else {
            "↓ 최신으로"
        }
        jumpLatestBtn.visibility = View.VISIBLE
    }

    fun exportPlainText(): String {
        if (currentEntries.isEmpty()) return ""
        return currentEntries.joinToString("\n\n") { it.toClipboardText() }
    }
}

/**
 * Full 100% payload inspector dialog triggered by tapping any log entry.
 * Supports TEXT / Canonical HEX Dump / JSON Pretty-Print, full untruncated copy,
 * and prev/next navigation across the visible log list.
 */
internal class PacketDetailDialog(
    private val context: Context,
    private val entries: List<LogEntry>,
    startIndex: Int,
    private val onReplay: ((LogEntry) -> Unit)? = null
) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
    private var index = startIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    private var mode = DetailViewMode.TEXT

    private val titleView: TextView
    private val subtitleView: TextView
    private val bodyScroll: ScrollView
    private val bodyView: TextView
    private val prevBtn: TextView
    private val posLabel: TextView
    private val nextBtn: TextView
    private val replayBtn: TextView
    private val copyBtn: TextView
    private val renderModeChips: (DetailViewMode) -> Unit

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xff060908.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }

        titleView = UiStyle.label(context, "", 14.5f, UiStyle.ACCENT, bold = true).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = UiStyle.label(context, "", 11.5f, UiStyle.SECONDARY).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val closeBtn = UiStyle.compactChipButton(
            context, "✕ 닫기", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { dismiss() }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subtitleView)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(closeBtn)
        }

        val (modeChips, modeRenderer) = UiStyle.segmentedChips(
            context,
            DetailViewMode.entries.map { it to it.label },
            mode
        ) {
            mode = it
            renderCurrent()
        }
        renderModeChips = modeRenderer

        val headerBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.SURFACE)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 12), UiStyle.dp(context, 10))
            addView(topRow)
            addView(modeChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        }

        bodyView = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(UiStyle.INK)
            setTextIsSelectable(true)
            val p = UiStyle.dp(context, 14)
            setPadding(p, p, p, p)
        }
        bodyScroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(0xff060908.toInt())
            addView(bodyView, FrameLayout.LayoutParams(-1, -2))
        }

        prevBtn = UiStyle.compactChipButton(context, "◀ 이전", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            if (index > 0) {
                index--
                renderCurrent()
            }
        }
        posLabel = UiStyle.label(context, "", 12f, UiStyle.MUTED, bold = true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        nextBtn = UiStyle.compactChipButton(context, "다음 ▶", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            if (index < entries.lastIndex) {
                index++
                renderCurrent()
            }
        }
        replayBtn = UiStyle.compactChipButton(context, "▶ 재전송", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.CYAN) {
            val cur = entries.getOrNull(index) ?: return@compactChipButton
            dismiss()
            onReplay?.invoke(cur)
        }
        copyBtn = UiStyle.compactChipButton(context, "전체 복사", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT) {
            copyCurrentFullPayload()
        }

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiStyle.SURFACE)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
            val gap = UiStyle.dp(context, 6)
            addView(prevBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(posLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(nextBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap; marginEnd = gap })
            addView(replayBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(copyBtn)
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff060908.toInt())
            addView(headerBox, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) }, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) }, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        }

        rootLayout.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }

        dialog.setContentView(rootLayout)
        renderCurrent()
    }

    private fun renderCurrent() {
        val entry = entries.getOrNull(index) ?: return
        val byteStr = entry.rawBytes?.let { " · ${it.size}B" } ?: ""
        titleView.text = "${entry.displayIndexLabel()} 패킷 원문$byteStr"
        titleView.setTextColor(entry.accentColor)
        subtitleView.text = entry.header

        bodyView.text = renderEntryBody(entry, mode)
        bodyScroll.scrollTo(0, 0)

        posLabel.text = "${index + 1} / ${entries.size}"
        prevBtn.isEnabled = index > 0
        prevBtn.alpha = if (index > 0) 1f else 0.4f
        nextBtn.isEnabled = index < entries.lastIndex
        nextBtn.alpha = if (index < entries.lastIndex) 1f else 0.4f
        val canReplay = onReplay != null && ((entry.rawBytes?.isNotEmpty() == true) || entry.fullText.isNotEmpty())
        replayBtn.visibility = if (canReplay) View.VISIBLE else View.GONE
        renderModeChips(mode)
    }

    private fun renderEntryBody(entry: LogEntry, viewMode: DetailViewMode): String {
        val raw = entry.rawBytes
        return when (viewMode) {
            DetailViewMode.TEXT -> {
                if (raw != null) LogFormatter.formatReadableText(raw) else entry.fullText.ifEmpty { "(내용 없음)" }
            }
            DetailViewMode.HEX_DUMP -> {
                val bytes = raw ?: entry.fullText.toByteArray(Charsets.UTF_8)
                LogFormatter.formatCanonicalHexDump(bytes, bytesPerLine = 8)
            }
            DetailViewMode.JSON -> {
                val src = if (raw != null) LogFormatter.formatReadableText(raw) else entry.fullText
                LogFormatter.tryPrettyJson(src)
                    ?: "(JSON 형식이 아닌 패킷입니다 · TEXT 또는 HEX 덤프 탭을 확인하세요)\n\n$src"
            }
            DetailViewMode.DISSECT -> {
                val bytes = raw ?: entry.fullText.toByteArray(Charsets.UTF_8)
                val res = ProtocolDissector.dissect(bytes, entry.srcPort, entry.dstPort, entry.isUdp)
                "■ [${res.protocol}] ${res.summary}\n\n${res.details}"
            }
        }
    }

    private fun copyCurrentFullPayload() {
        val entry = entries.getOrNull(index) ?: return
        val full = when (mode) {
            DetailViewMode.TEXT -> entry.rawBytes?.let { LogFormatter.formatReadableText(it, maxBytes = Int.MAX_VALUE) } ?: entry.fullText
            DetailViewMode.HEX_DUMP -> {
                val bytes = entry.rawBytes ?: entry.fullText.toByteArray(Charsets.UTF_8)
                LogFormatter.formatCanonicalHexDump(bytes, bytesPerLine = 8, maxBytes = 131072)
            }
            DetailViewMode.JSON -> {
                val src = entry.rawBytes?.let { LogFormatter.formatReadableText(it, maxBytes = Int.MAX_VALUE) } ?: entry.fullText
                LogFormatter.tryPrettyJson(src) ?: src
            }
            DetailViewMode.DISSECT -> {
                val bytes = entry.rawBytes ?: entry.fullText.toByteArray(Charsets.UTF_8)
                val res = ProtocolDissector.dissect(bytes, entry.srcPort, entry.dstPort, entry.isUdp)
                "[${res.protocol}] ${res.summary}\n${res.details}"
            }
        }
        val textToCopy = "${entry.displayIndexLabel()} ${entry.header}\n$full"
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("DockyCam Full Packet #${entry.seq}", textToCopy))
        val sizeInfo = entry.rawBytes?.let { " (${it.size}B 전체)" } ?: ""
        Toast.makeText(context, "${entry.displayIndexLabel()} 원문을 복사했습니다$sizeInfo", Toast.LENGTH_SHORT).show()
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    fun dismiss() {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }
}

internal data class FullscreenLogActions(
    val onToggleRun: (() -> Unit)? = null,
    val onCycleFilter: (() -> Unit)? = null,
    val onCycleFormat: (() -> Unit)? = null,
    val onCycleDensity: (() -> Unit)? = null,
    val onToggleDedup: (() -> Unit)? = null,
    val onEditWatch: (() -> Unit)? = null,
    val onExportPcap: (() -> Unit)? = null,
    val onCopyAll: () -> Unit,
    val onClear: () -> Unit,
    val onSendConsoleCommand: ((String) -> Unit)? = null
)

/**
 * Dedicated edge-to-edge fullscreen live log viewer with keep-screen-on,
 * real-time include/-exclude search filter, compact/summary/full density toggle,
 * consecutive duplicate grouping, font size adjustment, and tap-to-inspect detail dialog.
 */
internal class FullscreenLogDialog(
    private val context: Context,
    title: String,
    private val defaultEmptyText: String,
    private val actions: FullscreenLogActions,
    onInspectEntry: (entries: List<LogEntry>, index: Int) -> Unit,
    onLongCopyEntry: (LogEntry) -> Unit,
    private val onDismissed: () -> Unit
) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
    private val statusDot: View
    private val titleView: TextView
    private val statsView: TextView
    private val runChip: TextView?
    private val filterChip: TextView?
    private val formatChip: TextView?
    private val densityChip: TextView?
    private val dedupChip: TextView?
    private val watchChip: TextView?
    private val followChip: TextView
    private val searchToggleChip: TextView
    private val searchBar: LinearLayout
    private val searchField: EditText
    private val consoleInput: EditText?
    private val logPanel: LiveLogPanel

    private var allEntries: List<LogEntry> = emptyList()
    private var searchQuery: String = ""

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xff060908.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        statusDot = View(context).apply {
            background = UiStyle.rounded(context, UiStyle.ACCENT, 99)
        }
        titleView = UiStyle.label(context, title, 14.5f, UiStyle.INK, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        statsView = UiStyle.label(context, "실시간 대기 중", 11f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val closeBtn = UiStyle.compactChipButton(
            context, "✕ 닫기", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { dismiss() }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(UiStyle.dp(context, 9), UiStyle.dp(context, 9)).apply {
                marginEnd = UiStyle.dp(context, 8)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(statsView)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(closeBtn)
        }

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gap = UiStyle.dp(context, 6)

        runChip = actions.onToggleRun?.let { cb ->
            UiStyle.compactChipButton(context, "시작", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        filterChip = actions.onCycleFilter?.let { cb ->
            UiStyle.compactChipButton(context, "방향: 전체", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        formatChip = actions.onCycleFormat?.let { cb ->
            UiStyle.compactChipButton(context, "HEX+TXT", UiStyle.SURFACE_ALT, UiStyle.CYAN, UiStyle.BORDER) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        densityChip = actions.onCycleDensity?.let { cb ->
            UiStyle.compactChipButton(context, "보기: 요약", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        dedupChip = actions.onToggleDedup?.let { cb ->
            UiStyle.compactChipButton(context, "×N 묶기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        watchChip = actions.onEditWatch?.let { cb ->
            UiStyle.compactChipButton(context, "알림", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) { cb() }
                .also { controlsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }) }
        }

        followChip = UiStyle.compactChipButton(
            context, "▶ 라이브", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
        ) {
            val next = !logPanel.isAutoFollow()
            logPanel.setAutoFollow(next, jumpNow = next)
        }
        controlsRow.addView(followChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })

        searchToggleChip = UiStyle.compactChipButton(
            context, "검색", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { toggleSearchBar() }
        controlsRow.addView(searchToggleChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })

        controlsRow.addView(
            UiStyle.compactChipButton(context, "A-", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
                logPanel.setFontSizeSp(logPanel.getFontSizeSp() - 1f)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
        )
        controlsRow.addView(
            UiStyle.compactChipButton(context, "A+", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
                logPanel.setFontSizeSp(logPanel.getFontSizeSp() + 1f)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
        )

        actions.onExportPcap?.let { cb ->
            controlsRow.addView(
                UiStyle.compactChipButton(context, ".pcap 저장", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT) { cb() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )
        }

        controlsRow.addView(
            UiStyle.compactChipButton(context, "복사", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
                actions.onCopyAll()
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
        )
        controlsRow.addView(
            UiStyle.compactChipButton(context, "지우기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
                actions.onClear()
            }
        )

        searchField = EditText(context).apply {
            hint = "포함: GET 01  /  제외: -PING -STATUS"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
            minimumHeight = UiStyle.dp(context, 42)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    applyFilter(addedDelta = 0)
                }
            })
        }
        val clearSearchBtn = UiStyle.compactChipButton(context, "지움", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
            searchField.setText("")
        }
        searchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 6), 0, 0)
            addView(searchField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(clearSearchBtn)
        }

        val headerBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.SURFACE)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 12), UiStyle.dp(context, 8))
            addView(topRow)
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(controlsRow)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) }
            )
            addView(searchBar)
        }

        val dividerTop = View(context).apply { setBackgroundColor(UiStyle.BORDER) }

        logPanel = LiveLogPanel(
            context = context,
            emptyPlaceholder = defaultEmptyText,
            inlineMode = false,
            onInspectEntry = onInspectEntry,
            onLongCopyEntry = onLongCopyEntry
        ).apply {
            onAutoFollowChanged = { following -> renderFollowChip(following) }
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff060908.toInt())
            addView(headerBox, LinearLayout.LayoutParams(-1, -2))
            addView(dividerTop, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            addView(logPanel.root, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        consoleInput = actions.onSendConsoleCommand?.let { sendCb ->
            val input = EditText(context).apply {
                hint = "명령어 전송 (Enter)"
                textSize = 13.5f
                typeface = Typeface.MONOSPACE
                setTextColor(UiStyle.INK)
                setHintTextColor(UiStyle.MUTED)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT
                background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
                setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
                minimumHeight = UiStyle.dp(context, 44)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                        val txt = text.toString()
                        if (txt.isNotEmpty()) {
                            sendCb(txt)
                            setText("")
                        }
                        true
                    } else false
                }
            }
            val sendBtn: Button = UiStyle.primaryButton(context, "전송") {
                val txt = input.text.toString()
                if (txt.isNotEmpty()) {
                    sendCb(txt)
                    input.setText("")
                }
            }
            rootLayout.addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) }, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            rootLayout.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(UiStyle.SURFACE)
                    setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
                    addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                    addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
                },
                LinearLayout.LayoutParams(-1, -2)
            )
            input
        }

        rootLayout.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }

        dialog.setContentView(rootLayout)
        dialog.setOnDismissListener { onDismissed() }
    }

    private fun toggleSearchBar() {
        val show = searchBar.visibility != View.VISIBLE
        searchBar.visibility = if (show) View.VISIBLE else View.GONE
        searchToggleChip.setTextColor(if (show) UiStyle.ON_ACCENT else UiStyle.INK)
        searchToggleChip.background = UiStyle.rippleRounded(
            context,
            if (show) UiStyle.CYAN else UiStyle.SURFACE_ALT,
            10,
            if (show) null else UiStyle.BORDER
        )
        if (!show) {
            searchField.setText("")
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
        } else {
            searchField.requestFocus()
        }
    }

    private fun renderFollowChip(following: Boolean) {
        followChip.text = if (following) "▶ 라이브" else "⏸ 일시정지"
        followChip.setTextColor(if (following) UiStyle.ACCENT else UiStyle.AMBER)
        followChip.background = UiStyle.rippleRounded(
            context,
            if (following) UiStyle.ACCENT_SOFT else UiStyle.AMBER_SOFT,
            10,
            if (following) UiStyle.ACCENT else UiStyle.AMBER
        )
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    fun dismiss() {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }

    fun updateHeader(
        statusText: String,
        statusColor: Int,
        running: Boolean,
        runLabel: String? = null,
        filterLabel: String? = null,
        formatLabel: String? = null,
        density: LogDisplayDensity? = null,
        dedupEnabled: Boolean? = null,
        watchCount: Int? = null
    ) {
        statsView.text = statusText
        statsView.setTextColor(statusColor)
        statusDot.background = UiStyle.rounded(context, if (running) UiStyle.ACCENT else UiStyle.MUTED, 99)
        if (runChip != null && runLabel != null) {
            runChip.text = runLabel
            runChip.setTextColor(if (running) UiStyle.DANGER else UiStyle.ACCENT)
            runChip.background = UiStyle.rippleRounded(
                context,
                if (running) UiStyle.DANGER_SOFT else UiStyle.ACCENT_SOFT,
                10,
                if (running) UiStyle.DANGER else UiStyle.ACCENT
            )
        }
        if (filterChip != null && filterLabel != null) {
            filterChip.text = filterLabel
        }
        if (formatChip != null && formatLabel != null) {
            formatChip.text = formatLabel
        }
        if (densityChip != null && density != null) {
            densityChip.text = "보기: ${density.label}"
            val highlighted = density != LogDisplayDensity.SUMMARY
            densityChip.setTextColor(if (highlighted) UiStyle.CYAN else UiStyle.INK)
            densityChip.background = UiStyle.rippleRounded(
                context,
                if (highlighted) UiStyle.CYAN_SOFT else UiStyle.SURFACE_ALT,
                10,
                if (highlighted) UiStyle.CYAN else UiStyle.BORDER
            )
            logPanel.setDisplayDensity(density)
        }
        if (dedupChip != null && dedupEnabled != null) {
            dedupChip.text = if (dedupEnabled) "● ×N 묶기" else "×N 묶기"
            dedupChip.setTextColor(if (dedupEnabled) UiStyle.ON_ACCENT else UiStyle.SECONDARY)
            dedupChip.background = UiStyle.rippleRounded(
                context,
                if (dedupEnabled) UiStyle.AMBER else UiStyle.SURFACE_ALT,
                10,
                if (dedupEnabled) null else UiStyle.BORDER
            )
        }
        if (watchChip != null && watchCount != null) {
            val active = watchCount > 0
            watchChip.text = if (active) "⚠ 알림($watchCount)" else "알림"
            watchChip.setTextColor(if (active) UiStyle.DANGER else UiStyle.SECONDARY)
            watchChip.background = UiStyle.rippleRounded(
                context,
                if (active) UiStyle.DANGER_SOFT else UiStyle.SURFACE_ALT,
                10,
                if (active) UiStyle.DANGER else UiStyle.BORDER
            )
        }
    }

    fun updateEntries(entries: List<LogEntry>, addedDelta: Int = 0) {
        allEntries = entries
        applyFilter(addedDelta)
    }

    private fun applyFilter(addedDelta: Int) {
        val visible = LogFilter.filterEntries(allEntries, searchQuery)
        if (searchQuery.isNotBlank() && visible.isEmpty()) {
            logPanel.setEmptyText("'$searchQuery' 검색 결과 없음 (전체 ${allEntries.size}건)")
        } else {
            logPanel.setEmptyText(defaultEmptyText)
        }
        logPanel.submitEntries(visible, addedDelta = addedDelta)
    }

    fun setConsoleInputHint(hintText: String) {
        consoleInput?.hint = hintText
    }
}
