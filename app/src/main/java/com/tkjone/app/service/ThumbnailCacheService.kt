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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cache gambar dua tingkat, semuanya di cacheDir aplikasi (bukan storage user):
 *
 *  • DROP  → thumbnail compressed (WEBP) untuk grid galeri. Cepat & hemat.
 *  • CLEAN → byte asli dari server untuk tampilan detail/story. Tetap "kualitas bersih",
 *            tapi disimpan lokal supaya buka foto kedua kali tidak mengunduh ulang.
 *
 * Nama file = hash SHA-256 dari (versi-format | url | tanggal-foto). Artinya:
 *  - URL yang berubah otomatis menghasilkan nama baru → data lama tidak pernah dipakai salah.
 *  - Naikkan formatVersion kalau algoritma kompresi berubah → cache lama otomatis ditinggalkan
 *    lalu dibersihkan oleh trim.
 *
 * Download (simpan ke galeri) sengaja TIDAK memakai cache ini; DownloadService selalu
 * mengambil file fresh dari server.
 */
object ThumbnailCacheService {
    private const val TAG = "ImageCache"

    private const val MAX_SOURCE_FILE_SIZE = 64L * 1024 * 1024
    private const val DROP_MAX_DIMENSION = 1600
    private const val DROP_QUALITY = 88
    private const val VIDEO_FRAME_QUALITY = 75
    private const val VIDEO_FRAME_MAX_BYTES = 10L * 1024 * 1024

    /**
     * Prefetch dibatasi ke halaman pertama saja. Grid tetap memuat thumbnail on-demand,
     * jadi mengunduh seluruh katalog (ratusan foto) saat app dibuka cuma membuang kuota.
     */
    private const val PREFETCH_LIMIT = 30

    /** Dua tingkat cache dengan batas ukuran dan masa simpan masing-masing. */
    private enum class Tier(val folder: String, val formatVersion: String, val maxBytes: Long, val staleMs: Long) {
        DROP("thumbnail", "drop-v4", 192L * 1024 * 1024, 3L * 24 * 60 * 60 * 1000),
        CLEAN("view", "clean-v2", 256L * 1024 * 1024, 7L * 24 * 60 * 60 * 1000)
    }

    /** Folder cache versi lama — dihapus saat startup supaya tidak menumpuk. */
    private val legacyFolders = listOf("drops", "clean", "tkjone_thumbs")

    private val directories = ConcurrentHashMap<Tier, File>()

