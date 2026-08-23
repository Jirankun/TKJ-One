package com.tkjone.app
import com.tkjone.app.config.AppConfig

import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tkjone.app.model.Photo
import com.tkjone.app.service.ApiService
import com.tkjone.app.service.DownloadService
import com.tkjone.app.service.ThumbnailCacheService
import com.tkjone.app.ui.ThemeHelper
import kotlinx.coroutines.*

class UriViewerActivity : com.tkjone.app.ui.AppActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var photos: List<Photo> = emptyList()
    private lateinit var rootContainer: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootContainer = findViewById(R.id.root_container)

        val photoName = extractPhotoName(intent)
        if (photoName == null) { showError(); return }

        scope.launch {
            val result = ApiService.fetchPhotos(this@UriViewerActivity)
            if (result.isSuccess && result.data != null) {
                photos = result.data
                val match = photos.find { it.name.equals(photoName, ignoreCase = true) }
                if (match != null) showPhotoDetail(match) else showError()
            } else showError()
        }
    }

    private fun extractPhotoName(intent: Intent?): String? {
        val uri = intent?.data ?: return intent?.getStringExtra("photo_name")
        val scheme = uri.scheme
        val host = uri.host
        val segs = uri.pathSegments
        return when {
            scheme == "https" && host == AppConfig.DEEP_LINK_HOST && segs.size >= 2 && segs[0] == "photo" -> segs[1]
            scheme == "https" && host == AppConfig.DEEP_LINK_HOST && segs.size == 1 && segs[0] != "photo" -> segs[0]
            scheme == "tkjone" && host == "photo" && segs.isNotEmpty() -> segs[0]
            scheme == "tkjone" && segs.size >= 2 && segs[0] == "photo" -> segs[1]
            else -> intent?.getStringExtra("photo_name")
        }
    }

    private fun showError() {
        val v = layoutInflater.inflate(R.layout.screen_error, rootContainer, false) as ViewGroup
        v.setBackgroundColor(0xFF1C1C1E.toInt())
        rootContainer.removeAllViews()
        rootContainer.addView(v)
        animateScreenIn(v)
    }

    private fun showPhotoDetail(photo: Photo) {
        val isDark = isDark
        val v = layoutInflater.inflate(R.layout.screen_photo_detail, null) as ViewGroup
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        v.findViewById<LinearLayout>(R.id.detail_nav_bar).setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        v.findViewById<TextView>(R.id.detail_title).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_title).text = getString(R.string.photo_name_format, photo.name)
        v.findViewById<TextView>(R.id.detail_counter).text = getString(R.string.photo_counter_format, 1, photos.size)

        v.findViewById<ImageView>(R.id.detail_back_btn).setOnClickListener { finish() }

        v.findViewById<ImageView>(R.id.detail_share_btn).setOnClickListener {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, AppConfig.photoLink(photo.name))
            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_chooser)))
        }

        v.findViewById<ImageView>(R.id.detail_download_btn).setOnClickListener {
            scope.launch {
                try {
                    val fileName = "${photo.name}.jpg"
                    val result = DownloadService.downloadToDcim(this@UriViewerActivity, photo.url, fileName)
                    val msg = if (result.success) getString(R.string.toast_download_complete) else result.message
                    Toast.makeText(this@UriViewerActivity, msg, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@UriViewerActivity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Badge
        val badge = v.findViewById<TextView>(R.id.detail_media_badge)
        val badgeText: String
        val badgeColor: Int
        if (photo.isVideo) { badgeText = getString(R.string.type_video); badgeColor = ThemeHelper.ACCENT_YELLOW }
        else { badgeText = getString(R.string.type_photo); badgeColor = ThemeHelper.ACCENT_BLUE }
        badge.text = badgeText
        badge.setTextColor(badgeColor)
        badge.background = GradientDrawable().apply {
            setColor((badgeColor.toInt() and 0x1FFFFFFF.toInt()) or 0x15000000)
            cornerRadius = dpFloat(12f)
            setStroke(1, (badgeColor.toInt() and 0x3FFFFFFF.toInt()) or 0x30000000)
        }

        v.findViewById<TextView>(R.id.detail_photo_name).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_photo_name).text = getString(R.string.photo_name_format, photo.name)
        v.findViewById<TextView>(R.id.detail_date).setTextColor(ThemeHelper.getSecLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_date).text = photo.date

        if (photo.isVideo) {
            v.findViewById<FrameLayout>(R.id.detail_play_overlay).visibility = View.VISIBLE
            v.findViewById<FrameLayout>(R.id.detail_play_overlay).setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.url))) }
                catch (_: Exception) {}
            }
        }

        v.findViewById<LinearLayout>(R.id.detail_footer).setBackgroundColor(ThemeHelper.getCardColor(isDark))

        val imgView = v.findViewById<ImageView>(R.id.detail_image)
        imgView.setBackgroundColor(0xFF1C1C1E.toInt())

        val loadingTv = TextView(this).apply {
            text = getString(R.string.gallery_loading_thumbs, 1)
            textSize = 13f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER
        }
        (v as? ViewGroup)?.addView(loadingTv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        // Load image
        scope.launch {
            try {
                val cachedP = ThumbnailCacheService.getCachedPathSync(this@UriViewerActivity, photo.url, photo.date)
                if (cachedP != null) {
                    val lowBmp = ThumbnailCacheService.decodeBitmapOnIO(cachedP, 400)
                    if (lowBmp != null) { imgView.setImageBitmap(lowBmp); imgView.setBackgroundColor(Color.TRANSPARENT); loadingTv.visibility = View.GONE }
                }
                val p = cachedP ?: ThumbnailCacheService.cacheThumbnail(this@UriViewerActivity, photo.url, version = photo.date)
                if (p != null) {
                    val isGif = photo.url.lowercase().let { it.endsWith(".gif") || it.contains(".gif?") }
                    if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val drawable = withContext(Dispatchers.IO) {
                                ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, Uri.parse(photo.url))) { decoder, _, _ ->
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = true
                                }
                            }
                            if (drawable != null) { imgView.setImageDrawable(drawable); loadingTv.visibility = View.GONE }
                        } catch (_: Exception) {
                            val bmp = ThumbnailCacheService.decodeBitmapOnIO(p, 800)
                            if (bmp != null) { imgView.setImageBitmap(bmp); loadingTv.visibility = View.GONE }
                        }
                    } else {
                        val bmp = ThumbnailCacheService.decodeBitmapOnIO(p, 800)
                        if (bmp != null) { imgView.setImageBitmap(bmp); loadingTv.visibility = View.GONE }
                    }
                }
                loadingTv.visibility = View.GONE
            } catch (_: Exception) { loadingTv.visibility = View.GONE }
        }

        rootContainer.removeAllViews()
        rootContainer.addView(v)
        animateScreenIn(v)
    }


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
