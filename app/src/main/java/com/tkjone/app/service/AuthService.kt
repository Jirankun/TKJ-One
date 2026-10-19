package com.tkjone.app.service

import android.content.Context
import android.content.SharedPreferences
import com.tkjone.app.R
import com.tkjone.app.model.UserStatus

object AuthService {
    private const val USER_KEY = "logged_user"
    private const val PREFS_NAME = "tkjone_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private suspend fun getValidUsers(): List<UserStatus> {
        val result = ApiService.fetchUserStatus()
        return if (result.isSuccess) result.data.orEmpty() else emptyList()
    }

    suspend fun getLoggedInUser(context: Context): String? {
        val user = getPrefs(context).getString(USER_KEY, null)
        return if (!user.isNullOrEmpty()) user else null
    }

    suspend fun login(context: Context, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        val validUsers = getValidUsers()
        if (validUsers.isEmpty()) return null
        val normalizedInput = trimmed.lowercase().replace(" ", "")
        val matchedUser = validUsers.find { it.normalizedName == normalizedInput } ?: return null
        if (!matchedUser.isActive) return null

        getPrefs(context).edit().putString(USER_KEY, trimmed).apply()
        return trimmed
    }

    suspend fun logout(context: Context) {
        getPrefs(context).edit().remove(USER_KEY).apply()
    }

    suspend fun checkUserStatus(context: Context, currentUserName: String): String? {
        val trimmed = currentUserName.trim()
        if (trimmed.isEmpty()) return context.getString(R.string.user_invalid)

        val validUsers = getValidUsers()
        if (validUsers.isEmpty()) return null

        val normalizedInput = trimmed.lowercase().replace(" ", "")
        val matchedUser = validUsers.find { it.normalizedName == normalizedInput }

        if (matchedUser == null) return context.getString(R.string.user_deleted)
        if (!matchedUser.isActive) {
            return matchedUser.suspendReason.ifEmpty { context.getString(R.string.user_suspended) }
        }
        return null
    }

    fun validateName(context: Context, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return context.getString(R.string.login_error_empty)
        if (trimmed.length < 2) return context.getString(R.string.login_error_too_short)
        if (trimmed.length > 30) return context.getString(R.string.login_error_too_long)
        return null
    }

    suspend fun getLoginErrorMessage(context: Context, name: String): String {
        val trimmed = name.trim()
        val validUsers = getValidUsers()

        if (validUsers.isEmpty()) return context.getString(R.string.login_error_no_connection)

        val normalizedInput = trimmed.lowercase().replace(" ", "")
        val matchedUser = validUsers.find { it.normalizedName == normalizedInput }

        if (matchedUser == null) return context.getString(R.string.login_error_not_registered)
        if (!matchedUser.isActive) {
            val reason = matchedUser.suspendReason.ifEmpty { context.getString(R.string.login_error_generic) }
            return context.getString(R.string.login_error_suspended_reason, reason)
        }
        return "" // user valid, no error
    }
}
