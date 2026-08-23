package com.tkjone.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.cancelChildren

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class CachePriority { NORMAL, HIGH }

object ThumbnailCacheService {
    private const val TAG = "ThumbCache"
    private const val MAX_CACHE_SIZE = 80L * 1024 * 1024 // 80MB
    private const val STALE_THRESHOLD = 3 * 24 * 60 * 60 * 1000L // 3 days
    private const val MAX_FILE_SIZE = 8 * 1024 * 1024 // 8MB max per thumbnail

    private var cacheDir: File? = null

    private val client = OkHttpClient.Builder()
        .dns(FastDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build())
        }
        .build()

    // No semaphore — all thumbnails fire at once for instant display
    private val pendingJobs = ConcurrentHashMap<String, Deferred<String?>>()
    @Volatile private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    private fun generateCacheKey(url: String, version: String? = null): String {
        val key = if (version != null) "$url|$version" else url
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val encoded = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val shortHash = if (encoded.length > 43) encoded.substring(0, 43) else encoded
        return "$shortHash.dat"
    }

    private fun getCacheDirectory(context: Context): File {
        cacheDir?.let { if (it.exists()) return it }
        val dir = File(context.cacheDir, "tkjone_thumbs")
        if (!dir.exists()) dir.mkdirs()
        cacheDir = dir
        return dir
    }

    /**
     * Check if a thumbnail is already cached. Does NOT block.
     */
    fun getCachedPathSync(context: Context, url: String, version: String? = null): String? {
        val dir = getCacheDirectory(context)
        val key = generateCacheKey(url, version)
        val file = File(dir, key)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * Check if cached. Suspend-safe wrapper.
     */
    suspend fun getCachedPath(context: Context, url: String, version: String? = null): String? {
        return getCachedPathSync(context, url, version)
    }

    /**
     * Get a thumbnail path. Returns cached path immediately, or downloads.
     * Returns null for videos (they don't need thumbnail cache).
     */
    suspend fun cacheThumbnail(
        context: Context,
        url: String,
        version: String? = null,
        isVideo: Boolean = false,
        priority: CachePriority = CachePriority.NORMAL
    ): String? {
        // Check cache first (instant, no blocking)
        val cached = getCachedPathSync(context, url, version)
        if (cached != null) return cached

        // For video URLs: download file then extract first frame
        if (isVideo && isVideoUrl(url)) {
            return extractVideoFrame(context, url, version)
        }

        val dir = getCacheDirectory(context)
        val key = generateCacheKey(url, version)
        val file = File(dir, key)

        // Dedup: if same URL is already downloading, wait for it
        pendingJobs[file.absolutePath]?.let { existing ->
            return try { existing.await() } catch (_: Exception) { null }
        }

        val job = scope.async {
            try {
                downloadDirect(url, file)
                file.absolutePath
            } catch (e: Exception) {
                try { file.delete() } catch (_: Exception) {}
                Log.e(TAG, "Download failed: $url - ${e.message}")
                null
            }
        }
        pendingJobs[file.absolutePath] = job
        return try {
            job.await()
        } finally {
            pendingJobs.remove(file.absolutePath)
        }
    }

    /**
     * Download URL, write to file, validate it's a real image. Single attempt.
     */
    private suspend fun downloadDirect(url: String, file: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val body = response.body ?: throw Exception("Empty response")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw Exception("Empty data")
            if (bytes.size > MAX_FILE_SIZE) throw Exception("Too large")

            file.writeBytes(bytes)

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                file.delete()
                throw Exception("Invalid image")
            }
        }
    }

    /**
     * Decode a bitmap from file with downsampling. MUST be called from IO thread.
     * Returns null on any error — never crashes.
     */
    fun decodeSampledBitmap(filePath: String, maxWidth: Int = 400): Bitmap? {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxWidth * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(filePath, decodeOpts)
        } catch (_: OutOfMemoryError) {
            return try {
                BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply {
                    inSampleSize = 8
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } catch (_: Exception) { null }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Run bitmap decode on IO dispatcher. Safe for main thread callers.
     */
    suspend fun decodeBitmapOnIO(filePath: String, maxWidth: Int = 400): Bitmap? {
        return withContext(Dispatchers.IO) {
            decodeSampledBitmap(filePath, maxWidth)
        }
    }

    /**
     * Trim cache: remove oldest files when over limit, plus stale files.
     * Runs on IO thread.
     */
    private fun trimCacheIfNeeded(dir: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return

            // Remove stale files first
            val now = System.currentTimeMillis()
            for (f in files) {
                if (now - f.lastModified() > STALE_THRESHOLD) {
                    f.delete()
                }
            }

            // Then trim by size
            val remaining = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var totalSize = remaining.sumOf { it.length() }
            for (f in remaining) {
                if (totalSize <= MAX_CACHE_SIZE) break
                totalSize -= f.length()
                f.delete()
            }
        } catch (_: Exception) {}
    }

    suspend fun clearCache(context: Context) {
        withContext(Dispatchers.IO) {
            val dir = getCacheDirectory(context)
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun getCacheSize(context: Context): Long {
        return withContext(Dispatchers.IO) {
            val dir = getCacheDirectory(context)
            if (!dir.exists()) 0L else dir.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    /**
     * Call on app startup to clean stale/expired cache files.
     */
    suspend fun trimCacheOnStartup(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val dir = getCacheDirectory(context)
                trimCacheIfNeeded(dir)
            } catch (_: Exception) {}
        }
    }

    /**
     * Extract first frame from a video URL using MediaMetadataRetriever.
     * Caches the extracted frame as a regular image.
     */
    private suspend fun extractVideoFrame(context: Context, url: String, version: String?): String? {
        return withContext(Dispatchers.IO) {
            try {
                val dir = getCacheDirectory(context)
                val key = generateCacheKey(url, version)
                val frameFile = File(dir, key)

                // Check if frame already cached
                if (frameFile.exists() && frameFile.length() > 0) return@withContext frameFile.absolutePath

                // Download video to temp file first, then extract frame
                val tempFile = File(dir, "tmp_video_${System.currentTimeMillis()}.mp4")
                try {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val bytes = body.bytes()
                    if (bytes.size > 10 * 1024 * 1024) return@withContext null // skip huge videos
                    tempFile.writeBytes(bytes)

                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tempFile.absolutePath)
                        val frame = retriever.getFrameAtTime(0)
                        if (frame != null) {
                            frameFile.outputStream().use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 75, out) }
                            frame.recycle()
                            frameFile.absolutePath
                        } else null
                    } catch (_: Exception) {
                        null
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            } catch (_: Exception) { null }
        }
    }

    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        scope.coroutineContext.cancelChildren()
    }
}
