package com.tkjone.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import android.util.Log
import com.tkjone.app.model.Photo
import kotlinx.coroutines.*
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ThumbnailCacheService {
    private const val TAG = "ThumbCache"
    private const val MAX_CACHE_SIZE = 192L * 1024 * 1024
    private const val STALE_THRESHOLD = 3 * 24 * 60 * 60 * 1000L // 3 days
    private const val MAX_SOURCE_FILE_SIZE = 64L * 1024 * 1024
    private const val CACHED_THUMBNAIL_MAX_DIMENSION = 1600
    private const val CACHE_FORMAT_VERSION = "thumb-v2"

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

    private val downloadSlots = Semaphore(3)
    private val pendingJobs = ConcurrentHashMap<String, Deferred<String?>>()
    @Volatile private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun prefetchThumbnails(context: Context, photos: List<Photo>) {
        val appContext = context.applicationContext
        scope.launch {
            photos.asSequence().filterNot { it.isVideo }.toList().chunked(3).forEach { batch ->
                coroutineScope {
                    batch.map { photo ->
                        async {
                            try {
                                cacheThumbnail(
                                    appContext,
                                    photo.url,
                                    version = photo.date,
                                    isVideo = photo.isVideo
                                )
                            } catch (error: Exception) {
                                Log.w(TAG, "Thumbnail prefetch failed for ${photo.name}: ${error.message}")
                                null
                            }
                        }
                    }.awaitAll()
                }
                trimCacheIfNeeded(getCacheDirectory(appContext))
            }
        }
    }

    fun isVideoUrl(url: String): Boolean {
        val clean = url.split("?")[0].split("#")[0].lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")
    }

    private fun generateCacheKey(url: String, version: String? = null): String {
        val key = if (version != null) "$CACHE_FORMAT_VERSION|$url|$version" else "$CACHE_FORMAT_VERSION|$url"
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
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return file.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(1, 1)
                }
                return file.absolutePath
            } catch (_: Exception) {}
        }
        file.delete()
        return null
    }

    /**
     * Get a thumbnail path. Returns cached path immediately, or downloads.
     * Returns null for videos (they don't need thumbnail cache).
     */
    suspend fun cacheThumbnail(
        context: Context,
        url: String,
        version: String? = null,
        isVideo: Boolean = false
    ): String? {
        // Check cache first (instant, no blocking)
        val cached = getCachedPathSync(context, url, version)
        if (cached != null) return cached

        // For video URLs: download file then extract first frame
        if (isVideo && isVideoUrl(url)) {
            return downloadSlots.withPermit { extractVideoFrame(context, url, version) }
        }

        val dir = getCacheDirectory(context)
        val key = generateCacheKey(url, version)
        val file = File(dir, key)

        // Dedup: if same URL is already downloading, wait for it
        pendingJobs[file.absolutePath]?.let { existing ->
            return try { existing.await() } catch (_: Exception) { null }
        }

        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                downloadSlots.withPermit { downloadDirect(url, file) }
                file.absolutePath
            } catch (e: Exception) {
                try { file.delete() } catch (_: Exception) {}
                Log.e(TAG, "Download failed: $url - ${e.message}")
                null
            }
        }
        val existing = pendingJobs.putIfAbsent(file.absolutePath, job)
        if (existing != null) {
            job.cancel()
            return try { existing.await() } catch (_: Exception) { null }
        }
        job.start()
        return try {
            job.await()
        } finally {
            pendingJobs.remove(file.absolutePath, job)
        }
    }

    /**
     * Download URL, write to file, validate it's a real image. Single attempt.
     */
    private suspend fun downloadDirect(url: String, file: File) = withContext(Dispatchers.IO) {
        val sourceFile = File(file.parentFile, "${file.name}.source.part")
        val thumbnailFile = File(file.parentFile, "${file.name}.thumb.part")
        sourceFile.delete()
        thumbnailFile.delete()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty response")
                if (body.contentLength() > MAX_SOURCE_FILE_SIZE) throw Exception("Source image exceeds limit")
                var totalBytes = 0L
                body.byteStream().use { input ->
                    sourceFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > MAX_SOURCE_FILE_SIZE) throw Exception("Source image exceeds limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (totalBytes == 0L) throw Exception("Empty response")
            }

            val thumbnail = decodeSampledBitmap(sourceFile.absolutePath, CACHED_THUMBNAIL_MAX_DIMENSION)
                ?: throw Exception("Unsupported image format")
            try {
                thumbnailFile.outputStream().use { output ->
                    if (!thumbnail.compress(Bitmap.CompressFormat.WEBP, 88, output)) {
                        throw Exception("Thumbnail encoding failed")
                    }
                }
            } finally {
                thumbnail.recycle()
            }
            if (thumbnailFile.length() == 0L) throw Exception("Empty thumbnail")
            if (file.exists() && !file.delete()) throw Exception("Unable to replace old thumbnail")
            if (!thumbnailFile.renameTo(file)) throw Exception("Unable to finalize thumbnail cache")
        } finally {
            sourceFile.delete()
            thumbnailFile.delete()
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
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(File(filePath))
                    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = maxOf(info.size.width, info.size.height).toFloat() / maxWidth
                        if (scale > 1f) {
                            decoder.setTargetSize(
                                (info.size.width / scale).toInt().coerceAtLeast(1),
                                (info.size.height / scale).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }
                return null
            }

            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxWidth) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(filePath, decodeOpts)?.let { return it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(File(filePath))
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = maxOf(info.size.width, info.size.height).toFloat() / maxWidth
                    if (scale > 1f) {
                        decoder.setTargetSize(
                            (info.size.width / scale).toInt().coerceAtLeast(1),
                            (info.size.height / scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }
            return null
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
