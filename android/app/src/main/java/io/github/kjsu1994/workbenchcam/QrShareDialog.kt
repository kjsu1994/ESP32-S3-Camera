package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Sleek dark-themed Local QR Airdrop dialog for instant cross-device file download or Web UI access.
 */
internal object QrShareDialog {
    fun show(
        activity: Activity,
        title: String,
        subtitle: String,
        url: String,
        wifiHint: String
    ) {
        val qrBitmap = runCatching { QrCodeGenerator.toBitmap(url, pixelSize = 620, quietZone = 4) }
            .getOrElse { err ->
                Toast.makeText(activity, err.message ?: "QR 코드를 생성할 수 없습니다", Toast.LENGTH_LONG).show()
                return
            }

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = UiStyle.card(activity, 20).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        card.addView(UiStyle.badge(activity, "로컬 QR 에어드롭", UiStyle.CYAN_SOFT, UiStyle.CYAN))
        card.addView(UiStyle.label(activity, title, 16.5f, UiStyle.INK, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, UiStyle.dp(activity, 10), 0, 0)
        })
        if (subtitle.isNotEmpty()) {
            card.addView(UiStyle.label(activity, subtitle, 12.5f, UiStyle.SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, UiStyle.dp(activity, 3), 0, 0)
            })
        }

        // High-contrast crisp white QR container for instant camera recognition
        val qrSizePx = UiStyle.dp(activity, 224)
        val qrFrame = FrameLayout(activity).apply {
            background = UiStyle.rounded(activity, Color.WHITE, 18)
            val pad = UiStyle.dp(activity, 12)
            setPadding(pad, pad, pad, pad)
            addView(ImageView(activity).apply {
                setImageBitmap(qrBitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "QR 코드"
            }, FrameLayout.LayoutParams(qrSizePx, qrSizePx, Gravity.CENTER))
        }
        card.addView(qrFrame, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = UiStyle.dp(activity, 16)
            bottomMargin = UiStyle.dp(activity, 14)
        })

        // Monospace URL preview pill
        val urlPill = UiStyle.label(activity, url, 11.5f, UiStyle.ACCENT).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            background = UiStyle.rounded(activity, UiStyle.BG, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(activity, 12), UiStyle.dp(activity, 8), UiStyle.dp(activity, 12), UiStyle.dp(activity, 8))
        }
        card.addView(urlPill, LinearLayout.LayoutParams(-1, -2))

        card.addView(UiStyle.label(activity, wifiHint, 12f, UiStyle.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiStyle.dp(activity, 10), 0, UiStyle.dp(activity, 14))
        })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(activity, 8)
            addView(UiStyle.secondaryButton(activity, "링크 복사") {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("DockyCam URL", url))
                Toast.makeText(activity, "주소를 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(UiStyle.primaryButton(activity, "닫기") { d.dismiss() },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))

        val wrap = FrameLayout(activity).apply {
            val m = UiStyle.dp(activity, 20)
            setPadding(m, m, m, m)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        d.setContentView(wrap)
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        d.setOnDismissListener {
            if (!qrBitmap.isRecycled) qrBitmap.recycle()
        }
        d.show()
    }
}
