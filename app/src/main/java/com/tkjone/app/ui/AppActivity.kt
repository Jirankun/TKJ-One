package com.tkjone.app.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator

/**
 * Base activity — all shared helpers live here once.
 * Both MainActivity and UriViewerActivity extend this.
 */
open class AppActivity : Activity() {

    // ─── Theme ───

    val isDark: Boolean = true // Dark theme only — always true

    // ─── Dimensions ───

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    fun dpFloat(v: Float): Float = v * resources.displayMetrics.density

    // ─── Keyboard ───

    fun hideKeyboardFrom(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun makeFocusableOutside(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val f = currentFocus
                if (f is EditText) {
                    hideKeyboardFrom(f)
                    f.clearFocus()
                }
            }
            false
        }
    }

    // ─── View Styling Helpers ───

    fun styledButton(
        color: Int,
        radius: Float = 12f,
        strokeWidth: Int = 0,
        strokeColor: Int = 0
    ): GradientDrawable = ThemeHelper.createRoundedBackground(color, radius).apply {
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    fun cardBackground(isDark: Boolean, radius: Float = 16f): GradientDrawable =
        ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), radius).apply {
            setStroke(1, ThemeHelper.getSepColor(isDark))
        }

    fun applyThemeToView(view: View, isDark: Boolean) {
        view.setBackgroundColor(ThemeHelper.getBgColor(isDark))
    }

    fun applyCardTheme(view: View, isDark: Boolean) {
        view.setBackgroundColor(ThemeHelper.getCardColor(isDark))
    }

    fun statusColor(isDark: Boolean): Int = ThemeHelper.getSecLabelColor(isDark)
    fun labelColor(isDark: Boolean): Int = ThemeHelper.getLabelColor(isDark)
    fun bgColor(isDark: Boolean): Int = ThemeHelper.getBgColor(isDark)
    fun bg2Color(isDark: Boolean): Int = ThemeHelper.getBg2Color(isDark)
    fun sepColor(isDark: Boolean): Int = ThemeHelper.getSepColor(isDark)

    // ─── Animations ───

    fun animateScreenIn(view: View) {
        view.alpha = 0f; view.translationX = 60f
        view.animate().alpha(1f).translationX(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun animateScreenOut(view: View, onEnd: (() -> Unit)? = null) {
        view.animate().alpha(0f).translationX(-60f).setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { onEnd?.invoke() }.start()
    }

    fun animateScreenInFromBottom(view: View) {
        view.alpha = 0f; view.translationY = 80f
        view.animate().alpha(1f).translationY(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun animateDialogIn(view: View) {
        view.scaleX = 0.85f; view.scaleY = 0.85f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun animateCardIn(view: View, delay: Int = 0) {
        view.alpha = 0f; view.translationY = 30f
        view.animate().alpha(1f).translationY(0f).setDuration(300)
            .setStartDelay(delay.toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
}
