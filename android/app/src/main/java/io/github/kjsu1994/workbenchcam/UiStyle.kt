package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

internal object UiStyle {
    const val BG = 0xff0B0F0E.toInt()
    const val SURFACE = 0xff131B18.toInt()
    const val SURFACE_ALT = 0xff1A2420.toInt()
    const val NAV_BG = 0xff0F1513.toInt()
    const val BORDER = 0xff24322D.toInt()
    const val INK = 0xffF3F7F5.toInt()
    const val SECONDARY = 0xff9CB0A6.toInt()
    const val MUTED = 0xff697D73.toInt()
    const val ACCENT = 0xff34D399.toInt()
    const val ACCENT_SOFT = 0xff123327.toInt()
    const val ON_ACCENT = 0xff042016.toInt()
    const val DANGER = 0xffF87171.toInt()
    const val DANGER_SOFT = 0xff36181A.toInt()
    const val AMBER = 0xffFBBF24.toInt()
    const val AMBER_SOFT = 0xff33270F.toInt()
    const val OVERLAY = 0xcc090E0C.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(
        context: Context,
        color: Int,
        radiusDp: Int = 16,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(context, strokeDp), strokeColor)
    }

    fun rippleRounded(
        context: Context,
        color: Int,
        radiusDp: Int = 14,
        strokeColor: Int? = null,
        rippleColor: Int = 0x3334D399
    ): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(rippleColor),
        rounded(context, color, radiusDp, strokeColor),
        null
    )

    fun card(context: Context, paddingDp: Int = 16): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, SURFACE, 18, BORDER)
        val p = dp(context, paddingDp)
        setPadding(p, p, p, p)
    }

    fun label(
        context: Context,
        text: String,
        sizeSp: Float = 14f,
        color: Int = INK,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun sectionTitle(context: Context, title: String): TextView = label(
        context, title, 12f, MUTED, bold = true
    ).apply {
        letterSpacing = 0.06f
        setPadding(dp(context, 4), dp(context, 4), 0, dp(context, 8))
    }

    fun badge(context: Context, text: String, bgColor: Int, textColor: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        background = rounded(context, bgColor, 99)
        setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
    }

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        styledButton(context, label, ACCENT, ON_ACCENT, null, 48, onClick)

    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        styledButton(context, label, SURFACE_ALT, INK, BORDER, 46, onClick)

    fun dangerButton(context: Context, label: String, onClick: () -> Unit): Button =
        styledButton(context, label, DANGER_SOFT, DANGER, 0xff5A2427.toInt(), 46, onClick)

    fun amberButton(context: Context, label: String, onClick: () -> Unit): Button =
        styledButton(context, label, AMBER_SOFT, AMBER, 0xff594218.toInt(), 46, onClick)

    private fun styledButton(
        context: Context,
        label: String,
        bg: Int,
        fg: Int,
        stroke: Int?,
        heightDp: Int,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(context, heightDp)
        minimumHeight = dp(context, heightDp)
        stateListAnimator = null
        setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
        val enabledState = intArrayOf(android.R.attr.state_enabled)
        setTextColor(ColorStateList(arrayOf(enabledState, intArrayOf()), intArrayOf(fg, MUTED)))
        background = StateListDrawable().apply {
            addState(enabledState, rippleRounded(context, bg, 14, stroke))
            addState(intArrayOf(), rounded(context, 0xff171F1C.toInt(), 14, BORDER))
        }
        setOnClickListener { onClick() }
    }

    fun iconCircleButton(
        context: Context,
        iconRes: Int,
        contentDesc: String,
        bgColor: Int = SURFACE_ALT,
        tint: Int = INK,
        sizeDp: Int = 40,
        onClick: () -> Unit
    ): FrameLayout {
        val size = dp(context, sizeDp)
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconSize = dp(context, 20)
        return FrameLayout(context).apply {
            contentDescription = contentDesc
            isClickable = true
            isFocusable = true
            background = rippleRounded(context, bgColor, 99, BORDER)
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            setOnClickListener { onClick() }
        }
    }

    class ActionTile(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView
    ) {
        fun setState(enabled: Boolean, active: Boolean = false, labelText: String? = null) {
            if (labelText != null) title.text = labelText
            root.isEnabled = enabled
            root.alpha = if (enabled) 1f else 0.42f
            val fg = if (active) ACCENT else INK
            icon.imageTintList = ColorStateList.valueOf(fg)
            title.setTextColor(if (active) ACCENT else SECONDARY)
            root.background = rippleRounded(
                root.context,
                if (active) ACCENT_SOFT else SURFACE,
                14,
                if (active) ACCENT else BORDER
            )
        }
    }

    fun actionTile(context: Context, iconRes: Int, labelText: String, onClick: () -> Unit): ActionTile {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(INK)
        }
        val title = label(context, labelText, 12f, SECONDARY, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rippleRounded(context, SURFACE, 14, BORDER)
            setPadding(dp(context, 6), dp(context, 10), dp(context, 6), dp(context, 10))
            addView(icon, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)))
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(context, 5) })
            setOnClickListener { onClick() }
        }
        return ActionTile(root, icon, title)
    }

    fun switchRow(
        context: Context,
        titleText: String,
        subtitleText: String?,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context, titleText, 14.5f, INK, bold = true))
            if (subtitleText != null) {
                addView(label(context, subtitleText, 12f, SECONDARY).apply {
                    setPadding(0, dp(context, 2), 0, 0)
                })
            }
        }
        val toggle = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, 0xff93A49B.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xff1E5B44.toInt(), 0xff26332E.toInt())
            )
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 52)
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(context, 12) })
            addView(toggle, LinearLayout.LayoutParams(-2, -2))
        }
        return row to toggle
    }

    fun <T> segmentedChips(
        context: Context,
        items: List<Pair<T, String>>,
        selected: T,
        onSelect: (T) -> Unit
    ): Pair<LinearLayout, (T) -> Unit> {
        val views = LinkedHashMap<T, TextView>()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(context, BG, 12, BORDER)
            val p = dp(context, 3)
            setPadding(p, p, p, p)
        }
        fun render(active: T) {
            views.forEach { (key, tv) ->
                val isSel = key == active
                tv.setTextColor(if (isSel) ON_ACCENT else SECONDARY)
                tv.typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = if (isSel) rounded(context, ACCENT, 10) else null
            }
        }
        items.forEach { (key, title) ->
            val tv = TextView(context).apply {
                text = title
                textSize = 12.5f
                gravity = Gravity.CENTER
                minimumHeight = dp(context, 36)
                setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                isClickable = true
                setOnClickListener {
                    render(key)
                    onSelect(key)
                }
            }
            views[key] = tv
            container.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        }
        render(selected)
        return container to ::render
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatMb(mb: Long): String = if (mb >= 1024L) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        "$mb MB"
    }

    fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun formatSdTitle(name: String): String {
        // BB_20261125_223015.avi -> 2026-11-25 22:30:15
        val regex = Regex("^[A-Z]+_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})\\..+$")
        val match = regex.matchEntire(name) ?: return name
        val (y, mo, d, h, mi, s) = match.destructured
        return "$y-$mo-$d  $h:$mi:$s"
    }

    fun categoryMeta(cat: String): Triple<String, Int, Int> = when (cat) {
        "blackbox" -> Triple("블랙박스", DANGER_SOFT, DANGER)
        "timelapse" -> Triple("타임랩스", AMBER_SOFT, AMBER)
        "snapshots" -> Triple("스냅샷", ACCENT_SOFT, ACCENT)
        else -> Triple(cat, SURFACE_ALT, SECONDARY)
    }
}
