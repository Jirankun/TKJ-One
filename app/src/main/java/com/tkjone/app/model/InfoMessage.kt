package com.tkjone.app.model

import org.json.JSONObject

data class InfoMessage(
    val isActive: Boolean,
    val category: String,
    val message: String
) {
    companion object {
        fun fromJson(json: JSONObject): InfoMessage {
            return InfoMessage(
                isActive = json.optString("status", "").lowercase() == "true",
                category = json.optString("category", "").trim().lowercase(),
                message = json.optString("show", "").trim()
            )
        }
    }
}
