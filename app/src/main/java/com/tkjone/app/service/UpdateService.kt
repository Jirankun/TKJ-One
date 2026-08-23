package com.tkjone.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tkjone.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tagVersion: String,
    val title: String,
    val description: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
) {
    val formattedSize: String
        get() {
            if (apkSizeBytes < 1024 * 1024) {
                return String.format("%.1f KB", apkSizeBytes / 1024.0)
            }
            return String.format("%.1f MB", apkSizeBytes / (1024.0 * 1024))
        }
}

data class UpdateCheckResult(
    val updateInfo: UpdateInfo? = null,
    val error: String? = null
) {
    val hasUpdate: Boolean get() = updateInfo != null
}

object UpdateService {
    private const val GITHUB_API = "https://api.github.com/repos/Jirankun/TKJ-One/releases/latest"
    private const val GITHUB_RELEASES = "https://github.com/Jirankun/TKJ-One/releases/tag"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersionStr = getCurrentVersion(context)
                val currentVersion = parseVersion(currentVersionStr)

                val request = Request.Builder()
                    .url(GITHUB_API)
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                if (response.code != 200) {
                    return@withContext UpdateCheckResult(
                        error = context.getString(R.string.update_check_failed_http, response.code)
                    )
                }

                val body = response.body?.string() ?: ""
                val data = JSONObject(body)
                val tagName = data.optString("tag_name", "")
                val cleanTag = tagName.removePrefix("v")

                val remoteVersion = parseVersion(cleanTag)
                if (compareVersions(remoteVersion, currentVersion) <= 0) {
                    return@withContext UpdateCheckResult()
                }

                val assets = data.optJSONArray("assets")
                if (assets == null || assets.length() == 0) {
                    return@withContext UpdateCheckResult(error = context.getString(R.string.update_no_apk))
                }

                val apkAsset = assets.getJSONObject(0)
                val apkUrl = apkAsset.optString("browser_download_url", "")
                val apkSize = apkAsset.optLong("size", 0)
                val releaseName = data.optString("name", "TKJ-OnE_$tagName.apk")
                val description = data.optString("body", context.getString(R.string.update_no_description)).trim()
                val htmlUrl = data.optString("html_url", "$GITHUB_RELEASES/$tagName")

                UpdateCheckResult(
                    updateInfo = UpdateInfo(
                        tagVersion = tagName,
                        title = releaseName,
                        description = description,
                        apkUrl = apkUrl,
                        apkSizeBytes = apkSize,
                        htmlUrl = htmlUrl
                    )
                )
            } catch (e: Exception) {
                UpdateCheckResult(error = context.getString(R.string.update_check_failed, e.message ?: ""))
            }
        }
    }

    private fun parseVersion(versionStr: String): List<Int> {
        return versionStr.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val va = a.getOrElse(i) { 0 }
            val vb = b.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: ((progress: Float) -> Unit)? = null
    ): String {
        return withContext(Dispatchers.IO) {
            val downloadDir = File(context.filesDir, "downloads")
            if (downloadDir.exists()) {
                downloadDir.listFiles()?.forEach { it.delete() }
            } else {
                downloadDir.mkdirs()
            }

            val savePath = File(downloadDir, fileName)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val body = response.body ?: throw Exception("Empty response")
            val contentLength = body.contentLength()
            var bytesRead = 0L

            body.byteStream().use { input ->
                savePath.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            onProgress?.invoke(bytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }

            savePath.absolutePath
        }
    }

    fun installApk(context: Context, filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return context.getString(R.string.update_apk_not_found)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            context.getString(R.string.update_install_starting)
        } catch (e: Exception) {
            context.getString(R.string.update_install_failed, e.message ?: "")
        }
    }
}
