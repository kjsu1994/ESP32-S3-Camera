package io.github.kjsu1994.workbenchcam

import android.app.Dialog
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
import android.widget.TextView

internal data class LogEntry(
    val seq: Int,
    val header: String,
    val body: String,
    val accentColor: Int
) {
    fun toClipboardText(): String = if (body.isEmpty()) header else "$header\n  $body"
}

internal object LogFilter {
    const val MIN_FONT_SP = 9.5f
    const val MAX_FONT_SP = 15.5f
    const val DEFAULT_FONT_SP = 11.5f

    fun clampFontSize(sp: Float): Float = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    fun matchesQuery(header: String, body: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.all { token ->
            header.contains(token, ignoreCase = true) || body.contains(token, ignoreCase = true)
        }
    }

    fun filterEntries(entries: List<LogEntry>, query: String): List<LogEntry> {
        if (query.isBlank()) return entries
        return entries.filter { matchesQuery(it.header, it.body, query) }
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
        val bodyView: TextView
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
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val hPad = UiStyle.dp(context, 10)
                val vPad = UiStyle.dp(context, 6)
                setPadding(hPad, vPad, hPad, vPad)
                addView(headerTv, LinearLayout.LayoutParams(-1, -2))
                addView(bodyTv, LinearLayout.LayoutParams(-1, -2))
            }
            holder = Holder(row, headerTv, bodyTv)
            row.tag = holder
            view = row
        } else {
            view = convertView
            holder = convertView.tag as Holder
        }

        val entry = items[position]
        holder.headerView.text = "#${entry.seq} ${entry.header}"
        holder.headerView.setTextColor(entry.accentColor)
        holder.headerView.textSize = (bodyFontSp - 0.5f).coerceAtLeast(9f)
        if (entry.body.isEmpty()) {
            holder.bodyView.visibility = View.GONE
        } else {
            holder.bodyView.visibility = View.VISIBLE
            holder.bodyView.text = entry.body
            holder.bodyView.textSize = bodyFontSp
        }
        return view
    }
}

/**
 * Scrollable, high-throughput terminal log viewer with chronological ordering,
 * touch-aware auto-follow pause/resume, floating jump-to-latest pill, and 1-tap row copy.
 */
internal class LiveLogPanel(
    private val context: Context,
    private var emptyPlaceholder: String,
    inlineMode: Boolean,
    private val onCopyEntry: (LogEntry) -> Unit
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
                    onCopyEntry(currentEntries[position])
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
        return currentEntries.joinToString("\n\n") { "#${it.seq} ${it.toClipboardText()}" }
    }
}

internal data class FullscreenLogActions(
    val onToggleRun: (() -> Unit)? = null,
    val onCycleFilter: (() -> Unit)? = null,
    val onCycleFormat: (() -> Unit)? = null,
    val onExportPcap: (() -> Unit)? = null,
    val onCopyAll: () -> Unit,
    val onClear: () -> Unit,
    val onSendConsoleCommand: ((String) -> Unit)? = null
)

/**
 * Dedicated edge-to-edge fullscreen live log viewer with keep-screen-on,
 * real-time search filter, font size adjustment, auto-scroll lock, and quick controls.
 */
internal class FullscreenLogDialog(
    private val context: Context,
    title: String,
    private val defaultEmptyText: String,
    private val actions: FullscreenLogActions,
    onCopyEntry: (LogEntry) -> Unit,
    private val onDismissed: () -> Unit
) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
    private val statusDot: View
    private val titleView: TextView
    private val statsView: TextView
    private val runChip: TextView?
    private val filterChip: TextView?
    private val formatChip: TextView?
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
            hint = "실시간 로그 필터 (예: C→T, 01 03, GET, ERROR)"
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
            onCopyEntry = onCopyEntry
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

    fun isShowing(): Boolean = dialog.isShowing

    fun dismiss() {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }

    fun updateHeader(
        statusText: String,
        statusColor: Int,
        running: Boolean,
        runLabel: String? = null,
        filterLabel: String? = null,
        formatLabel: String? = null
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
