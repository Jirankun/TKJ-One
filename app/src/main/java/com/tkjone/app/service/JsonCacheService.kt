package com.tkjone.app.service

import android.content.Context
import java.io.File

/**
 * Cache JSON database (photos/info/status) di `cacheDir/database`.
 *
 * Tujuannya:
 *  - Saat app dibuka, JSON selalu diambil ulang dari server (FORCE_NETWORK) lalu cache ditimpa,
 *    jadi data yang berubah/berkurang di server ikut ter-update.
 *  - Kalau jaringan gagal, isi cache dipakai sebagai fallback supaya galeri tidak kosong.
 */
object JsonCacheService {
    private const val CACHE_FOLDER = "database"

    const val PHOTOS = "photos.json"
    const val INFO = "info.json"
    const val STATUS = "status.json"

    private fun dir(context: Context): File {
        val folder = File(context.cacheDir, CACHE_FOLDER)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun read(context: Context, name: String): String? = try {
        file(context, name).takeIf { it.isFile && it.length() > 0L }?.readText()
    } catch (_: Exception) {
        null
    }

    /** Tulis atomik (.part → rename) supaya tidak ada JSON setengah jadi. */
    fun write(context: Context, name: String, body: String) {
        try {
            val target = file(context, name)
            val part = File(target.parentFile, "${target.name}.part")
            part.writeText(body)
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            dir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
