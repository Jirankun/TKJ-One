package com.tkjone.app.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Loader foto profil dari field `profile` di status.json.
 *
 * Isi field itu tidak selalu URL gambar langsung — contoh nyata di DB:
 *   "https://www.github.com/Jirankun.png"  → redirect 301 → 302 → avatar JPEG
 *   "https://github.com/Jirankun"          → halaman profil (BUKAN gambar)
 *
 * Dulu URL seperti itu diumpankan apa adanya ke Coil, dan ketika gagal tidak ada
 * fallback selain huruf inisial. Di sini URL dinormalisasi dulu (host, ekstensi,
 * halaman GitHub → .png), lalu byte-nya diambil lewat OkHttp (ikut redirect,
 * pakai User-Agent) dan disimpan di cache clean dengan nama hash.
 */
object ProfileImageLoader {
    private const val TAG = "ProfileImage"
    private const val AVATAR_MAX_DIMENSION = 512
    private const val CACHE_VERSION = "avatar-v1"

    private val githubPagePattern = Regex("^https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/?$")
    private val githubAvatarPattern = Regex("^https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]+)\\.png$", RegexOption.IGNORE_CASE)

    /**
     * Ubah nilai field `profile` menjadi URL gambar yang valid, atau null kalau tidak bisa.
     */
    fun normalizeAvatarUrl(raw: String?): String? {
        var url = raw?.trim()?.trim('"', '\'', ' ', '\n', '\t') ?: return null
        if (url.isEmpty()) return null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        url = url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

        // Halaman profil GitHub → endpoint avatar .png (mengikuti redirect ke avatars.githubusercontent.com)
        githubPagePattern.find(url)?.let { match ->
            return "https://github.com/${match.groupValues[1]}.png"
        }
        githubAvatarPattern.find(url)?.let { match ->
            return "https://github.com/${match.groupValues[1]}.png"
        }
        return url
    }

    /**
     * Unduh (atau ambil dari cache clean) lalu decode foto profil.
     * Return null kalau gagal — pemanggil tetap menampilkan huruf inisial.
     */
    suspend fun loadAvatar(context: Context, rawUrl: String?): Bitmap? {
        val url = normalizeAvatarUrl(rawUrl) ?: return null
        return try {
            val path = ThumbnailCacheService.cacheCleanImage(context, url, version = CACHE_VERSION)
                ?: return null
            ThumbnailCacheService.decodeBitmapOnIO(path, AVATAR_MAX_DIMENSION)
        } catch (error: Exception) {
            Log.w(TAG, "Gagal memuat avatar $url: ${error.message}")
            null
        }
    }
}
