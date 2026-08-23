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
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ApiResult<T>(
    val data: T?,
    val error: String?,
    val isSuccess: Boolean
) {
    companion object {
        fun <T> success(data: T) = ApiResult(data = data, error = null, isSuccess = true)
        fun <T> failure(error: String) = ApiResult<T>(data = null, error = error, isSuccess = false)
    }
}

object ApiService {
    private val PHOTOS_JSON_URL get() = com.tkjone.app.config.AppConfig.PHOTOS_JSON_URL
    private val INFO_JSON_URL get() = com.tkjone.app.config.AppConfig.INFO_JSON_URL
    private val STATUS_JSON_URL get() = com.tkjone.app.config.AppConfig.STATUS_JSON_URL
    private const val CACHE_FILE_NAME = "tkjone_photos_cache.json"

    private lateinit var client: OkHttpClient

    fun initClient(context: Context) {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = okhttp3.Cache(cacheDir, 10L * 1024 * 1024) // 10MB HTTP cache
        client = OkHttpClient.Builder()
            .dns(FastDns)
            .cache(cache)
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
                val request = Request.Builder().url(PHOTOS_JSON_URL).build()
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.failure(
                        context.getString(R.string.download_failed_http, response.code)
                    )
                }
                val body = response.body?.string() ?: ""
                val photos = parsePhotos(body)
                    ?: return@withContext ApiResult.failure(context.getString(R.string.error_format_invalid, "foto"))
                cachePhotosJson(body, context)
                ApiResult.success(photos)
            } catch (e: Exception) {
                ApiResult.failure(context.getString(R.string.error_load_generic, "foto", e.message ?: ""))
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
            (0 until arr.length()).map { Photo.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== LOCAL PHOTO CACHE ====================

    private fun cachePhotosJson(jsonBody: String, context: Context) {
        try {
            File(context.filesDir, CACHE_FILE_NAME).writeText(jsonBody)
        } catch (_: Exception) {}
    }

    fun loadCachedPhotos(context: Context): List<Photo>? {
        return try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (file.exists()) parsePhotos(file.readText()) else null
        } catch (_: Exception) {
            null
        }
    }

    // ==================== INFO ====================

    suspend fun fetchInfo(context: Context): ApiResult<List<InfoMessage>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(INFO_JSON_URL).build()
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.failure(
                        context.getString(R.string.download_failed_http, response.code)
                    )
                }
                val body = response.body?.string() ?: ""
                val messages = parseInfo(body)
                    ?: return@withContext ApiResult.failure(context.getString(R.string.error_format_invalid, "info"))
                ApiResult.success(messages)
            } catch (e: Exception) {
                ApiResult.failure(context.getString(R.string.error_load_generic, "info", e.message ?: ""))
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

    suspend fun fetchUserStatus(): ApiResult<List<UserStatus>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(STATUS_JSON_URL).build()
                val response = getClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.failure("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: ""
                val users = parseUserStatus(body)
                    ?: return@withContext ApiResult.failure("Format data status tidak valid")
                ApiResult.success(users)
            } catch (e: Exception) {
                ApiResult.failure("Gagal memuat status: ${e.message}")
            }
        }
    }

    private fun parseUserStatus(body: String): List<UserStatus>? {
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("users") ?: return null
            (0 until arr.length()).map { UserStatus.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            null
        }
    }

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }
}
