package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.util.Linkify
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SharedNotesDialog(
    private val activity: Activity,
    private val target: BoardTarget
) {
    private val dialog = Dialog(activity)
    private lateinit var titleInput: EditText
    private lateinit var textInput: EditText
    private lateinit var saveBtn: Button
    private lateinit var cancelEditBtn: Button
    private lateinit var statusLabel: TextView
    private lateinit var listBox: LinearLayout
    private var editingId: Long? = null

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiStyle.rounded(activity, UiStyle.SURFACE, 18, UiStyle.BORDER)
            val p = UiStyle.dp(activity, 16)
            setPadding(p, p, p, p)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                UiStyle.label(activity, "빠른 메모 · 링크 공유판", 17f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            val closeBtn = UiStyle.secondaryButton(activity, "닫기") { dialog.dismiss() }
            addView(closeBtn)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 10) })

        titleInput = EditText(activity).apply {
            hint = "제목 (선택)"
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            background = UiStyle.rounded(activity, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(activity, 12), UiStyle.dp(activity, 10), UiStyle.dp(activity, 12), UiStyle.dp(activity, 10))
        }
        textInput = EditText(activity).apply {
            hint = "기기 간 공유할 메모나 URL을 입력하세요"
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = UiStyle.rounded(activity, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(activity, 12), UiStyle.dp(activity, 10), UiStyle.dp(activity, 12), UiStyle.dp(activity, 10))
        }
        root.addView(titleInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 8) })
        root.addView(textInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 8) })

        saveBtn = UiStyle.primaryButton(activity, "메모 저장") { submitNote() }
        cancelEditBtn = UiStyle.secondaryButton(activity, "취소") { resetEditor() }.apply {
            visibility = android.view.View.GONE
        }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelEditBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(activity, 8) })
            addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 10) })

        statusLabel = UiStyle.label(activity, "메모 불러오는 중...", 12.5f, UiStyle.MUTED)
        root.addView(statusLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 8) })

        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            addView(listBox, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, UiStyle.dp(activity, 300)))

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
        refreshNotes()
    }

    private fun resetEditor() {
        editingId = null
        titleInput.setText("")
        textInput.setText("")
        saveBtn.text = "메모 저장"
        cancelEditBtn.visibility = android.view.View.GONE
    }

    private fun refreshNotes() {
        statusLabel.text = "SD카드 메모 불러오는 중..."
        Thread({
            val list = runCatching { BoardApi(target).listNotes() }.getOrNull()
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                renderNotes(list)
            }
        }, "sd-notes-load").start()
    }

    private fun submitNote() {
        val title = titleInput.text.toString().trim()
        val text = textInput.text.toString().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val editTarget = editingId
        saveBtn.isEnabled = false
        Thread({
            val updated = runCatching {
                val api = BoardApi(target)
                if (editTarget != null) api.updateNote(editTarget, title, text)
                else api.addNote(title, text)
            }.getOrNull()
            activity.runOnUiThread {
                saveBtn.isEnabled = true
                if (!dialog.isShowing) return@runOnUiThread
                if (updated != null) {
                    resetEditor()
                    renderNotes(updated)
                    Toast.makeText(activity, "메모를 저장했습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "메모를 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "sd-notes-save").start()
    }

    private fun deleteNote(note: SharedNote) {
        Thread({
            val updated = runCatching { BoardApi(target).deleteNote(note.id) }.getOrNull()
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (updated != null) {
                    renderNotes(updated)
                } else {
                    Toast.makeText(activity, "메모를 삭제하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "sd-notes-del").start()
    }

    private fun renderNotes(list: List<SharedNote>?) {
        listBox.removeAllViews()
        if (list == null) {
            statusLabel.text = "메모를 불러올 수 없습니다"
            return
        }
        statusLabel.text = if (list.isEmpty()) "저장된 메모가 없습니다" else "저장된 메모 ${list.size}개 (PC 웹과 실시간 공유)"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        list.forEach { note ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiStyle.rounded(activity, UiStyle.BG, 12, UiStyle.BORDER)
                val p = UiStyle.dp(activity, 12)
                setPadding(p, p, p, p)
            }
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val timeStr = if (note.ts > 1700000000L) "  ${fmt.format(Date(note.ts * 1000L))}" else ""
                addView(
                    UiStyle.label(activity, (note.title.ifEmpty { "메모" }) + timeStr, 13.5f, UiStyle.INK, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
            }
            val body = TextView(activity).apply {
                text = note.text
                textSize = 13.5f
                setTextColor(UiStyle.SECONDARY)
                setLinkTextColor(UiStyle.CYAN)
                Linkify.addLinks(this, Linkify.WEB_URLS)
                setPadding(0, UiStyle.dp(activity, 6), 0, UiStyle.dp(activity, 8))
            }
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                val copyBtn = UiStyle.secondaryButton(activity, "복사") {
                    val clip = activity.getSystemService(ClipboardManager::class.java)
                    clip?.setPrimaryClip(ClipData.newPlainText("DockyCam", note.text.ifEmpty { note.title }))
                    Toast.makeText(activity, "클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
                }
                val editBtn = UiStyle.secondaryButton(activity, "수정") {
                    editingId = note.id
                    titleInput.setText(note.title)
                    textInput.setText(note.text)
                    saveBtn.text = "수정 저장"
                    cancelEditBtn.visibility = android.view.View.VISIBLE
                    textInput.requestFocus()
                }
                val delBtn = UiStyle.dangerButton(activity, "삭제") { deleteNote(note) }
                addView(copyBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(activity, 6) })
                addView(editBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(activity, 6) })
                addView(delBtn, LinearLayout.LayoutParams(0, -2, 1f))
            }
            card.addView(top)
            if (note.text.isNotEmpty()) card.addView(body)
            card.addView(actions)
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(activity, 8) })
        }
    }
}
