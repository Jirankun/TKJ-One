package com.tkjone.app.model

import org.json.JSONObject

data class Photo(
    val name: String,
    val url: String,
    val type: String,   // "video" or "photo"
    val date: String
) {
    val isVideo: Boolean get() = type.lowercase() == "video"

    companion object {
        fun fromJson(json: JSONObject): Photo {
            return Photo(
                name = json.optString("name", "unknown").trim(),
                url = json.optString("url", "").trim(),
                type = json.optString("type", "photo").trim().lowercase(),
                date = json.optString("date", "2024-01-01").trim()
            )
        }
    }
}
