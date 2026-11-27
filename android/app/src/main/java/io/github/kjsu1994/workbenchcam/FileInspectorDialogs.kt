package io.github.kjsu1994.workbenchcam

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Fullscreen interactive viewer for offline `.pcap`, `.har`, and `.saz` capture files.
 * Displays REA-style capture summary, 1-tap defensive security audit, keyword/-exclude search,
 * density/dedup controls, and tap-to-inspect PacketDetailDialog.
 */
internal class OfflineCaptureDialog(
    private val context: Context,
    private val parsed: ParsedCaptureResult,
    private val onReplayEntry: ((LogEntry) -> Unit)? = null
) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
    private var density = LogDisplayDensity.SUMMARY
    private var dedup = false
    private var query = ""
    private var detailDlg: PacketDetailDialog? = null

    private val densityChip: TextView
    private val dedupChip: TextView
    private val logPanel: LiveLogPanel

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xff060908.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val titleTv = UiStyle.label(
            context,
            "${parsed.summary.formatLabel} 분석 · ${parsed.summary.sourceName}",
            14f,
            UiStyle.INK,
            bold = true
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val closeBtn = UiStyle.compactChipButton(
            context, "✕ 닫기", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { dismiss() }

        val summaryTv = UiStyle.label(context, parsed.summary.toDisplayText(), 11f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            val p = UiStyle.dp(context, 8)
            setPadding(p, p, p, p)
        }

        val auditBtn = UiStyle.compactChipButton(
            context, "보안 점검", UiStyle.DANGER_SOFT, UiStyle.DANGER, UiStyle.DANGER
        ) {
            val report = TrafficSecurityAuditor.audit(parsed.entries)
            showTextReportDialog(context, "오프라인 캡처 보안 점검", report.toDisplayText())
        }
        densityChip = UiStyle.compactChipButton(
            context, "보기: 요약", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) {
            val all = LogDisplayDensity.entries
            density = all[(density.ordinal + 1) % all.size]
            densityChip.text = "보기: ${density.label}"
            logPanel.setDisplayDensity(density)
            refreshList()
        }
        dedupChip = UiStyle.compactChipButton(
            context, "×N 묶기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) {
            dedup = !dedup
            dedupChip.text = if (dedup) "● ×N 묶기" else "×N 묶기"
            dedupChip.setTextColor(if (dedup) UiStyle.ON_ACCENT else UiStyle.SECONDARY)
            dedupChip.background = UiStyle.rippleRounded(
                context,
                if (dedup) UiStyle.AMBER else UiStyle.SURFACE_ALT,
                10,
                if (dedup) null else UiStyle.BORDER
            )
            refreshList()
        }
        val copySummaryBtn = UiStyle.compactChipButton(
            context, "요약 복사", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) {
            val audit = TrafficSecurityAuditor.audit(parsed.entries)
            copyToClipboard(context, "${parsed.summary.toDisplayText()}\n\n=== 보안 점검 ===\n${audit.toDisplayText()}")
        }

        val gap = UiStyle.dp(context, 6)
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(auditBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(densityChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(dedupChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(copySummaryBtn)
        }

        val searchField = EditText(context).apply {
            hint = "검색 (포함: GET 200  /  제외: -png -css)"
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 7), UiStyle.dp(context, 10), UiStyle.dp(context, 7))
            minimumHeight = UiStyle.dp(context, 40)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    refreshList()
                }
            })
        }

        val headerBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.SURFACE)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 12), UiStyle.dp(context, 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(closeBtn)
            })
            addView(summaryTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(controlsRow)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) }
            )
            addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })
        }

        logPanel = LiveLogPanel(
            context = context,
            emptyPlaceholder = "표시할 패킷이 없습니다.",
            inlineMode = false,
            onInspectEntry = { list, idx ->
                detailDlg?.dismiss()
                detailDlg = PacketDetailDialog(
                    context = context,
                    entries = list,
                    startIndex = idx,
                    onReplay = onReplayEntry?.let { cb ->
                        { entry ->
                            dismiss()
                            cb(entry)
                        }
                    }
                ).also { it.show() }
            },
            onLongCopyEntry = { entry ->
                copyToClipboard(context, entry.toClipboardText())
            }
        )
        logPanel.setAutoFollow(false, jumpNow = false)

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff060908.toInt())
            addView(headerBox, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) }, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            addView(logPanel.root, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        applyFullscreenInsets(rootLayout)
        dialog.setContentView(rootLayout)
        dialog.setOnDismissListener {
            detailDlg?.dismiss()
            detailDlg = null
        }
        refreshList()
    }

    private fun refreshList() {
        val base = if (dedup) LogFilter.collapseConsecutiveDuplicates(parsed.entries) else parsed.entries
        val filtered = LogFilter.filterEntries(base, query)
        logPanel.submitEntries(filtered, addedDelta = 0)
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    fun dismiss() {
        detailDlg?.dismiss()
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }
}

