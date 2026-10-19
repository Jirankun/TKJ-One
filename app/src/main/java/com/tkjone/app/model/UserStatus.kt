package com.tkjone.app.model

import org.json.JSONObject

data class UserStatus(
    val name: String,
    val isActive: Boolean,
    val suspendReason: String,
    val profile: String
) {
    val normalizedName: String
        get() = name.lowercase().replace(" ", "")

    companion object {
        fun fromJson(json: JSONObject): UserStatus {
            return UserStatus(
                name = json.optString("name", "").trim(),
                isActive = json.optBoolean("status", true),
                suspendReason = json.optString("suspend_reason", "").trim(),
                profile = json.optString("profile", "").trim()
            )
        }
    }
}
