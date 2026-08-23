package com.tkjone.app.service

import android.content.Context
import android.content.SharedPreferences

object SettingsService {
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("tkjone_prefs", Context.MODE_PRIVATE)
    }
}
