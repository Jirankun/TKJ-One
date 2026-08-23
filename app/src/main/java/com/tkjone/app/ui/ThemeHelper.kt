package com.tkjone.app.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.graphics.drawable.GradientDrawable

/**
 * ThemeHelper — DARK THEME ONLY.
 * All color getters always return dark values. isDark parameter kept for
 * API compatibility but ignored.
 */
object ThemeHelper {
    // Brand accent
    const val PRIMARY_CYAN = 0xFF0A84FF.toInt()

    // iOS System Colors
    const val ACCENT_BLUE = 0xFF0A84FF.toInt()
    const val ACCENT_GREEN = 0xFF34C759.toInt()
    const val ACCENT_RED = 0xFFFF453A.toInt()
    const val ACCENT_ORANGE = 0xFFFF9F0A.toInt()
    const val ACCENT_YELLOW = 0xFFFFD60A.toInt()
    const val ACCENT_PURPLE = 0xFFBF5AF2.toInt()
    const val ACCENT_PINK = 0xFFFF375F.toInt()
    const val ACCENT_TEAL = 0xFF64D2FF.toInt()

    // Dark mode colors (the only theme)
    const val BG = 0xFF1C1C1E.toInt()
    const val BG2 = 0xFF2C2C2E.toInt()
    const val BG3 = 0xFF3A3A3C.toInt()
    const val CARD_BG = 0xFF2C2C2E.toInt()
    const val SEP = 0xFF38383A.toInt()
    const val LABEL = 0xFFFFFFFF.toInt()
    const val SEC_LABEL = 0xFF98989E.toInt()

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val LOGOUT_RED = 0xFFFF0D00.toInt()

    // Color getters — always return dark values
    fun getBgColor(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = BG
    fun getBg2Color(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = BG2
    fun getCardColor(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = CARD_BG
    fun getSepColor(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = SEP
    fun getLabelColor(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = LABEL
    fun getSecLabelColor(@Suppress("UNUSED_PARAMETER") isDark: Boolean = true): Int = SEC_LABEL

    fun applyStatusBarStyle(window: Window) {
        window.statusBarColor = BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decorView = window.decorView
            decorView.systemUiVisibility = decorView.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    fun applyNavBarColor(window: Window) {
        window.navigationBarColor = BG
    }

    fun createRoundedBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 3
        }
    }
}
