package com.tkjone.app.service

import android.content.Context
import android.os.Environment
import com.tkjone.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class DownloadResult(
    val success: Boolean,
    val message: String,
    val filePath: String? = null
)

object DownloadService {
    private val client = OkHttpClient.Builder()
        .dns(FastDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun downloadToDcim(
        context: Context,
        url: String,
        fileName: String,
        onProgress: ((received: Long, total: Long) -> Unit)? = null
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                val tempDir = context.cacheDir
                val tempFile = File(tempDir, fileName)

                val request = Request.Builder()
                    .url(url)
                    .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext DownloadResult(
                        false,
                        context.getString(R.string.download_failed_http, response.code)
                    )
                }

                val body = response.body
                    ?: return@withContext DownloadResult(false, context.getString(R.string.download_failed_response))
                val contentLength = body.contentLength()
                var bytesRead = 0L

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress?.invoke(bytesRead, contentLength)
                        }
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    return@withContext DownloadResult(false, context.getString(R.string.download_failed_empty))
                }

                // Try to save to DCIM
                try {
                    val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    val saveDir = File(dcimDir, "TKJ Photos")
                    if (!saveDir.exists()) saveDir.mkdirs()

                    val saveFile = File(saveDir, fileName)
                    tempFile.copyTo(saveFile, overwrite = true)
                    tempFile.delete()
                    return@withContext DownloadResult(
                        success = true,
                        message = context.getString(R.string.download_saved_dcim),
                        filePath = saveFile.absolutePath
                    )
                } catch (_: Exception) {
                    // Fallback: save to app documents
                    try {
                        val saveDir = File(context.filesDir, "TKJOnE_Downloads")
                        if (!saveDir.exists()) saveDir.mkdirs()
                        val saveFile = File(saveDir, fileName)
                        tempFile.copyTo(saveFile, overwrite = true)
                        tempFile.delete()
                        return@withContext DownloadResult(
                            success = true,
                            message = context.getString(R.string.download_saved_app),
                            filePath = saveFile.absolutePath
                        )
                    } catch (e2: Exception) {
                        return@withContext DownloadResult(
                            false,
                            context.getString(R.string.download_failed_save, e2.message ?: "")
                        )
                    }
                }
            } catch (e: Exception) {
                DownloadResult(false, context.getString(R.string.download_failed_generic, e.message ?: ""))
            }
        }
    }

    fun getFileExtension(url: String): String {
        val cleanUrl = url.split("?")[0].split("#")[0]
        val fileName = cleanUrl.substringAfterLast("/")
        val parts = fileName.split(".")
        if (parts.size > 1) {
            return when (parts.last().lowercase()) {
                "mp4", "webm", "mov" -> "mp4"
                "jpg", "jpeg" -> "jpg"
                "png" -> "png"
                "gif" -> "gif"
                else -> "bin"
            }
        }
        return "bin"
    }

    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim()
            .replace(Regex("_+"), "_")
    }

}
