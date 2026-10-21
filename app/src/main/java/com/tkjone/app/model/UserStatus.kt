package com.tkjone.app.model

import org.json.JSONObject

data class UserStatus(
    val name: String,
    val isActive: Boolean,
    val suspendReason: String,
    val profile: String
) {
    /** Nama untuk pencocokan: huruf kecil, tanpa spasi/tanda baca. */
    val normalizedName: String
        get() = normalizeName(name)

    companion object {
        fun normalizeName(value: String?): String =
            value.orEmpty().lowercase().replace(Regex("[^a-z0-9]"), "")

        fun fromJson(json: JSONObject): UserStatus {
            return UserStatus(
                name = json.firstString("name", "user", "username"),
                isActive = json.optBoolean("status", true),
                suspendReason = json.firstString("suspend_reason", "reason", "suspend"),
                // Beberapa entri DB memakai nama kunci berbeda untuk foto profil.
                profile = json.firstString("profile", "avatar", "photo", "image")
            )
        }

        private fun JSONObject.firstString(vararg keys: String): String {
            for (key in keys) {
                val value = optString(key, "").trim()
                if (value.isNotEmpty() && value != "null") return value
            }
            return ""
        }
    }
}
