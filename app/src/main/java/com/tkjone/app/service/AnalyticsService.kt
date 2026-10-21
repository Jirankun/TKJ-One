package com.tkjone.app.service

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.tkjone.app.model.Photo

/**
 * Satu pintu untuk semua event Firebase Analytics.
 *
 * Sebelum ini project hanya memasang dependency firebase-analytics tanpa memanggil
 * satu event pun, sehingga GA4 cuma menerima event otomatis (first_open, session_start,
 * user_engagement). Dengan service ini, scroll galeri, buka foto/story, pencarian,
 * filter, tab, share, dan download ikut terkirim.
 *
 * Semua event dibungkus try/catch: analytics tidak boleh pernah menggagalkan UI.
 */
object AnalyticsService {

    private const val EVENT_SCROLL = "gallery_scroll"
    private const val EVENT_PHOTO_OPEN = "photo_open"
    private const val EVENT_SEARCH = "gallery_search"
    private const val EVENT_FILTER = "gallery_filter"
    private const val EVENT_TAB = "home_tab"
    private const val EVENT_STORY_OPEN = "story_open"
    private const val EVENT_STORY_CLOSE = "story_close"
    private const val EVENT_SHARE = "photo_share"
    private const val EVENT_DOWNLOAD = "photo_download"

    private const val PARAM_NAME = "photo_name"
    private const val PARAM_TYPE = "photo_type"
    private const val PARAM_SOURCE = "source"
    private const val PARAM_POSITION = "position"
    private const val PARAM_DEPTH = "depth_percent"
    private const val PARAM_QUERY = "query"
    private const val PARAM_RESULTS = "results"
    private const val PARAM_FILTER = "filter"
    private const val PARAM_TAB = "tab"
    private const val PARAM_SUCCESS = "success"
    private const val PARAM_BYTES = "bytes"
    private const val PARAM_DURATION = "duration_ms"

    @Volatile private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (analytics == null) {
            analytics = try {
                FirebaseAnalytics.getInstance(context.applicationContext)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun log(event: String, build: Bundle.() -> Unit) {
        val fa = analytics ?: return
        try {
            fa.logEvent(event, Bundle().apply(build))
        } catch (_: Exception) {
        }
    }

    /** Layar yang sedang dibuka (splash, login, home, detail, story, error). */
    fun screen(name: String) = log(FirebaseAnalytics.Event.SCREEN_VIEW) {
        putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
    }

    /** Kedalaman scroll galeri (%) — dipanggil sekali per milestone supaya tidak spam. */
    fun galleryScroll(depthPercent: Int, rendered: Int, total: Int) = log(EVENT_SCROLL) {
        putInt(PARAM_DEPTH, depthPercent)
        putInt("rendered", rendered)
        putInt("total", total)
    }

    fun photoOpened(photo: Photo, position: Int, source: String) = log(EVENT_PHOTO_OPEN) {
        putString(PARAM_NAME, photo.name)
        putString(PARAM_TYPE, photo.type)
        putString(PARAM_SOURCE, source)
        putInt(PARAM_POSITION, position)
    }

    fun search(query: String, results: Int) = log(EVENT_SEARCH) {
        putString(PARAM_QUERY, query.take(60))
        putInt(PARAM_RESULTS, results)
    }

    fun filterChanged(filter: String) = log(EVENT_FILTER) {
        putString(PARAM_FILTER, filter)
    }

    fun tabSelected(tab: String) = log(EVENT_TAB) {
        putString(PARAM_TAB, tab)
    }

    fun storyOpened() = log(EVENT_STORY_OPEN) {}

    fun storyClosed(durationMs: Long) = log(EVENT_STORY_CLOSE) {
        putLong(PARAM_DURATION, durationMs)
    }

    fun photoShared(photo: Photo) = log(EVENT_SHARE) {
        putString(PARAM_NAME, photo.name)
        putString(PARAM_TYPE, photo.type)
    }

    /** Dipanggil saat user menekan tombol download (sebelum proses berjalan). */
    fun downloadRequested(photo: Photo, source: String) = log(EVENT_DOWNLOAD) {
        putString(PARAM_NAME, photo.name)
        putString(PARAM_TYPE, photo.type)
        putString(PARAM_SOURCE, source)
        putString("stage", "requested")
    }

    /** Hasil akhir download — dipakai untuk mengukur tingkat keberhasilan. */
    fun downloadFinished(photo: Photo, success: Boolean, bytes: Long = 0L, durationMs: Long = 0L) =
        log(EVENT_DOWNLOAD) {
            putString(PARAM_NAME, photo.name)
            putString(PARAM_TYPE, photo.type)
            putString("stage", "finished")
            putBoolean(PARAM_SUCCESS, success)
            putLong(PARAM_BYTES, bytes)
            putLong(PARAM_DURATION, durationMs)
        }
}
