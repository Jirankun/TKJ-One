package com.tkjone.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tkjone.app.R
import com.tkjone.app.model.Photo
import com.tkjone.app.ui.ThemeHelper

/**
 * Single photo detail view — updates in-place, drag swipe navigation.
 * No layout rebuild on photo change.
 */
class PhotoSwipeHelper(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val onBack: () -> Unit,
    private val onDownload: (Photo) -> Unit,
    private val onShare: (Photo) -> Unit,
    private val onVideoOpen: (Photo, Int) -> Unit,
    private val getCachedBitmap: (Photo) -> Bitmap?,
    private val loadHighQuality: (Photo, ImageView) -> Unit
) {
    private var currentView: View? = null
    private var currentPhoto: Photo? = null
    private var currentIndex = 0
    private var photos: List<Photo> = emptyList()
    private var isAnimating = false
    private var destroyed = false

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
            // View exists and is attached — just update content
            updateContent(v, photo, index)
            loadImage(v, photo)
        } else {
            // Create fresh view
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

        val imageFrame = v.findViewById<ImageView>(R.id.detail_image).parent as FrameLayout
        setupDragSwipe(imageFrame)

        updateContent(v, photo, index)
        loadImage(v, photo)
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

            val playOverlay = v.findViewById<FrameLayout>(R.id.detail_play_overlay)
            if (photo.isVideo) {
                playOverlay.visibility = View.VISIBLE
                playOverlay.setOnClickListener { onVideoOpen(photo, index) }
            } else {
                playOverlay.visibility = View.GONE
            }
        } catch (_: Exception) {}
    }

    private fun loadImage(v: View, photo: Photo) {
        try {
            val imgView = v.findViewById<ImageView>(R.id.detail_image)
            imgView.tag = photo.url
            val cachedBmp = getCachedBitmap(photo)
            if (cachedBmp != null && !cachedBmp.isRecycled) {
                imgView.setImageBitmap(cachedBmp)
                imgView.scaleType = ImageView.ScaleType.FIT_CENTER
                imgView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                imgView.setImageBitmap(null)
                imgView.setBackgroundColor(0xFF1C1C1E.toInt())
                imgView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            if (photo.isVideo) return
            loadHighQuality(photo, imgView)
        } catch (_: Exception) {}
    }

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
                        val imgView = imageFrame.findViewById<ImageView>(R.id.detail_image)
                        imgView?.translationX = dx * 0.6f
                        val alphaVal = 1f - Math.abs(dx) / (imageFrame.width * 2f).coerceAtLeast(1f)
                        imgView?.alpha = alphaVal.coerceIn(0.3f, 1f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && !isAnimating) {
                        val imgView = imageFrame.findViewById<ImageView>(R.id.detail_image)
                        val dx = event.x - downX
                        if (Math.abs(dx) > dragThreshold * 1.5f) {
                            if (dx < 0 && currentIndex < photos.size - 1) {
                                navigateTo(currentIndex + 1, slideOutLeft = true)
                            } else if (dx > 0 && currentIndex > 0) {
                                navigateTo(currentIndex - 1, slideOutLeft = false)
                            } else {
                                snapBack(imgView)
                            }
                        } else {
                            snapBack(imgView)
                        }
                        isDragging = false; true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun snapBack(imgView: ImageView?) {
        imgView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(200)
            ?.setInterpolator(DecelerateInterpolator())?.start()
    }

    private fun navigateTo(newIndex: Int, slideOutLeft: Boolean) {
        if (isAnimating || destroyed || newIndex < 0 || newIndex >= photos.size || newIndex == currentIndex) return
        isAnimating = true
        val v = currentView ?: run { isAnimating = false; return }
        val imgView = v.findViewById<ImageView>(R.id.detail_image) ?: run { isAnimating = false; return }

        val outX = if (slideOutLeft) -v.width * 0.4f else v.width * 0.4f
        imgView.animate().translationX(outX).alpha(0f).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (destroyed || activity.isFinishing || activity.isDestroyed) {
                        isAnimating = false; return
                    }
                    currentIndex = newIndex
                    currentPhoto = photos[newIndex]
                    updateContent(v, photos[newIndex], newIndex)
                    imgView.translationX = -outX
                    loadImage(v, photos[newIndex])
                    imgView.animate().translationX(0f).alpha(1f).setDuration(250)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) { isAnimating = false }
                        }).start()
                }
            }).start()
    }

    fun destroy() {
        destroyed = true
        currentView?.animate()?.cancel()
        currentView = null
        currentPhoto = null
    }
}
