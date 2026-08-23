package com.tkjone.app.service

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class CachePriority { NORMAL, HIGH }

object ThumbnailCacheService {
    private var cacheDir: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val normalSemaphore = Semaphore(2)
    private val highSemaphore = Semaphore(1)
    private val pendingJobs = ConcurrentHashMap<String, Deferred<String>>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun getExtension(url: String): String {
        val clean = url.split("?")[0].split("#")[0].lowercase()
        val fileName = clean.substringAfterLast("/")
        val dotIndex = fileName.lastIndexOf(".")
        return if (dotIndex > 0 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1)
        } else "jpg"
    }

    fun isVideoUrl(url: String): Boolean {
        val clean = url.split("?")[0].split("#")[0].lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")
    }

    fun generateCacheKey(url: String, version: String? = null): String {
        val key = if (version != null) "$url|$version" else url
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val encoded = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val shortHash = if (encoded.length > 64) encoded.substring(0, 64) else encoded
        val ext = getExtension(url)
        return "$shortHash.$ext"
    }

    fun getCacheDirectory(context: Context): String {
        if (cacheDir != null) return cacheDir!!
        val extDir = context.getExternalFilesDir(null)
        cacheDir = if (extDir != null) {
            "${extDir.path}/tkjone_thumb_cache"
        } else {
            "${context.filesDir}/tkjone_thumb_cache"
        }
        File(cacheDir!!).mkdirs()
        return cacheDir!!
    }

    suspend fun getCachedPath(context: Context, url: String, version: String? = null): String? {
        val dir = getCacheDirectory(context)
        val key = generateCacheKey(url, version)
        val filePath = "$dir/$key"
        return if (File(filePath).exists()) filePath else null
    }

    suspend fun cacheThumbnail(
        context: Context,
        url: String,
        version: String? = null,
        isVideo: Boolean = false,
        priority: CachePriority = CachePriority.NORMAL
    ): String {
        val cached = getCachedPath(context, url, version)
        if (cached != null) return cached

        val dir = getCacheDirectory(context)
        val key = generateCacheKey(url, version)
        val filePath = "$dir/$key"

        // Dedup: if same URL is already being downloaded, wait for it
        pendingJobs[filePath]?.let { return it.await() }

        val job = scope.async {
            val semaphore = if (priority == CachePriority.HIGH) highSemaphore else normalSemaphore
            semaphore.withPermit {
                try {
                    downloadRawBytes(url, filePath)
                    filePath
                } catch (e: Exception) {
                    File(filePath).delete()
                    throw e
                }
            }
        }
        pendingJobs[filePath] = job
        return try {
            job.await()
        } finally {
            pendingJobs.remove(filePath)
        }
    }

    private suspend fun downloadRawBytes(url: String, filePath: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
            val bytes = response.body?.bytes() ?: throw Exception("Empty response")
            if (bytes.isEmpty()) throw Exception("Empty image data")
            File(filePath).writeBytes(bytes)
        }
    }

    suspend fun cleanStale(context: Context, maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L): Int {
        val dir = File(getCacheDirectory(context))
        if (!dir.exists()) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
                deleted++
            }
        }
        return deleted
    }

    suspend fun cleanUnused(context: Context, activeKeys: Set<String>): Int {
        val dir = File(getCacheDirectory(context))
        if (!dir.exists()) return 0
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && !activeKeys.contains(file.name)) {
                file.delete()
                deleted++
            }
        }
        return deleted
    }

    suspend fun getCacheSize(context: Context): Long {
        val dir = File(getCacheDirectory(context))
        if (!dir.exists()) return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    suspend fun clearCache(context: Context) {
        val dir = File(getCacheDirectory(context))
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Cancel all pending jobs and clear dedup map. Call on Activity destroy. */
    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }
}