    private val client = OkHttpClient.Builder()
        .dns(FastDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build()
            )
        }
        .build()

    private val downloadSlots = Semaphore(3)

    // Slot terpisah untuk cache view (clean): download foto yang sedang dibuka user tidak
    // perlu antre di belakang prefetch thumbnail galeri.
    private val cleanSlots = Semaphore(2)

    @Volatile private var prefetchPaused = false
    private val pendingJobs = ConcurrentHashMap<String, Deferred<String?>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ==================== PUBLIC API — DROP (grid) ====================

    fun prefetchThumbnails(context: Context, photos: List<Photo>) {
        val appContext = context.applicationContext
        scope.launch {
            photos.asSequence()
                .filterNot { it.isVideo }
                .take(PREFETCH_LIMIT)
                .toList()
                .chunked(3)
                .forEach { batch ->
                while (prefetchPaused) delay(350)
                coroutineScope {
                    batch.map { photo ->
                        async {
                            try {
                                cacheThumbnail(appContext, photo.url, version = photo.date)
                            } catch (error: Exception) {
                                Log.w(TAG, "Prefetch drop gagal untuk ${photo.name}: ${error.message}")
                                null
                            }
                        }
                    }.awaitAll()
                }
                trimCacheIfNeeded(appContext, Tier.DROP)
            }
        }
    }

    /** Pause prefetch thumbnail saat layar detail dibuka agar bandwidth fokus ke foto aktif. */
    fun setPrefetchPaused(paused: Boolean) {
        prefetchPaused = paused
    }

    fun isVideoUrl(url: String): Boolean {
        val clean = url.split("?")[0].split("#")[0].lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")
    }

    /** Path drop yang sudah ada di cache (tanpa I/O jaringan). */
    fun getCachedPathSync(context: Context, url: String, version: String? = null): String? =
        existingFile(dirFor(context, Tier.DROP), url, version, Tier.DROP)?.absolutePath

    /**
     * Drop image: kalau belum ada, unduh lalu encode WEBP [DROP_MAX_DIMENSION] px.
     * Return null untuk video (poster video ditangani terpisah) atau saat gagal.
     */
    suspend fun cacheThumbnail(
        context: Context,
        url: String,
        version: String? = null,
        isVideo: Boolean = false
    ): String? {
        getCachedPathSync(context, url, version)?.let { return it }

        if (isVideo && isVideoUrl(url)) {
            return downloadSlots.withPermit { extractVideoFrame(context, url, version) }
        }

        val dir = dirFor(context, Tier.DROP)
        val target = File(dir, cacheName(url, version, Tier.DROP, "webp"))
        return runDeduplicated(target) {
            writeDrop(url, target)
            target.absolutePath
        }
    }

    // ==================== PUBLIC API — CLEAN (detail & story) ====================

    /** Path gambar kualitas bersih (byte asli) bila sudah tersimpan. */
    fun getCachedCleanPathSync(context: Context, url: String, version: String? = null): String? =
        existingFile(dirFor(context, Tier.CLEAN), url, version, Tier.CLEAN)?.absolutePath

    /**
     * Simpan byte asli dari server ke cache clean (tanpa rekompresi).
     * Return null kalau gagal / ukurannya tidak wajar.
     */
    suspend fun cacheCleanImage(context: Context, url: String, version: String? = null): String? {
        getCachedCleanPathSync(context, url, version)?.let { return it }
        val dir = dirFor(context, Tier.CLEAN)
        val target = File(dir, cacheName(url, version, Tier.CLEAN, "img"))
        return runDeduplicated(target, cleanSlots) {
            downloadTo(url, target)
            trimCacheIfNeeded(context, Tier.CLEAN)
        }
    }

    /**
     * Poster video kualitas bersih (frame asli resolusi penuh, JPEG 92) untuk layar detail.
     * Disimpan di tier CLEAN, bukan tier thumbnail, supaya tidak ikut kompresi drop.
     */
    suspend fun cacheVideoPoster(context: Context, url: String, version: String? = null): String? {
        val key = "${url}#poster"
        getCachedCleanPathSync(context, key, version)?.let { return it }
        val dir = dirFor(context, Tier.CLEAN)
        val target = File(dir, cacheName(key, version, Tier.CLEAN, "img"))
        return runDeduplicated(target, cleanSlots) {
            // Retriever membaca frame langsung dari URL (range request), jadi tidak perlu
            // mengunduh seluruh video hanya untuk poster.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, emptyMap())
                val frame = retriever.getFrameAtTime(0)
                    ?: throw Exception("Frame video tidak tersedia")
                try {
                    val part = File(dir, "${target.name}.encoded.part")
                    part.outputStream().use { out ->
                        if (!frame.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                            throw Exception("Encode poster gagal")
                        }
                    }
                    if (part.length() == 0L) throw Exception("Poster kosong")
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) throw Exception("Gagal menyimpan poster")
                } finally {
                    frame.recycle()
                }
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
            trimCacheIfNeeded(context, Tier.CLEAN)
        }
    }

    // ==================== DECODING ====================

    /**
     * Decode dengan downsampling maksimal [maxWidth] px di sisi terpanjang.
     * Selalu aman dipanggil dari IO thread; tidak pernah melempar exception.
     */
    fun decodeSampledBitmap(filePath: String, maxWidth: Int = 400): Bitmap? {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return decodeWithImageDecoder(filePath, maxWidth)

            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxWidth) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(filePath, decodeOpts)?.let { return it }
            return decodeWithImageDecoder(filePath, maxWidth)
        } catch (_: OutOfMemoryError) {
            return try {
                BitmapFactory.decodeFile(
                    filePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = 8
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                )
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun decodeWithImageDecoder(filePath: String, maxWidth: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(File(filePath))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = maxOf(info.size.width, info.size.height).toFloat() / maxWidth
                if (scale > 1f) {
                    decoder.setTargetSize(
                        (info.size.width / scale).toInt().coerceAtLeast(1),
                        (info.size.height / scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun decodeBitmapOnIO(filePath: String, maxWidth: Int = 400): Bitmap? =
        withContext(Dispatchers.IO) { decodeSampledBitmap(filePath, maxWidth) }

    // ==================== CACHE MAINTENANCE ====================

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        Tier.values().forEach { tier ->
            dirFor(context, tier).listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun getCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        Tier.values().sumOf { tier ->
            dirFor(context, tier).listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    /** Dipanggil saat startup: buang file kedaluwarsa & rapikan ukuran kedua tier. */
    suspend fun trimCacheOnStartup(context: Context) = withContext(Dispatchers.IO) {
        // Migrasi: folder dari versi cache lama dibuang total.
        legacyFolders.forEach { legacy ->
            try {
                File(context.cacheDir, legacy).takeIf { it.isDirectory }?.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        Tier.values().forEach { tier ->
            try {
                trimCacheIfNeeded(context, tier)
            } catch (_: Exception) {
            }
        }
    }

    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        scope.coroutineContext.cancelChildren()
    }

    // ==================== INTERNAL ====================

    private fun dirFor(context: Context, tier: Tier): File {
        directories[tier]?.let { if (it.exists()) return it }
        val dir = File(context.cacheDir, tier.folder)
        if (!dir.exists()) dir.mkdirs()
        directories[tier] = dir
        return dir
    }

    /** Nama file = hash dari versi-format + url + versi foto. Ganti URL → nama ikut ganti. */
    private fun cacheName(url: String, version: String?, tier: Tier, extension: String): String {
        val raw = "${tier.formatVersion}|$url|${version.orEmpty()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${encoded.take(43)}.$extension"
    }

    private fun existingFile(dir: File, url: String, version: String?, tier: Tier): File? {
        val file = File(dir, cacheName(url, version, tier, if (tier == Tier.DROP) "webp" else "img"))
        if (!file.isFile || file.length() == 0L) return null
        if (tier == Tier.DROP) {
            // File drop rusak/terpotong dibuang supaya bisa diunduh ulang (self-healing).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete()
                return null
            }
        }
        return file
    }

    /** Satu URL hanya diunduh sekali walau diminta dari beberapa card sekaligus. */
    private suspend fun runDeduplicated(
        target: File,
        slots: Semaphore = downloadSlots,
        work: suspend () -> Unit
    ): String? {
        pendingJobs[target.absolutePath]?.let { existing ->
            return try {
                existing.await()
            } catch (_: Exception) {
                null
            }
        }
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit { work() }
                target.absolutePath
            } catch (e: Exception) {
                target.delete()
                Log.e(TAG, "Gagal membuat cache ${target.name}: ${e.message}")
                null
            }
        }
        val existing = pendingJobs.putIfAbsent(target.absolutePath, job)
        if (existing != null) {
            job.cancel()
            return try {
                existing.await()
            } catch (_: Exception) {
                null
            }
        }
        job.start()
        return try {
            job.await()
        } finally {
            pendingJobs.remove(target.absolutePath, job)
        }
    }

    /** Tulis byte asli dari server ke [target] secara atomik (.part → rename). */
    private fun downloadTo(url: String, target: File): Long {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty response")
                if (body.contentLength() > MAX_SOURCE_FILE_SIZE) throw Exception("File terlalu besar")
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_SOURCE_FILE_SIZE) throw Exception("File terlalu besar")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total == 0L) throw Exception("Empty response")
            }
            if (!part.renameTo(target)) throw Exception("Gagal menyimpan cache")
            return target.length()
        } finally {
            part.delete()
        }
    }

    /** Drop = unduh, perkecil ke [DROP_MAX_DIMENSION], encode WEBP. */
    private fun writeDrop(url: String, target: File) {
        val source = File(target.parentFile, "${target.name}.source.part")
        val encoded = File(target.parentFile, "${target.name}.encoded.part")
        source.delete()
        encoded.delete()
        try {
            downloadTo(url, source)

            val bitmap = decodeSampledBitmap(source.absolutePath, DROP_MAX_DIMENSION)
                ?: throw Exception("Format gambar tidak didukung")
            try {
                encoded.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.WEBP, DROP_QUALITY, output)) {
                        throw Exception("Encode thumbnail gagal")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            if (encoded.length() == 0L) throw Exception("Thumbnail kosong")
            if (target.exists() && !target.delete()) throw Exception("Gagal mengganti cache lama")
            if (!encoded.renameTo(target)) throw Exception("Gagal menyimpan thumbnail")
        } finally {
            source.delete()
            encoded.delete()
        }
    }

    private fun trimCacheIfNeeded(context: Context, tier: Tier) {
        try {
            val dir = dirFor(context, tier)
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                if (now - file.lastModified() > tier.staleMs) file.delete()
            }
            val remaining = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = remaining.sumOf { it.length() }
            for (file in remaining) {
                if (total <= tier.maxBytes) break
                total -= file.length()
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Ambil frame pertama video (poster) lalu simpan sebagai JPEG drop. */
    private suspend fun extractVideoFrame(context: Context, url: String, version: String?): String? =
        withContext(Dispatchers.IO) {
            val dir = dirFor(context, Tier.DROP)
            val target = File(dir, cacheName(url, version, Tier.DROP, "webp"))
            if (target.exists() && target.length() > 0L) return@withContext target.absolutePath
            val tempVideo = File(dir, "video-${System.currentTimeMillis()}.tmp")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    if (body.contentLength() > VIDEO_FRAME_MAX_BYTES) return@withContext null
                    val bytes = body.bytes()
                    if (bytes.size > VIDEO_FRAME_MAX_BYTES) return@withContext null
                    tempVideo.writeBytes(bytes)
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(tempVideo.absolutePath)
                    val frame = retriever.getFrameAtTime(0) ?: return@withContext null
                    try {
                        val part = File(dir, "${target.name}.part")
                        part.outputStream().use { out ->
                            frame.compress(Bitmap.CompressFormat.JPEG, VIDEO_FRAME_QUALITY, out)
                        }
                        if (!part.renameTo(target)) {
                            part.delete()
                            return@withContext null
                        }
                    } finally {
                        frame.recycle()
                    }
                    target.absolutePath
                } catch (_: Exception) {
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                tempVideo.delete()
            }
        }
}
