package com.tkjone.app.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.graphics.drawable.GradientDrawable
import com.tkjone.app.service.AppThemeMode

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

    // Dark mode
    const val DARK_BG = 0xFF1C1C1E.toInt()
    const val DARK_BG2 = 0xFF2C2C2E.toInt()
    const val DARK_BG3 = 0xFF3A3A3C.toInt()
    const val CARD_BG = 0xFF2C2C2E.toInt()
    const val DARK_SEP = 0xFF38383A.toInt()
    const val DARK_LABEL = 0xFFFFFFFF.toInt()
    const val DARK_SEC_LABEL = 0xFF98989E.toInt()

    // Light mode
    const val LIGHT_BG = 0xFFF2F2F7.toInt()
    const val LIGHT_BG2 = 0xFFFFFFFF.toInt()
    const val LIGHT_SEP = 0xFFC6C6C8.toInt()
    const val LIGHT_LABEL = 0xFF000000.toInt()
    const val LIGHT_SEC_LABEL = 0xFF8E8E93.toInt()

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    // Profile logout
    const val LOGOUT_RED = 0xFFFF0D00.toInt()

    // Story handles
    const val STORY_HANDLE_DARK = 0x4DFFFFFF
    const val STORY_HANDLE_LIGHT = 0x33000000

    fun isDarkMode(activity: Activity, mode: AppThemeMode): Boolean {
        return when (mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.AUTO -> {
                val flags = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                flags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun getBgColor(isDark: Boolean): Int = if (isDark) DARK_BG else LIGHT_BG
    fun getBg2Color(isDark: Boolean): Int = if (isDark) DARK_BG2 else LIGHT_BG2
    fun getCardColor(isDark: Boolean): Int = if (isDark) CARD_BG else WHITE
    fun getSepColor(isDark: Boolean): Int = if (isDark) DARK_SEP else LIGHT_SEP
    fun getLabelColor(isDark: Boolean): Int = if (isDark) DARK_LABEL else LIGHT_LABEL
    fun getSecLabelColor(isDark: Boolean): Int = if (isDark) DARK_SEC_LABEL else LIGHT_SEC_LABEL

    fun applyStatusBarStyle(window: Window, isDark: Boolean) {
        window.statusBarColor = getBgColor(isDark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decorView = window.decorView
            decorView.systemUiVisibility = if (isDark) {
                decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    fun applyNavBarColor(window: Window, isDark: Boolean) {
        window.navigationBarColor = getBgColor(isDark)
    }

    fun createRoundedBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 3
        }
    }
}