/**
 * Read-only SQLite database viewer (uses Android built-in SQLite engine, zero dependencies).
 * Safely copies selected content URI to a temporary cache file and deletes it on dismiss.
 */
internal class SqliteViewerDialog(
    private val context: Context,
    private val dbFile: File,
    private val displayName: String,
    private val deleteOnDismiss: Boolean
) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
    private var db: SQLiteDatabase? = null
    private val tableNames = ArrayList<Pair<String, Long>>()
    private var activeTable: String = ""
    private var filterQuery: String = ""

    private val tableChipRow: LinearLayout
    private val schemaText: TextView
    private val rowsContainer: LinearLayout
    private val rowsScroll: ScrollView

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xff060908.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val titleTv = UiStyle.label(context, "SQLite 뷰어 · $displayName", 14f, UiStyle.INK, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val closeBtn = UiStyle.compactChipButton(
            context, "✕ 닫기", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { dismiss() }

        tableChipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        schemaText = UiStyle.label(context, "테이블을 불러오는 중...", 11f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            val p = UiStyle.dp(context, 8)
            setPadding(p, p, p, p)
        }

        val searchField = EditText(context).apply {
            hint = "행 내용 키워드 검색..."
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 7), UiStyle.dp(context, 10), UiStyle.dp(context, 7))
            minimumHeight = UiStyle.dp(context, 40)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterQuery = s?.toString()?.trim() ?: ""
                    if (activeTable.isNotEmpty()) loadTableRows(activeTable)
                }
            })
        }

        val headerBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.SURFACE)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 12), UiStyle.dp(context, 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(closeBtn)
            })
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(tableChipRow)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) }
            )
            addView(schemaText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })
            addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })
        }

        rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 10)
            setPadding(p, p, p, p)
        }
        rowsScroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(0xff060908.toInt())
            addView(rowsContainer, FrameLayout.LayoutParams(-1, -2))
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff060908.toInt())
            addView(headerBox, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) }, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1).coerceAtLeast(1)))
            addView(rowsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        applyFullscreenInsets(rootLayout)
        dialog.setContentView(rootLayout)
        dialog.setOnDismissListener {
            runCatching { db?.close() }
            db = null
            if (deleteOnDismiss) runCatching { dbFile.delete() }
        }

        openDatabase()
    }

    private fun openDatabase() {
        try {
            val opened = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db = opened
            opened.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { cur ->
                while (cur.moveToNext()) {
                    val tName = cur.getString(0) ?: continue
                    val count = runCatching {
                        opened.rawQuery("SELECT COUNT(*) FROM \"${tName.replace("\"", "\"\"")}\"", null).use { c2 ->
                            if (c2.moveToFirst()) c2.getLong(0) else 0L
                        }
                    }.getOrDefault(0L)
                    tableNames.add(tName to count)
                }
            }
            if (tableNames.isEmpty()) {
                schemaText.text = "사용자 테이블이 없는 빈 SQLite 데이터베이스입니다."
            } else {
                activeTable = tableNames.first().first
                renderTableChips()
                loadTableRows(activeTable)
            }
        } catch (e: Exception) {
            schemaText.text = "SQLite 열기 실패: ${e.message ?: "유효한 .db 파일이 아닙니다"}"
            schemaText.setTextColor(UiStyle.DANGER)
        }
    }

    private fun renderTableChips() {
        tableChipRow.removeAllViews()
        val gap = UiStyle.dp(context, 6)
        tableNames.forEach { (name, count) ->
            val sel = name == activeTable
            tableChipRow.addView(
                UiStyle.compactChipButton(
                    context,
                    "$name ($count)",
                    if (sel) UiStyle.ACCENT else UiStyle.SURFACE_ALT,
                    if (sel) UiStyle.ON_ACCENT else UiStyle.INK,
                    if (sel) UiStyle.ACCENT else UiStyle.BORDER
                ) {
                    activeTable = name
                    renderTableChips()
                    loadTableRows(name)
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )
        }
    }

    private fun loadTableRows(tableName: String) {
        val opened = db ?: return
        val escaped = tableName.replace("\"", "\"\"")
        rowsContainer.removeAllViews()

        val cols = ArrayList<String>()
        runCatching {
            opened.rawQuery("PRAGMA table_info(\"$escaped\")", null).use { cur ->
                while (cur.moveToNext()) {
                    val cname = cur.getString(1) ?: ""
                    val ctype = cur.getString(2) ?: ""
                    cols.add("$cname($ctype)")
                }
            }
        }
        schemaText.text = "컬럼(${cols.size}): ${cols.joinToString(", ")}"

        var shown = 0
        try {
            opened.rawQuery("SELECT * FROM \"$escaped\" LIMIT 200", null).use { cur ->
                val colNames = cur.columnNames
                var rowIdx = 0
                while (cur.moveToNext()) {
                    rowIdx++
                    val rowLines = ArrayList<String>(colNames.size)
                    for (c in colNames.indices) {
                        val v = when (cur.getType(c)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_BLOB -> {
                                val b = cur.getBlob(c) ?: ByteArray(0)
                                "<BLOB ${b.size}B: ${HexCodec.bytesToHex(b, 12)}>"
                            }
                            else -> cur.getString(c) ?: ""
                        }
                        rowLines.add("${colNames[c]}: $v")
                    }
                    val fullRow = rowLines.joinToString("\n")
                    if (filterQuery.isNotEmpty() && !fullRow.contains(filterQuery, ignoreCase = true)) {
                        continue
                    }
                    shown++
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = UiStyle.rippleRounded(context, UiStyle.SURFACE, 10, UiStyle.BORDER)
                        val p = UiStyle.dp(context, 10)
                        setPadding(p, UiStyle.dp(context, 8), p, UiStyle.dp(context, 8))
                        addView(UiStyle.label(context, "ROW #$rowIdx", 11f, UiStyle.ACCENT, bold = true).apply {
                            typeface = Typeface.MONOSPACE
                        })
                        addView(UiStyle.label(context, fullRow.take(500), 11.5f, UiStyle.INK).apply {
                            typeface = Typeface.MONOSPACE
                            setPadding(0, UiStyle.dp(context, 3), 0, 0)
                        })
                        setOnClickListener {
                            showTextReportDialog(context, "$tableName · ROW #$rowIdx", fullRow)
                        }
                    }
                    rowsContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
                }
            }
            if (shown == 0) {
                rowsContainer.addView(UiStyle.label(context, "표시할 행이 없습니다.", 12f, UiStyle.MUTED))
            }
            rowsScroll.scrollTo(0, 0)
        } catch (e: Exception) {
            rowsContainer.addView(UiStyle.label(context, "조회 오류: ${e.message}", 12f, UiStyle.DANGER))
        }
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    fun dismiss() {
        if (dialog.isShowing) runCatching { dialog.dismiss() }
    }
}

internal fun showTextReportDialog(context: Context, title: String, content: String) {
    val tv = TextView(context).apply {
        text = content
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.INK)
        setTextIsSelectable(true)
        val p = UiStyle.dp(context, 14)
        setPadding(p, p, p, p)
    }
    val scroll = ScrollView(context).apply {
        setBackgroundColor(0xff060908.toInt())
        addView(tv, FrameLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton("복사") { _, _ -> copyToClipboard(context, content) }
        .setNegativeButton("닫기", null)
        .show()
}

internal fun copyToClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("DockyCam Security", text))
    Toast.makeText(context, "클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
}

internal fun copyUriToTempFile(context: Context, uri: Uri, prefix: String): File {
    val tmp = File.createTempFile(prefix, ".tmp", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { inp ->
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(16_384)
            var total = 0L
            while (total < 32L * 1024L * 1024L) { // 32MB safety cap for mobile memory
                val n = inp.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
        }
    } ?: throw IllegalStateException("파일을 읽을 수 없습니다")
    return tmp
}

private fun applyFullscreenInsets(view: View) {
    view.setOnApplyWindowInsetsListener { v, insets ->
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
}
