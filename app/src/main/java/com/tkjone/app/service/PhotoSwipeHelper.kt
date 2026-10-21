package com.tkjone.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.tkjone.app.R
import com.tkjone.app.model.Photo
import com.tkjone.app.ui.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ONE unified media preview screen — photo and video share the same view.
 * Swipe left/right switches media in-place; when the current media is a video,
 * a mini player card (prev / play-pause / next ... seekbar ... elapsed/total)
 * appears right below the media, above the info footer card.
 */
class PhotoSwipeHelper(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val onBack: () -> Unit,
    private val onDownload: (Photo) -> Unit,
    private val onShare: (Photo) -> Unit,
    private val getCachedBitmap: (Photo) -> Bitmap?,
    /** Placeholder cepat: thumbnail drop (di memori / cache drop). */
    private val loadPlaceholder: suspend (Photo) -> Bitmap?,
    /** Gambar kualitas bersih (byte asli server) untuk tampilan utama. */
    private val loadFullImage: suspend (Photo) -> Bitmap?
) {
    private var currentView: View? = null
    private var currentPhoto: Photo? = null
    private var currentIndex = 0
    private var photos: List<Photo> = emptyList()
    private var isAnimating = false
    private var destroyed = false

    // Video state
    private var videoView: VideoView? = null
    private var pendingVideoUrl: String? = null
    private var videoPrepared = false
    private var shouldAutoPlay = true
    private var handler: Handler? = null
    private var progressRunnable: Runnable? = null
    private val helperScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var imageJob: Job? = null
    private var loadingBar: View? = null

    // Status pemutaran
    private var userPaused = false      // user menekan pause manual
    private var resumeOnReturn = false  // video sedang play saat app ditinggalkan
    private var appInBackground = false // activity sedang tidak terlihat

    // Drag state
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private val dragThreshold = 80f

    fun show(photo: Photo, index: Int, photoList: List<Photo>) {
        if (destroyed) return
        currentPhoto = photo
        currentIndex = index
        photos = photoList

        val v = currentView
        if (v != null && v.parent != null) {
            // View exists and is attached — just update content in-place
            updateContent(v, photo, index)
            switchMedia(v, photo)
        } else {
            stopVideo()
            val newView = createView(photo, index)
            currentView = newView
            rootContainer.removeAllViews()
            rootContainer.addView(newView)
            newView.alpha = 0f; newView.translationX = 60f
            newView.animate().alpha(1f).translationX(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun createView(photo: Photo, index: Int): View {
        val isDark = true
        val inflater = LayoutInflater.from(activity)
        val v = inflater.inflate(R.layout.screen_photo_detail, null) as ViewGroup
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        v.findViewById<LinearLayout>(R.id.detail_nav_bar).setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        v.findViewById<TextView>(R.id.detail_title).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<ImageView>(R.id.detail_back_btn).setOnClickListener { onBack() }
        v.findViewById<ImageView>(R.id.detail_download_btn).setOnClickListener { currentPhoto?.let { p -> onDownload(p) } }
        v.findViewById<ImageView>(R.id.detail_share_btn).setOnClickListener { currentPhoto?.let { p -> onShare(p) } }

        v.findViewById<LinearLayout>(R.id.detail_footer).setBackgroundColor(ThemeHelper.getCardColor(isDark))
        v.findViewById<TextView>(R.id.detail_photo_name).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_date).setTextColor(ThemeHelper.getSecLabelColor(isDark))

        setupMiniPlayer(v)
        loadingBar = v.findViewById(R.id.detail_loading_bar)

        val imageFrame = v.findViewById<ImageView>(R.id.detail_image).parent as FrameLayout
        setupDragSwipe(imageFrame)

        updateContent(v, photo, index)
        switchMedia(v, photo)
        return v
    }

    private fun updateContent(v: View, photo: Photo, index: Int) {
        try {
            v.findViewById<TextView>(R.id.detail_title).text = activity.getString(R.string.photo_name_format, photo.name)
            v.findViewById<TextView>(R.id.detail_counter).text = activity.getString(R.string.photo_counter_format, index + 1, photos.size)
            v.findViewById<TextView>(R.id.detail_photo_name).text = activity.getString(R.string.photo_name_format, photo.name)
            v.findViewById<TextView>(R.id.detail_date).text = photo.date

            val uploaderRow = v.findViewById<LinearLayout>(R.id.detail_uploader_row)
            val uploaderTv = v.findViewById<TextView>(R.id.detail_uploader)
            uploaderTv.setTextColor(ThemeHelper.ACCENT_BLUE)
            if (photo.uploader.isNotEmpty()) {
                uploaderRow.visibility = View.VISIBLE
                uploaderTv.text = activity.getString(R.string.photo_uploader_format, photo.uploader)
            } else {
                uploaderRow.visibility = View.GONE
            }

            val badge = v.findViewById<TextView>(R.id.detail_media_badge)
            val (badgeText, badgeColor) = if (photo.isVideo) activity.getString(R.string.type_video) to ThemeHelper.ACCENT_YELLOW else activity.getString(R.string.type_photo) to ThemeHelper.ACCENT_BLUE
            badge.text = badgeText; badge.setTextColor(badgeColor)
            badge.background = GradientDrawable().apply {
                setColor((badgeColor and 0x1FFFFFFF) or 0x15000000)
                cornerRadius = activity.resources.displayMetrics.density * 12f
                setStroke(1, (badgeColor and 0x3FFFFFFF) or 0x30000000)
            }
        } catch (_: Exception) {}
    }

    /** Show photo (ImageView) or video (VideoView) + mini player card, in the SAME screen. */
    private fun switchMedia(v: View, photo: Photo) {
        val imgView = v.findViewById<ImageView>(R.id.detail_image)
        val vv = v.findViewById<VideoView>(R.id.detail_video_view)
        val loading = v.findViewById<View>(R.id.detail_video_loading)
        val playerCard = v.findViewById<View>(R.id.detail_player_card)

        // Video sebelumnya dihentikan lebih dulu sebelum media baru dimuat
        stopVideo()
        showLoadingBar()

        if (photo.isVideo) {
            // Poster: thumbnail video ditampilkan di atas VideoView selagi video loading,
            // jadi user tetap melihat gambar (bukan area hitam) dan drag swipe-nya terlihat.
            imgView.tag = photo.url
            showImage(photo, imgView)
            imgView.visibility = View.VISIBLE
            // Ensure video view is centered and fully opaque
            centerVideoView(vv)
            vv.translationX = 0f
            vv.alpha = 1f
            vv.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            playerCard.visibility = View.VISIBLE
            shouldAutoPlay = true
            userPaused = false
            resumeOnReturn = false
            startVideo(v, photo.url)
        } else {
            vv.visibility = View.GONE
            loading.visibility = View.GONE
            playerCard.visibility = View.GONE
            imgView.visibility = View.VISIBLE
            loadImage(v, photo)
        }
    }

    private fun loadImage(v: View, photo: Photo) {
        try {
            val imgView = v.findViewById<ImageView>(R.id.detail_image)
            imgView.tag = photo.url
            showImage(photo, imgView)
        } catch (_: Exception) {}
    }

    /**
     * Tampilan utama foto memakai kualitas bersih (byte asli dari server).
     *
     * Urutannya:
     * 1. Bitmap drop yang sudah di memori → tampil instan, tidak ada layar kosong.
     * 2. Drop dari cache disk → versi lebih tajam sebagai placeholder.
     * 3. Gambar clean (cache clean / unduh langsung) → menggantikan placeholder.
     */
    private fun showImage(photo: Photo, imgView: ImageView) {
        try {
            imgView.scaleType = ImageView.ScaleType.FIT_CENTER
            val memBmp = getCachedBitmap(photo)
            if (memBmp != null && !memBmp.isRecycled) {
                imgView.setImageBitmap(memBmp)
                imgView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                imgView.setImageBitmap(null)
                imgView.setBackgroundColor(0xFF1C1C1E.toInt())
            }
            showLoadingBar()
            imageJob?.cancel()
            imageJob = helperScope.launch {
                if (!isStillShowing(photo, imgView)) return@launch
                val placeholder = try { loadPlaceholder(photo) } catch (_: Exception) { null }
                if (isStillShowing(photo, imgView) && placeholder != null && !placeholder.isRecycled) {
                    imgView.setImageBitmap(placeholder)
                    imgView.setBackgroundColor(Color.TRANSPARENT)
                }
                val full = try { loadFullImage(photo) } catch (_: Exception) { null }
                if (isStillShowing(photo, imgView) && full != null && !full.isRecycled) {
                    imgView.setImageBitmap(full)
                    imgView.setBackgroundColor(Color.TRANSPARENT)
                }
                // Video: loading bar baru berhenti saat video siap diputar (startPreparedVideo).
                if (!photo.isVideo) hideLoadingBar()
            }
        } catch (_: Exception) {}
    }

    /**
     * Cek apakah job gambar masih relevan.
     *
     * JANGAN bergantung pada currentView: pada pembukaan pertama, createView() menjalankan
     * showImage() sebelum currentView sempat diisi, sehingga pengecekan lama langsung gagal
     * dan gambar clean tidak pernah dimuat (foto tampak blur/kosong sampai di-swipe ulang).
     */
    private fun isStillShowing(photo: Photo, imgView: ImageView): Boolean =
        !destroyed && imgView.tag == photo.url && currentPhoto?.url == photo.url

    private fun showLoadingBar() {
        try {
            loadingBar?.visibility = View.VISIBLE
        } catch (_: Exception) {}
    }

    private fun hideLoadingBar() {
        try {
            loadingBar?.visibility = View.GONE
        } catch (_: Exception) {}
    }

    // ─── VIDEO PLAYBACK ───

    private fun startVideo(v: View, url: String) {
        val vv = v.findViewById<VideoView>(R.id.detail_video_view)
        val loading = v.findViewById<View>(R.id.detail_video_loading)
        videoView = vv
        videoPrepared = false
        pendingVideoUrl = url
        vv.setOnPreparedListener(null)
        vv.setOnErrorListener(null)
        vv.setOnCompletionListener(null)
        try { vv.stopPlayback() } catch (_: Exception) {}
        // Ensure video view is centered and fully opaque when starting
        centerVideoView(vv)
        vv.translationX = 0f
        vv.alpha = 1f
        vv.setVideoURI(Uri.parse(url))
        vv.setOnPreparedListener { mp -> startPreparedVideo(v, mp) }
        vv.setOnErrorListener { _, what, extra ->
            if (pendingVideoUrl == url && !destroyed) {
                pendingVideoUrl = null
                loading.visibility = View.GONE
                hideLoadingBar()
                showRetryOverlay(v, url)
            }
            true
        }
        vv.setOnCompletionListener {
            // loop by default; user can toggle loop with long-press on play button
            try { vv.seekTo(0); vv.start(); updatePlayIcon(v, true) } catch (_: Exception) {}
        }
    }

    private fun startPreparedVideo(v: View, mp: MediaPlayer) {
        if (destroyed || currentView !== v) {
            try { mp.release() } catch (_: Exception) {}
            return
        }
        videoPrepared = true
        hideLoadingBar()
        v.findViewById<View>(R.id.detail_video_loading).visibility = View.GONE
        // Video sudah siap → poster thumbnail disembunyikan agar video terlihat
        v.findViewById<ImageView>(R.id.detail_image).visibility = View.GONE
        mp.isLooping = looping
        val ppBtn = v.findViewById<ImageView>(R.id.detail_play_pause_btn)
        ppBtn.isSelected = false
        if (shouldAutoPlay && !userPaused) {
            if (appInBackground) {
                // App sedang di background (video baru siap belakangan) — jangan mulai
                // memutar otomatis. Tandai agar dilanjutkan begitu user kembali.
                resumeOnReturn = true
                updatePlayIcon(v, false)
            } else {
                try { mp.start() } catch (_: Exception) {}
                updatePlayIcon(v, true)
                startProgressUpdates(v)
            }
        } else {
            // User memang sedang pause — jangan diputar otomatis
            updatePlayIcon(v, false)
        }
        val seek = v.findViewById<SeekBar>(R.id.detail_seek)
        seek.max = if (mp.duration > 0) mp.duration else 0
        updateTimeText(v)
    }

    /**
     * VideoView mengukur dirinya mengikuti aspect ratio video, sehingga ukurannya bisa lebih
     * kecil dari frame. FrameLayout secara default menaruh child di Gravity.TOP|START (kiri-atas),
     * akibatnya video terlihat miring ke kiri dengan area hitam di sisi kanan.
     * Memaksa gravity = CENTER membuat video selalu rata tengah di frame.
     */
    private fun centerVideoView(vv: VideoView) {
        try {
            val lp = vv.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.gravity != android.view.Gravity.CENTER) {
                lp.gravity = android.view.Gravity.CENTER
                vv.layoutParams = lp
            }
        } catch (_: Exception) {}
    }

    private fun showRetryOverlay(v: View, url: String) {
        val frame = v.findViewById<FrameLayout>(R.id.detail_media_frame)
        val overlay = FrameLayout(activity).apply {
            tag = "video_retry"
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener {
                removeRetryOverlay(v)
                v.findViewById<View>(R.id.detail_video_loading).visibility = View.VISIBLE
                startVideo(v, url)
            }
        }
        val tv = TextView(activity).apply {
            text = activity.getString(R.string.toast_video_failed) + "\n" +
                activity.getString(R.string.error_retry)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
        }
        overlay.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER })
        frame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun removeRetryOverlay(v: View) {
        val frame = v.findViewById<FrameLayout>(R.id.detail_media_frame)
        val old = frame.findViewWithTag<View>("video_retry")
        if (old != null) frame.removeView(old)
    }

    private fun stopVideo() {
        pendingVideoUrl = null
        videoPrepared = false
        resumeOnReturn = false
        stopProgressUpdates()
        val vv = videoView
        videoView = null
        if (vv != null) {
            vv.setOnPreparedListener(null)
            vv.setOnErrorListener(null)
            vv.setOnCompletionListener(null)
            try { vv.stopPlayback() } catch (_: Exception) {}
        }
    }

    // ─── MINI PLAYER CARD ───

    private var looping = true

    private fun setupMiniPlayer(v: View) {
        val prevBtn = v.findViewById<ImageView>(R.id.detail_prev_btn)
        val nextBtn = v.findViewById<ImageView>(R.id.detail_next_btn)
        val ppBtn = v.findViewById<ImageView>(R.id.detail_play_pause_btn)
        val seek = v.findViewById<SeekBar>(R.id.detail_seek)

        prevBtn.setOnClickListener { navigateRelative(-1) }
        nextBtn.setOnClickListener { navigateRelative(1) }

        ppBtn.setOnClickListener { togglePlayPause(v) }
        ppBtn.setOnLongClickListener {
            looping = !looping
            try { videoView?.setOnPreparedListener { mediaPlayer -> mediaPlayer.isLooping = looping } } catch (_: Exception) {}
            android.widget.Toast.makeText(
                activity,
                if (looping) "Loop: ON" else "Loop: OFF",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && videoPrepared) {
                    try { videoView?.seekTo(progress) } catch (_: Exception) {}
                    updateTimeText(v)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun togglePlayPause(v: View) {
        val vv = videoView ?: return
        if (!videoPrepared) return
        try {
            if (vv.isPlaying) {
                vv.pause()
                userPaused = true
                updatePlayIcon(v, false)
                stopProgressUpdates()
            } else {
                // Melanjutkan dari posisi pause — bukan dari awal
                vv.start()
                userPaused = false
                updatePlayIcon(v, true)
                startProgressUpdates(v)
            }
        } catch (_: Exception) {}
    }

    private fun updatePlayIcon(v: View, playing: Boolean) {
        val ppBtn = v.findViewById<ImageView>(R.id.detail_play_pause_btn)
        ppBtn.setImageResource(if (playing) R.drawable.pause_video else R.drawable.play)
    }

    private fun startProgressUpdates(v: View) {
        stopProgressUpdates()
        if (handler == null) handler = Handler(Looper.getMainLooper())
        val h = handler ?: return
        val r = object : Runnable {
            override fun run() {
                if (destroyed || currentView !== v) return
                val vv = videoView ?: return
                if (videoPrepared) {
                    try {
                        val pos = vv.currentPosition
                        val dur = vv.duration
                        val seek = v.findViewById<SeekBar>(R.id.detail_seek)
                        if (dur > 0) {
                            if (seek.max != dur) seek.max = dur
                            seek.progress = pos
                        }
                    } catch (_: Exception) {}
                    updateTimeText(v)
                }
                h.postDelayed(this, 500)
            }
        }
        progressRunnable = r
        h.postDelayed(r, 500)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler?.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateTimeText(v: View) {
        val tv = v.findViewById<TextView>(R.id.detail_time)
        val vv = videoView
        if (vv == null || !videoPrepared) {
            tv.text = "0:00/0:00"
            return
        }
        try {
            tv.text = formatTime(vv.currentPosition) + "/" + formatTime(vv.duration)
        } catch (_: Exception) {}
    }

    private fun formatTime(ms: Int): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    // ─── SWIPE NAVIGATION ───

    private fun setupDragSwipe(imageFrame: FrameLayout) {
        imageFrame.setOnTouchListener { _, event ->
            if (destroyed) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!isDragging && Math.abs(dx) > dragThreshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        isDragging = true
                    }
                    if (isDragging && !isAnimating) {
                        val movingView = imageFrame.findActiveMediaView()
                        movingView?.translationX = dx * 0.6f
                        val alphaVal = 1f - Math.abs(dx) / (imageFrame.width * 2f).coerceAtLeast(1f)
                        movingView?.alpha = alphaVal.coerceIn(0.3f, 1f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && !isAnimating) {
                        val movingView = imageFrame.findActiveMediaView()
                        val dx = event.x - downX
                        if (Math.abs(dx) > dragThreshold * 1.5f) {
                            if (dx < 0 && currentIndex < photos.size - 1) {
                                navigateTo(currentIndex + 1, slideOutLeft = true)
                            } else if (dx > 0 && currentIndex > 0) {
                                navigateTo(currentIndex - 1, slideOutLeft = false)
                            } else {
                                snapBack(movingView)
                            }
                        } else {
                            snapBack(movingView)
                        }
                        isDragging = false; true
                    } else false
                }
                else -> false
            }
        }
    }

    /** The currently visible media view inside the frame (ImageView for photo, VideoView for video). */
    private fun FrameLayout.findActiveMediaView(): View? {
        val img = findViewById<ImageView>(R.id.detail_image)
        if (img.visibility == View.VISIBLE) return img
        val vv = findViewById<VideoView>(R.id.detail_video_view)
        if (vv.visibility == View.VISIBLE) return vv
        return img
    }

    private fun snapBack(view: View?) {
        view?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(200)
            ?.setInterpolator(DecelerateInterpolator())?.start()
    }

    private fun navigateRelative(delta: Int) {
        val newIndex = currentIndex + delta
        if (newIndex < 0 || newIndex >= photos.size) return
        shouldAutoPlay = true
        navigateTo(newIndex, slideOutLeft = delta > 0)
    }

    private fun navigateTo(newIndex: Int, slideOutLeft: Boolean) {
        if (isAnimating || destroyed || newIndex < 0 || newIndex >= photos.size || newIndex == currentIndex) return
        isAnimating = true
        val v = currentView ?: run { isAnimating = false; return }
        val mediaFrame = v.findViewById<FrameLayout>(R.id.detail_media_frame)
        val movingView = mediaFrame.findActiveMediaView()
            ?: run { isAnimating = false; return }

        val outX = if (slideOutLeft) -v.width * 0.4f else v.width * 0.4f

        // Outgoing view animates out
        movingView.animate().translationX(outX).alpha(0f).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (destroyed || activity.isFinishing || activity.isDestroyed) {
                        isAnimating = false; return
                    }
                    currentIndex = newIndex
                    currentPhoto = photos[newIndex]
                    updateContent(v, photos[newIndex], newIndex)
                    switchMedia(v, photos[newIndex])
                    val incoming = mediaFrame.findActiveMediaView()
                    if (incoming != null) {
                        // Incoming view animate to center
                        incoming.translationX = 0f
                        incoming.alpha = 1f
                        isAnimating = false
                    } else {
                        isAnimating = false
                    }
                }
            }).start()
    }

    // ─── LIFECYCLE ───

    /** Called when the detail screen is being closed — release all video resources. */
    fun destroy() {
        destroyed = true
        currentPhoto = null
        hideLoadingBar()
        loadingBar = null
        imageJob?.cancel()
        imageJob = null
        helperScope.cancel()
        stopVideo()
        currentView?.animate()?.cancel()
        currentView = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
    }

    /**
     * Called when the activity goes to background — pause playback but keep the
     * position so playback can continue from the exact same spot later.
     */
    fun pauseForBackground() {
        appInBackground = true
        val vv = videoView
        resumeOnReturn = false
        try {
            // Hanya pause kalau media memang sudah siap; kalau belum, callback
            // onPrepared nanti tidak memulai playback karena appInBackground = true.
            if (vv != null && videoPrepared) {
                val wasPlaying = try { vv.isPlaying } catch (_: Exception) { false }
                if (wasPlaying) {
                    vv.pause()
                    // Disimpan agar saat kembali video dilanjutkan, bukan diulang dari awal
                    resumeOnReturn = true
                }
            }
        } catch (_: Exception) {}
        stopProgressUpdates()
    }

    /**
     * Called when returning from background — continue the video from the position
     * it was paused at (tidak seek ke 0 / tidak memutar ulang dari awal).
     */
    fun resumeFromBackground() {
        appInBackground = false
        if (!resumeOnReturn) return
        val vv = videoView ?: return
        val cv = currentView ?: return
        if (!videoPrepared) return
        try {
            // start() melanjutkan dari posisi yang sedang di-pause
            vv.start()
            resumeOnReturn = false
            updatePlayIcon(cv, true)
            startProgressUpdates(cv)
        } catch (_: Exception) {}
    }
}
