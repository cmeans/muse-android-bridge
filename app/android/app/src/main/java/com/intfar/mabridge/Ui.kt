package com.intfar.mabridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small builders for the owner and setup screens. Plain framework views, no extra
 * libraries: colors come from res/values (light) and res/values-night (dark).
 */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).toInt()
    fun color(id: Int): Int = ctx.getColor(id)

    /** A rounded background, optionally with a border. */
    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 2) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), color(stroke))
    }

    fun text(t: CharSequence, sizeSp: Float = 15f, colorId: Int = R.color.text_main, weight: Int = 400) =
        TextView(ctx).apply {
            text = t
            textSize = sizeSp
            setTextColor(color(colorId))
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        }

    /** The small capitals label above a section ("STOP", "RECENT"). */
    fun sectionLabel(t: String) = text(t, 13f, R.color.text_muted, 600).apply {
        letterSpacing = 0.04f
        setPadding(dp(2), dp(16), 0, dp(8))
    }

    fun column(gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(0, dp(gapDp)) }
        }
    }

    fun row(gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 0) }
        }
    }

    /** A card: white (or dark) on the screen's gray, padded. */
    fun card(fill: Int = R.color.card_bg, stroke: Int? = null, padV: Int = 16, padH: Int = 18, radius: Int = 16) =
        column().apply {
            background = rounded(fill, radius, stroke)
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }

    /** A filled button: the main action on a card or screen. At least 48 dp tall. */
    fun primaryButton(t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(color(android.R.color.white))
        background = rounded(R.color.button_bg, 12)
        minHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    /** An outlined button for a second choice, in [textColor]. */
    fun outlineButton(t: String, textColor: Int = R.color.text_main, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(color(textColor))
        background = rounded(R.color.card_bg, 12, R.color.card_border)
        minHeight = dp(48)
        stateListAnimator = null
        // No default vertical padding, as primaryButton: at the largest font size the label
        // must fit a fixed-height row.
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    /** Text that acts as a link: a real button for TalkBack, 48 dp tall. */
    fun linkButton(t: String, colorId: Int = R.color.link, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        setTextColor(color(colorId))
        background = null
        minHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    /** A colored dot, as on the status card. */
    fun dot(colorId: Int, sizeDp: Int = 14) = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(colorId)) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun tint(colorId: Int) = ColorStateList.valueOf(color(colorId))

    fun matchWidth(topMarginDp: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topMarginDp) }

    fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    /**
     * Keeps a screen's content out from under the status and navigation bars, and on a
     * ScrollView, the focused field out from under the keyboard. Android 15+ draws apps
     * targeting SDK 35+ edge to edge, so without this, scrolled content passes under the
     * clock and the gesture bar, and nothing makes room for the keyboard.
     */
    fun keepClearOfSystemBars(view: View) {
        // Android 10 (minSdk 29) never draws an app edge to edge, and these insets APIs are 30+.
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (view is android.widget.ScrollView) {
            // Focus can move with the keyboard already up (its Next key, a tap on a field's
            // visible edge), which dispatches no new insets, so follow focus changes too.
            view.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
                if (now != null && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
                    view.post { scrollFocusAboveBottomPadding(view) }
                }
            }
        }
        view.setOnApplyWindowInsetsListener { v, insets ->
            // ime(): edge to edge, the window no longer resizes for the keyboard, so the
            // bottom padding takes the larger of the navigation bar and the open keyboard,
            // and the setup screen's lower fields and Save can scroll above it.
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or
                    android.view.WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // On 35+ nothing pans the window either, so once the keyboard is up, scroll the focused
            // field above it after the new padding is laid out. ScrollView's own
            // requestRectangleOnScreen measures against its full height, ignoring the padding,
            // so it would think a field under the keyboard is already visible.
            if (v is android.widget.ScrollView && insets.isVisible(android.view.WindowInsets.Type.ime())) {
                v.post { scrollFocusAboveBottomPadding(v) }
            }
            insets
        }
    }

    /** Scrolls [scroll] so its focused view sits above the bottom padding (the keyboard). */
    private fun scrollFocusAboveBottomPadding(scroll: android.widget.ScrollView) {
        val focused = scroll.findFocus() ?: return
        val r = android.graphics.Rect()
        focused.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(focused, r)
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        val needed = r.bottom + dp(16) - visibleBottom
        if (needed > 0) scroll.smoothScrollBy(0, needed)
    }
}
