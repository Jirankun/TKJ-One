package com.tkjone.app.service

import android.content.Context
import com.tkjone.app.R
import com.tkjone.app.model.InfoMessage
import com.tkjone.app.model.Photo
import com.tkjone.app.model.UserStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiResult<T>(
    val data: T?,
    val error: String?,
    val isSuccess: Boolean,
    /** true kalau data berasal dari cache/database, bukan dari server. */
    val fromCache: Boolean = false
) {
    companion object {
        fun <T> success(data: T, fromCache: Boolean = false) =
            ApiResult(data = data, error = null, isSuccess = true, fromCache = fromCache)

        fun <T> failure(error: String) =
            ApiResult<T>(data = null, error = error, isSuccess = false)
    }
}

object ApiService {
    private val PHOTOS_JSON_URL get() = com.tkjone.app.config.AppConfig.PHOTOS_JSON_URL
    private val INFO_JSON_URL get() = com.tkjone.app.config.AppConfig.INFO_JSON_URL
    private val STATUS_JSON_URL get() = com.tkjone.app.config.AppConfig.STATUS_JSON_URL
    private const val LEGACY_CACHE_FILE_NAME = "tkjone_photos_cache.json"

    private lateinit var client: OkHttpClient

    fun initClient(context: Context) {
        context.deleteFile(LEGACY_CACHE_FILE_NAME)
        client = OkHttpClient.Builder()
            .dns(FastDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun getClient(): OkHttpClient {
        if (!::client.isInitialized) {
            client = OkHttpClient.Builder()
                .dns(FastDns)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }
        return client
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== PHOTOS ====================

    suspend fun fetchPhotos(context: Context): ApiResult<List<Photo>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = networkRequest(PHOTOS_JSON_URL)
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext cachedPhotos(context) ?: ApiResult.failure(
                        context.getString(R.string.download_failed_http, response.code)
                    )
                }
                val body = response.body?.string() ?: ""
                val photos = parsePhotos(body)
                    ?: return@withContext cachedPhotos(context)
                        ?: ApiResult.failure(context.getString(R.string.error_format_invalid, "foto"))
                JsonCacheService.write(context, JsonCacheService.PHOTOS, body)
                ApiResult.success(photos)
            } catch (e: Exception) {
                cachedPhotos(context)
                    ?: ApiResult.failure(context.getString(R.string.error_load_generic, "foto", e.message ?: ""))
            }
        }
    }

    /**
     * Parse photos from new JSON format — flat array:
     * [{"name":"1","type":"video/photo","url":"https://...","date":"2024-01-01"}, ...]
     */
    private fun parsePhotos(body: String): List<Photo>? {
        return try {
            val trimmed = body.trim()
            // Support both flat array [...] and wrapped {"photos":[...]}
            val arr: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val json = JSONObject(trimmed)
                json.optJSONArray("photos") ?: json.optJSONArray("data") ?: return null
            }
            (0 until arr.length())
                .map { Photo.fromJson(arr.getJSONObject(it)) }
                .sortedWith(photoOrder)
        } catch (_: Exception) {
            null
        }
    }

    /** Urutan sama seperti web: tanggal terbaru di atas, lalu nama (numerik). */
    private val photoOrder = Comparator<Photo> { a, b ->
        val byDate = b.date.compareTo(a.date)
        if (byDate != 0) byDate else {
            val na = a.name.toDoubleOrNull()
            val nb = b.name.toDoubleOrNull()
            when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> a.name.compareTo(b.name, ignoreCase = true)
            }
        }
    }

    // ==================== INFO ====================

    suspend fun fetchInfo(context: Context): ApiResult<List<InfoMessage>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = networkRequest(INFO_JSON_URL)
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext cachedInfo(context) ?: ApiResult.failure(
                        context.getString(R.string.download_failed_http, response.code)
                    )
                }
                val body = response.body?.string() ?: ""
                val messages = parseInfo(body)
                    ?: return@withContext cachedInfo(context)
                        ?: ApiResult.failure(context.getString(R.string.error_format_invalid, "info"))
                JsonCacheService.write(context, JsonCacheService.INFO, body)
                ApiResult.success(messages)
            } catch (e: Exception) {
                cachedInfo(context)
                    ?: ApiResult.failure(context.getString(R.string.error_load_generic, "info", e.message ?: ""))
            }
        }
    }

    private fun parseInfo(body: String): List<InfoMessage>? {
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("info") ?: return null
            (0 until arr.length()).map { InfoMessage.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== USER STATUS ====================

    suspend fun fetchUserStatus(context: Context? = null): ApiResult<List<UserStatus>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = networkRequest(STATUS_JSON_URL)
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext cachedStatus(context) ?: ApiResult.failure("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: ""
                val users = parseUserStatus(body)
                    ?: return@withContext cachedStatus(context)
                        ?: ApiResult.failure("Format data status tidak valid")
                context?.let { JsonCacheService.write(it, JsonCacheService.STATUS, body) }
                ApiResult.success(users)
            } catch (e: Exception) {
                cachedStatus(context) ?: ApiResult.failure("Gagal memuat status: ${e.message}")
            }
        }
    }

    // ==================== CACHE DATABASE (fallback offline) ====================

    private fun cachedPhotos(context: Context): ApiResult<List<Photo>>? =
        JsonCacheService.read(context, JsonCacheService.PHOTOS)
            ?.let { parsePhotos(it) }
            ?.let { ApiResult.success(it, fromCache = true) }

    private fun cachedInfo(context: Context): ApiResult<List<InfoMessage>>? =
        JsonCacheService.read(context, JsonCacheService.INFO)
            ?.let { parseInfo(it) }
            ?.let { ApiResult.success(it, fromCache = true) }

    private fun cachedStatus(context: Context?): ApiResult<List<UserStatus>>? {
        val ctx = context ?: return null
        return JsonCacheService.read(ctx, JsonCacheService.STATUS)
            ?.let { parseUserStatus(it) }
            ?.let { ApiResult.success(it, fromCache = true) }
    }

    /**
     * Ambil URL foto profil milik [userName] dari status.json.
     * Nilainya dinormalisasi (halaman GitHub → .png, http → https) supaya siap dimuat.
     */
    suspend fun fetchProfileImageUrl(userName: String): String? {
        val target = UserStatus.normalizeName(userName)
        if (target.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = networkRequest("$STATUS_JSON_URL?t=${System.currentTimeMillis()}")
                getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseUserStatus(response.body?.string().orEmpty())
                        ?.firstOrNull { it.normalizedName == target }
                        ?.profile
                        ?.let { ProfileImageLoader.normalizeAvatarUrl(it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** status.json berbentuk {"users": [...]}; array polos juga diterima. */
    private fun parseUserStatus(body: String): List<UserStatus>? {
        return try {
            val trimmed = body.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("users")
                    ?: JSONObject(trimmed).optJSONArray("data")
                    ?: return null
            }
            (0 until arr.length()).map { UserStatus.fromJson(arr.getJSONObject(it)) }
                .filter { it.name.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun networkRequest(url: String): Request = Request.Builder()
        .url(url)
        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
        .header("Cache-Control", "no-cache")
        .build()

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }
}
