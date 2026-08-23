package com.tkjone.app.service

import android.content.Context
import android.content.SharedPreferences

enum class AppThemeMode { DARK, LIGHT, AUTO }
enum class GridLayout { GRID, STAGGERED }

object SettingsService {
    private const val THEME_KEY = "app_theme_mode"
    private const val LAYOUT_KEY = "grid_layout"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("tkjone_prefs", Context.MODE_PRIVATE)
    }

    fun getThemeMode(context: Context): AppThemeMode {
        val raw = getPrefs(context).getString(THEME_KEY, "dark") ?: "dark"
        return try {
            AppThemeMode.valueOf(raw.uppercase())
        } catch (_: Exception) {
            AppThemeMode.DARK
        }
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        getPrefs(context).edit().putString(THEME_KEY, mode.name.lowercase()).apply()
    }

    fun getGridLayout(context: Context): GridLayout {
        val raw = getPrefs(context).getString(LAYOUT_KEY, "grid") ?: "grid"
        return try {
            GridLayout.valueOf(raw.uppercase())
        } catch (_: Exception) {
            GridLayout.GRID
        }
    }

    fun setGridLayout(context: Context, layout: GridLayout) {
        getPrefs(context).edit().putString(LAYOUT_KEY, layout.name.lowercase()).apply()
    }
}
