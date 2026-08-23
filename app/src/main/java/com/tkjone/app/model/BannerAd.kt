package com.tkjone.app.model

import org.json.JSONObject

data class BannerAd(
    val id: String,
    val name: String,
    val url: String,
    val link: String
) {
    companion object {
        const val DEFAULT_LINK = "https://tkj1.pages.dev"
        const val BASE_URL = "https://tkj1.pages.dev"

        fun fromJson(json: JSONObject): BannerAd {
            val rawPath = json.optString("name", "").trim()
            val cleanPath = rawPath.removePrefix("./")
            val fullUrl = "$BASE_URL/$cleanPath"
            val linkRaw = json.optString("link", "").trim()

            return BannerAd(
                id = json.optString("id", "0").trim(),
                name = rawPath,
                url = fullUrl,
                link = if (linkRaw.isNotEmpty()) linkRaw else DEFAULT_LINK
            )
        }
    }
}
