package com.tkjone.app
import com.tkjone.app.config.AppConfig
import com.tkjone.app.service.ImageEnhancer

import android.Manifest
import android.app.Dialog
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.ContextCompat
import com.tkjone.app.model.InfoMessage
import com.tkjone.app.model.Photo
import com.tkjone.app.service.ApiService
import com.tkjone.app.service.AuthService
import com.tkjone.app.service.ThumbnailCacheService
import com.tkjone.app.service.UpdateInfo
import com.tkjone.app.service.UpdateService
import com.tkjone.app.ui.DialogHelper
import com.tkjone.app.ui.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : com.tkjone.app.ui.AppActivity() {

    private lateinit var rootContainer: FrameLayout
    private var currentScreenTag: String? = null

    // State
    private var loggedInUser: String? = null
    private val photos = mutableListOf<Photo>()
    private val infoMessages = mutableListOf<InfoMessage>()
    private var isOffline = false
    private var isLoading = true
    private var errorMessage: String? = null
    private var isMaintenanceShowing = false

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private var dataRefreshRunnable: Runnable? = null
    private var suspendCheckRunnable: Runnable? = null
    private var infoCheckRunnable: Runnable? = null

    // Gallery state
    private var searchQuery = ""
    private var isSearching = false
    private var initialPhotoName: String? = null
    private var galleryScrollY = 0
    private var galleryFilter = 0 // 0=all, 1=photo, 2=video
    private val FILTER_PREF_KEY = "gallery_filter"
    private val galleryCardMap = mutableMapOf<String, View>() // photo name -> card view
    private val cardBitmapCache = mutableMapOf<String, Bitmap>() // photo name -> cached bitmap for instant detail
    private var galleryBuilt = false // track if grid already built
    private var photoDetailJob: Job? = null // cancel when navigating to new photo
    private var lastResumeTime = 0L // guard against rapid onResume calls

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val PERMISSION_REQUEST_CODE = 1002
    private var pendingDownloadPhoto: Photo? = null

    // ─── GLOBAL CRASH GUARD (penyebab #1 force close ditangani di sini) ───
    // Error tak terduga di thread mana pun tidak lagi mematikan proses;
    // dicatat, dan user diberi tahu lewat toast bila app masih hidup.
    private val defaultUEH = Thread.getDefaultUncaughtExceptionHandler()
    private val crashGuard = Thread.UncaughtExceptionHandler { t, e ->
        android.util.Log.e("TKJCrashGuard", "Unhandled di ${t.name}", e)
        try {
            if (!isFinishing && !isDestroyed) {
                handler.post {
                    try { DialogHelper.showToast(this, getString(R.string.toast_app_error)) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private var galleryVersion = 0
    private var thumbnailJobs = mutableListOf<Job>()
    private var thumbnailsPaused = false // pause thumbnails when photo detail is open

    // Cached views for nav switching (no rebuild)
    private var cachedGalleryView: View? = null
    private var cachedProfileView: View? = null
    private var cachedHomeView: View? = null

    // ─── LIFECYCLE ───

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pasang crash guard secepat mungkin (setelah super agar WebView provider siap)
        Thread.setDefaultUncaughtExceptionHandler(crashGuard)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        rootContainer = findViewById(R.id.root_container)
        parseDeepLink(intent)
        // Auto-trim cache on startup — di luar jalur kritis splash agar tidak memperlambat load
        scope.launch { ThumbnailCacheService.trimCacheOnStartup(this@MainActivity) }
        // Warm-up Custom Tab browser agar halaman upload terbuka instan
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.tkjone.app.service.CustomTabLauncher.warmUp(this@MainActivity) }
        showSplash()
        scope.launch { initialize() }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (currentScreenTag) {
            "story" -> { destroyWebViews(); showHomeScreen() }
            "photo_detail" -> { photoSwipeHelper?.destroy(); photoSwipeHelper = null; showHomeScreen() }
            "video_player" -> showHomeScreen()
            "upload" -> showHomeScreen()
            "error" -> { /* do nothing, error screen has retry button */ }
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDeepLink(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }
                    if (bitmap != null) {
                        withContext(Dispatchers.IO) {
                            val f = File(filesDir, "profile_photo.jpg")
                            f.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                            bitmap.recycle()
                        }
                        getSharedPreferences("tkjone_prefs", MODE_PRIVATE).edit()
                            .putString("profile_photo", File(filesDir, "profile_photo.jpg").absolutePath).apply()
                        DialogHelper.showToast(this@MainActivity, getString(R.string.profile_photo_pick))
                        cachedProfileView = null
                        showHomeScreen()
                    }
                } catch (e: Exception) {
                    DialogHelper.showToast(this@MainActivity, getString(R.string.profile_photo_error, e.message ?: ""))
                }
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasStoragePermission()) {
                pendingDownloadPhoto?.let { photo -> pendingDownloadPhoto = null; startDownloadWithProgress(photo) }
            } else DialogHelper.showToast(this, getString(R.string.toast_permission_photo_video))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is EditText) {
                val rect = android.graphics.Rect()
                f.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    hideKeyboardFrom(f)
                    f.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // hideKeyboardFrom() and makeFocusableOutside() inherited from AppActivity

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (loggedInUser != null && !isLoading && now - lastResumeTime > 3000) {
            lastResumeTime = now
            scope.launch {
                if (!isDestroyed && !isFinishing) {
                    fetchAllData()
                    if (!isDestroyed && !isFinishing) {
                        runOnUiThread { cachedGalleryView?.let { refreshPhotoGrid(it) } }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Kembalikan handler default — jangan tinggalkan guard milik activity mati
        if (Thread.getDefaultUncaughtExceptionHandler() === crashGuard) {
            Thread.setDefaultUncaughtExceptionHandler(defaultUEH)
        }
        cancelTimers()
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear()
        photoDetailJob?.cancel()
        photoSwipeHelper?.destroy(); photoSwipeHelper = null
        destroyWebViews()
        // Recycle bitmap cache — mencegah memory leak yang berujung OOM/force close
        cardBitmapCache.values.forEach { try { it.recycle() } catch (_: Exception) {} }
        cardBitmapCache.clear()
        galleryCardMap.clear()
        cachedHomeView = null; cachedGalleryView = null; cachedProfileView = null
        scope.cancel()
        ApiService.cancelAll(); ThumbnailCacheService.cancelAll()
    }

    // ─── WEBVIEW CLEANUP ───

    private fun destroyWebViews() {
        try {
            fun findWebView(view: View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is ViewGroup) { for (i in 0 until view.childCount) { val f = findWebView(view.getChildAt(i)); if (f != null) return f } }
                return null
            }
            val wv = findWebView(rootContainer)
            if (wv != null) {
                wv.stopLoading(); wv.webViewClient = android.webkit.WebViewClient()
                wv.webChromeClient = android.webkit.WebChromeClient(); wv.removeJavascriptInterface("AndroidInterface")
                wv.removeAllViews(); wv.destroy()
            }
        } catch (_: Exception) {}
    }

    // ─── INIT ───

    private fun parseDeepLink(intent: Intent?) {
        // Deep links for photos are handled by UriViewerActivity
        // Only keep in-app navigation (story, internal links)
    }

    private fun applyTheme() {
        val isDark = isDark
        ThemeHelper.applyStatusBarStyle(window); ThemeHelper.applyNavBarColor(window)
    }


    private suspend fun initialize() {
        applyTheme()
        // Tampilkan layar secepat mungkin — JANGAN blokir splash dengan jaringan.
        loggedInUser = AuthService.getLoggedInUser(this)
        if (loggedInUser == null) {
            isLoading = false; showAppropriateScreen()
            startInfoCheckTimer()
            return
        }
        // Langsung tampilkan home dari cache lokal (instan, tanpa stuck "Loading…")
        val cached = withContext(Dispatchers.IO) { ApiService.loadCachedPhotos(this@MainActivity) }
        if (!cached.isNullOrEmpty()) {
            photos.clear(); photos.addAll(cached); isOffline = true
        }
        isLoading = false; showAppropriateScreen()
        // Baru lalu refresh data asli di background
        try { fetchAllData() } catch (_: Exception) {}
        if (!isDestroyed && !isFinishing) {
            runOnUiThread {
                cachedGalleryView?.let { refreshPhotoGrid(it) }
                if (photos.isEmpty() && errorMessage != null) showErrorView()
            }
        }
        startSuspendTimer()
        initialPhotoName?.let { n -> if (photos.isNotEmpty()) { initialPhotoName = null; navigateToPhoto(n) } }
        showInfoMessages(); checkForUpdate()
        startInfoCheckTimer()
    }

    private fun showAppropriateScreen() {
        when {
            loggedInUser == null -> showLoginScreen()
            photos.isEmpty() && errorMessage != null -> showErrorView()
            else -> showHomeScreen()
        }
    }

    // ─── DATA ───

    private suspend fun fetchAllData() {
        try {
            val r1 = withContext(Dispatchers.IO) { ApiService.fetchPhotos(this@MainActivity) }
            val r2 = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }
            if (r1.isSuccess) { photos.clear(); photos.addAll(r1.data!!); isOffline = false; errorMessage = null }
            else { val c = withContext(Dispatchers.IO) { ApiService.loadCachedPhotos(this@MainActivity) }; if (!c.isNullOrEmpty()) { photos.clear(); photos.addAll(c); isOffline = true } else { photos.clear(); errorMessage = r1.error } }
            if (r2.isSuccess) { infoMessages.clear(); infoMessages.addAll(r2.data!!) }
            checkForMaintenance()
        } catch (e: Exception) { errorMessage = e.message }
    }

    // ─── TIMERS ───

    private fun startSuspendTimer() { suspendCheckRunnable?.let { handler.removeCallbacks(it) }; suspendCheckRunnable = object : Runnable { override fun run() { if (isDestroyed || isFinishing) return; scope.launch { checkUserSuspendStatus() }; handler.postDelayed(this, 300_000L) } }; handler.postDelayed(suspendCheckRunnable!!, 300_000L) }
    private fun startInfoCheckTimer() { infoCheckRunnable?.let { handler.removeCallbacks(it) }; infoCheckRunnable = object : Runnable { override fun run() { if (isDestroyed || isFinishing) return; scope.launch { try { val r = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }; if (r.isSuccess && !isDestroyed) { infoMessages.clear(); infoMessages.addAll(r.data!!); checkForMaintenance() } } catch (_: Exception) {} }; handler.postDelayed(this, 30_000L) } }; handler.postDelayed(infoCheckRunnable!!, 30_000L) }
    private fun cancelTimers() { dataRefreshRunnable?.let { handler.removeCallbacks(it) }; suspendCheckRunnable?.let { handler.removeCallbacks(it) }; infoCheckRunnable?.let { handler.removeCallbacks(it) } }

    // ─── AUTH ───

    private suspend fun checkUserSuspendStatus() { val user = loggedInUser ?: return; val reason = AuthService.checkUserStatus(this, user) ?: return; cancelTimers(); forceLogout(reason) }
    private fun forceLogout(reason: String) { runOnUiThread { if (!isFinishing && !isDestroyed) DialogHelper.showInfoDialog(this, getString(R.string.dialog_suspended_title), reason) { finish() } }; loggedInUser = null; scope.launch { AuthService.logout(this@MainActivity) } }
    private fun handleLogout() { cancelTimers(); thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear(); photoDetailJob?.cancel(); photoSwipeHelper?.destroy(); photoSwipeHelper = null; scope.launch { AuthService.logout(this@MainActivity); runOnUiThread { loggedInUser = null; cachedHomeView = null; cachedGalleryView = null; cachedProfileView = null; galleryBuilt = false; photos.clear(); infoMessages.clear(); showLoginScreen(); DialogHelper.showToast(this@MainActivity, getString(R.string.toast_logout)) } } }

    // ─── MAINTENANCE ───

    private fun checkForMaintenance() { if (isMaintenanceShowing) return; val m = infoMessages.filter { it.isActive && it.category == "main" }; if (m.isNotEmpty()) { isMaintenanceShowing = true; runOnUiThread { if (!isFinishing && !isDestroyed) DialogHelper.showInfoDialog(this, getString(R.string.dialog_maintenance_title), m.first().message) { finish() } } } else isMaintenanceShowing = false }
    private fun showInfoMessages() { val active = infoMessages.filter { it.isActive && it.category == "info" }; val prefs = getSharedPreferences("info_shown", MODE_PRIVATE); for (msg in active) { val key = "shown_${msg.message.hashCode()}"; if (prefs.getBoolean(key, false)) continue; prefs.edit().putBoolean(key, true).apply(); runOnUiThread { if (!isFinishing && !isDestroyed) DialogHelper.showInfoDialog(this, getString(R.string.dialog_info_title), msg.message) } } }
    private suspend fun checkForUpdate() { try { val r = UpdateService.checkForUpdate(this); if (r.hasUpdate) runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(r.updateInfo!!) } } catch (_: Exception) {} }

    // ─── SPLASH ───

    private fun showSplash() { destroyWebViews(); val v = layoutInflater.inflate(R.layout.screen_splash, rootContainer, false); val isDark = isDark; v.setBackgroundColor(ThemeHelper.getBgColor(isDark)); v.findViewById<TextView>(R.id.splash_text).setTextColor(ThemeHelper.getSecLabelColor(isDark));        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "splash"
        animateScreenIn(v) }

    // ─── LOGIN ───

    private fun showLoginScreen() {
        destroyWebViews(); val v = layoutInflater.inflate(R.layout.screen_login, rootContainer, false)
        // Background image handles the full screen — no solid color needed
        // Card styling
        val isDark = isDark
        val card = v.findViewById<LinearLayout>(R.id.login_card)
        card.setBackgroundResource(if (isDark) R.drawable.login_card_bg_dark else R.drawable.login_card_bg)
        v.findViewById<TextView>(R.id.login_title).setTextColor(if (isDark) Color.WHITE else 0xFF222222.toInt())
        v.findViewById<TextView>(R.id.login_subtitle).setTextColor(if (isDark) 0x99FFFFFF.toInt() else 0x66666666.toInt())
        val nameInput = v.findViewById<EditText>(R.id.name_input)
        nameInput.setTextColor(if (isDark) Color.WHITE else 0xFF222222.toInt())
        nameInput.setHintTextColor(if (isDark) 0x66FFFFFF.toInt() else 0x66666666.toInt())
        nameInput.setBackgroundResource(if (isDark) R.drawable.edit_text_bg_dark else R.drawable.edit_text_bg)
        val errorText = v.findViewById<TextView>(R.id.error_text); errorText.setTextColor(ThemeHelper.ACCENT_RED)
        val loginBtnContainer = v.findViewById<LinearLayout>(R.id.login_btn_container); loginBtnContainer.background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f)
        val loginBtnText = v.findViewById<TextView>(R.id.login_btn_text); val loginSpinner = v.findViewById<ProgressBar>(R.id.login_spinner)
        v.findViewById<LinearLayout>(R.id.hint_card).background = GradientDrawable().apply { setColor(if (isDark) 0x10FFFFFF else 0x0A000000); cornerRadius = dpFloat(10f); setStroke(1, if (isDark) 0x08FFFFFF else 0x05000000) }
        var isLoggingIn = false
        nameInput.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (errorText.visibility == View.VISIBLE) errorText.visibility = View.GONE }; override fun afterTextChanged(s: Editable?) {} })
        loginBtnContainer.setOnClickListener {
            if (isLoggingIn) return@setOnClickListener
            val name = nameInput.text.toString().trim()
            val validationError = AuthService.validateName(this@MainActivity, name)
            if (validationError != null) {
                errorText.text = validationError; errorText.visibility = View.VISIBLE; return@setOnClickListener
            }
            isLoggingIn = true
            loginBtnText.visibility = View.GONE; loginSpinner.visibility = View.VISIBLE
            scope.launch {
                try {
                    val loginError = AuthService.getLoginErrorMessage(this@MainActivity, name)
                    if (loginError.isNotEmpty()) {
                        errorText.text = loginError; errorText.visibility = View.VISIBLE
                        isLoggingIn = false; loginBtnText.visibility = View.VISIBLE; loginSpinner.visibility = View.GONE
                    } else {
                        val user = AuthService.login(this@MainActivity, name)
                        if (user != null) {
                            loggedInUser = user
                            fetchAllData()
                            isLoading = false
                            showHomeScreen()
                        } else {
                            errorText.text = getString(R.string.login_error_generic); errorText.visibility = View.VISIBLE
                            isLoggingIn = false; loginBtnText.visibility = View.VISIBLE; loginSpinner.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    errorText.text = e.message ?: getString(R.string.login_error_generic); errorText.visibility = View.VISIBLE
                    isLoggingIn = false; loginBtnText.visibility = View.VISIBLE; loginSpinner.visibility = View.GONE
                }
            }
        }
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "login"
        animateScreenInFromBottom(v)
    }

    // ─── HOME ───

    private fun showHomeScreen() {
        thumbnailsPaused = false // resume thumbnails
        destroyWebViews(); applyTheme()
        // Cache and reuse home view
        val v = cachedHomeView ?: run {
            val newV = layoutInflater.inflate(R.layout.screen_home, rootContainer, false)
            cachedHomeView = newV; newV
        }
        val isDark = isDark
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        val bottomNav = v.findViewById<LinearLayout>(R.id.bottom_nav); bottomNav.setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        v.findViewById<View>(R.id.nav_separator_top).setBackgroundColor(ThemeHelper.getSepColor(isDark))
        v.findViewById<View>(R.id.nav_divider).setBackgroundColor(ThemeHelper.getSepColor(isDark))
        val galleryTab = v.findViewById<LinearLayout>(R.id.nav_gallery)
        val galleryIcon = v.findViewById<ImageView>(R.id.nav_gallery_icon); val galleryLabel = v.findViewById<TextView>(R.id.nav_gallery_label)
        val profileTab = v.findViewById<LinearLayout>(R.id.nav_profile)
        val profileIcon = v.findViewById<ImageView>(R.id.nav_profile_icon); val profileLabel = v.findViewById<TextView>(R.id.nav_profile_label)
        val contentFrame = v.findViewById<FrameLayout>(R.id.home_content)

        var activeTab = 0
        fun updateNav() {
            // Icons stay fixed: photo for gallery, person for profile
            // Only COLOR changes to indicate active tab
            galleryIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (activeTab == 0) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            galleryLabel.setTextColor(if (activeTab == 0) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            galleryLabel.typeface = resources.getFont(if (activeTab == 0) R.font.torus_bold else R.font.torus_regular)
            profileIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (activeTab == 1) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            profileLabel.setTextColor(if (activeTab == 1) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            profileLabel.typeface = resources.getFont(if (activeTab == 1) R.font.torus_bold else R.font.torus_regular)
        }

        fun showTab(tab: Int) {
            if (activeTab == tab && contentFrame.childCount > 0) return // Don't rebuild same tab
            activeTab = tab; updateNav(); contentFrame.removeAllViews()
            when (tab) {
                0 -> {
                    val gv = cachedGalleryView ?: createGalleryView().also { cachedGalleryView = it }
                    contentFrame.addView(gv)
                }
                1 -> {
                    val pv = cachedProfileView ?: createProfileView().also { cachedProfileView = it }
                    contentFrame.addView(pv)
                }
            }
        }

        // Remove old listeners before setting new ones
        galleryTab.setOnClickListener(null); profileTab.setOnClickListener(null)
        galleryTab.setOnClickListener { showTab(0) }
        profileTab.setOnClickListener { showTab(1) }

        if (rootContainer.childCount == 0 || currentScreenTag != "home") {
            rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "home"
            animateScreenIn(v)
        }
        showTab(0)
        // Restore gallery scroll position
        if (galleryScrollY > 0) {
            v.post {
                try {
                    val gv = cachedGalleryView
                    val scrollView = gv?.findViewById<ScrollView>(R.id.photo_scroll)
                    scrollView?.scrollTo(0, galleryScrollY)
                } catch (_: Exception) {}
            }
        }
    }

    // ─── GIF LOADER ───

    private fun loadGifOrImage(imageView: ImageView, url: String, maxWidth: Int) {
        val isGif = url.lowercase().let { it.endsWith(".gif") || it.contains(".gif?") }
        if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+ can decode GIF as animated drawable
            scope.launch {
                try {
                    val drawable: Drawable? = withContext(Dispatchers.IO) {
                        val uri = Uri.parse(url)
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    }
                    if (drawable != null && !isDestroyed) imageView.setImageDrawable(drawable)
                } catch (_: Exception) {
                    // Fallback: try cached version
                    val path = ThumbnailCacheService.getCachedPathSync(this@MainActivity, url)
                    if (path != null && !isDestroyed) {
                        val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, maxWidth)
                        if (bmp != null && !isDestroyed) imageView.setImageBitmap(bmp)
                    }
                }
            }
        } else if (isGif) {
            // Pre-28: just show first frame
            scope.launch {
                try {
                    val path = ThumbnailCacheService.cacheThumbnail(this@MainActivity, url, version = "gif")
                    if (path != null && !isDestroyed) {
                        val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, maxWidth)
                        if (bmp != null && !isDestroyed) imageView.setImageBitmap(bmp)
                    }
                } catch (_: Exception) {}
            }
        } else {
            // Normal image
            scope.launch {
                try {
                    val path = ThumbnailCacheService.cacheThumbnail(this@MainActivity, url)
                    if (path != null && !isDestroyed) {
                        val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, maxWidth)
                        if (bmp != null && !isDestroyed) imageView.setImageBitmap(bmp)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ─── GALLERY ───

    private fun createGalleryView(): View {
        val isDark = isDark
        val v = layoutInflater.inflate(R.layout.screen_gallery, null as ViewGroup?)
        v.setBackgroundColor(Color.TRANSPARENT)
        makeFocusableOutside(v)
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear(); galleryVersion++
        galleryCardMap.clear(); cardBitmapCache.clear(); galleryBuilt = false

        // Load saved filter preference
        galleryFilter = getSharedPreferences("tkjone_prefs", MODE_PRIVATE).getInt(FILTER_PREF_KEY, 0)
        val title = v.findViewById<TextView>(R.id.gallery_title); title.setTextColor(ThemeHelper.ACCENT_BLUE)
        title.text = when (galleryFilter) { 1 -> getString(R.string.filter_photo); 2 -> getString(R.string.filter_video); else -> getString(R.string.gallery_filter_all) }
        v.findViewById<ImageView>(R.id.gallery_search_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)
        // Filter dropdown
        val filterBtn = v.findViewById<LinearLayout>(R.id.gallery_filter_btn)
        filterBtn?.setOnClickListener { showFilterDropdown(title) }
        val searchContainer = v.findViewById<LinearLayout>(R.id.search_container)
        val searchInput = v.findViewById<EditText>(R.id.search_input); searchInput.setTextColor(ThemeHelper.getLabelColor(isDark)); searchInput.setHintTextColor(ThemeHelper.getSecLabelColor(isDark)); searchInput.background = GradientDrawable().apply { setColor(ThemeHelper.getBgColor(isDark)); cornerRadius = dpFloat(10f) }
        var searchJob: Job? = null
        searchInput.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchQuery = s?.toString() ?: ""; searchJob?.cancel(); searchJob = scope.launch { delay(300); if (!isDestroyed && galleryBuilt) filterCards(v) } }; override fun afterTextChanged(s: Editable?) {} })
        v.findViewById<ImageView>(R.id.gallery_search_btn).setOnClickListener {
            isSearching = !isSearching
            if (isSearching) {
                // Smooth expand search bar
                searchContainer.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                val targetH = searchContainer.measuredHeight
                searchContainer.layoutParams.height = 0
                searchContainer.visibility = View.VISIBLE
                val anim = android.animation.ValueAnimator.ofInt(0, targetH)
                anim.duration = 200
                anim.interpolator = android.view.animation.DecelerateInterpolator()
                anim.addUpdateListener { searchContainer.layoutParams.height = it.animatedValue as Int; searchContainer.requestLayout() }
                anim.start()
                // Delay focus until layout is ready
                searchInput.post {
                    searchInput.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                searchInput.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
                searchQuery = ""; searchInput.text.clear()
                // Smooth collapse search bar
                val startH = searchContainer.height
                val anim = android.animation.ValueAnimator.ofInt(startH, 0)
                anim.duration = 180
                anim.interpolator = android.view.animation.AccelerateInterpolator()
                anim.addUpdateListener { searchContainer.layoutParams.height = it.animatedValue as Int; searchContainer.requestLayout() }
                anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { searchContainer.visibility = View.GONE }
                })
                anim.start()
                try { if (v.isAttachedToWindow && galleryBuilt) filterCards(v) } catch (_: Exception) {}
            }
        }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isSearching) {
                isSearching = false; searchQuery = ""; searchInput.text.clear()
                val startH = searchContainer.height
                val anim = android.animation.ValueAnimator.ofInt(startH, 0)
                anim.duration = 180; anim.interpolator = android.view.animation.AccelerateInterpolator()
                anim.addUpdateListener { searchContainer.layoutParams.height = it.animatedValue as Int; searchContainer.requestLayout() }
                anim.addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { searchContainer.visibility = View.GONE } })
                anim.start()
                try { if (v.isAttachedToWindow && galleryBuilt) filterCards(v) } catch (_: Exception) {}
            }
        }

        v.findViewById<LinearLayout>(R.id.offline_banner).visibility = if (isOffline) View.VISIBLE else View.GONE
        val countTv = v.findViewById<TextView>(R.id.photo_count); countTv.setTextColor(ThemeHelper.getSecLabelColor(isDark))

        // ═══ AD BANNER PLACEHOLDER ═══
        // Pasang iklan di sini (misal: AdMob, Unity Ads, dll)
        // Load ad -> tambahkan ke view
        // Contoh:
        // val adView = AdView(this)
        // adView.adSize = AdSize.BANNER
        // adView.adUnitId = "ca-app-pub-XXXXX"
        // adContainer.addView(adView)

        val fab = v.findViewById<FrameLayout>(R.id.story_fab)
        v.findViewById<View>(R.id.story_fab_bg).background = GradientDrawable().apply { colors = intArrayOf(0xFF6366F1.toInt(), 0xFF8B5CF6.toInt()); cornerRadius = dpFloat(16f) }
        fab.setOnClickListener { showStoryView() }

        refreshPhotoGrid(v)
        return v
    }

    private fun showFilterDropdown(titleTv: TextView) {
        val items = arrayOf(
            getString(R.string.filter_all) to 0,
            getString(R.string.filter_photo) to 1,
            getString(R.string.filter_video) to 2
        )
        // Build manual popup to avoid ListPopupWindow modal covering gallery
        val popup = android.widget.PopupWindow(this)
        val listView = android.widget.ListView(this)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { it.first })
        listView.adapter = adapter
        listView.setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        listView.setOnItemClickListener { _, _, position, _ ->
            galleryFilter = items[position].second
            getSharedPreferences("tkjone_prefs", MODE_PRIVATE).edit().putInt(FILTER_PREF_KEY, galleryFilter).apply()
            titleTv.text = items[position].first
            filterCards(cachedGalleryView ?: return@setOnItemClickListener)
            popup.dismiss()
        }
        popup.contentView = listView
        popup.width = dp(200)
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.showAsDropDown(titleTv)
    }

    private fun getFilteredPhotos(): List<Photo> {
        val q = searchQuery.lowercase()
        return photos.filter { p ->
            val matchesSearch = q.isEmpty() || p.name.lowercase().contains(q)
            val matchesType = when (galleryFilter) {
                1 -> !p.isVideo
                2 -> p.isVideo
                else -> true
            }
            matchesSearch && matchesType
        }
    }

    private fun refreshPhotoGrid(galleryView: View) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val countTv = galleryView.findViewById<TextView>(R.id.photo_count)
        val isDark = isDark
        // Hentikan shimmer & job thumbnail lama sebelum rebuild — mencegah animasi
        // yatim menumpuk (penyebab UI makin lama makin berat lalu force close).
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear()
        galleryVersion++
        galleryCardMap.clear()
        grid.removeAllViews()
        val filtered = getFilteredPhotos()
        countTv?.text = "${filtered.size}"

        if (filtered.isEmpty()) {
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(64), 0, dp(64))
                addView(ImageView(this@MainActivity).apply { setImageResource(if (searchQuery.isNotEmpty()) R.drawable.close else R.drawable.image); setColorFilter(ThemeHelper.getSecLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)); gravity = Gravity.CENTER })
                addView(TextView(this@MainActivity).apply { text = if (searchQuery.isNotEmpty()) getString(R.string.gallery_empty_search, searchQuery) else getString(R.string.gallery_empty); textSize = 15f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
            }); return
        }

        // Loading indicator while thumbnails load
        val loadingTv = TextView(this).apply {
            text = getString(R.string.gallery_loading_thumbs, filtered.size)
            textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark))
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4))
        }
        grid.addView(loadingTv)

        val ver = galleryVersion
        var row: LinearLayout? = null
        var cardIdx = 0
        for ((idx, photo) in filtered.withIndex()) {
            if (idx % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }; grid.addView(row) }
            val card = createPhotoCard(photo, isDark, ver, cardIdx++)
            galleryCardMap[photo.name] = card
            row?.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(if (idx % 2 == 0) 0 else dp(6), 0, if (idx % 2 == 0) dp(6) else 0, 0)
            })
            // Hanya 12 kartu pertama yang dianimasikan — sisanya langsung tampil
            // agar scroll awal tidak drop-frame saat konten banyak.
            if (idx < 12) animateCardIn(card, idx * 50)
        }
        galleryBuilt = true

        // Remove loading text after thumbnails load
        scope.launch { delay(2000); if (!isDestroyed && ver == galleryVersion) loadingTv.visibility = View.GONE }
    }

    /**
     * Show/hide existing cards based on filter — NO rebuild, NO re-download.
     */
    private fun filterCards(galleryView: View) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val countTv = galleryView.findViewById<TextView>(R.id.photo_count)
        val q = searchQuery.lowercase()
        var visibleCount = 0

        // Iterate all rows and their children
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val card = row.getChildAt(j)
                // Find the photo name from card's tag (Array: [name, shimmer])
                val name = (card.tag as? Array<*>)?.firstOrNull() as? String ?: continue
                val photo = photos.find { it.name == name } ?: continue
                val matchesSearch = q.isEmpty() || photo.name.lowercase().contains(q)
                val matchesType = when (galleryFilter) {
                    1 -> !photo.isVideo
                    2 -> photo.isVideo
                    else -> true
                }
                card.visibility = if (matchesSearch && matchesType) { visibleCount++; View.VISIBLE } else View.GONE
            }
            // Hide empty rows
            val hasVisible = (0 until row.childCount).any { row.getChildAt(it).visibility == View.VISIBLE }
            row.visibility = if (hasVisible) View.VISIBLE else View.GONE
        }
        countTv?.text = "$visibleCount"
    }

    private fun createPhotoCard(photo: Photo, isDark: Boolean, version: Int, index: Int = 0): LinearLayout {
        val card = layoutInflater.inflate(R.layout.item_photo_card, null) as LinearLayout
        card.background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 12f)
        (card.background as? GradientDrawable)?.setStroke(1, ThemeHelper.getSepColor(isDark))
        val thumb = card.findViewById<ImageView>(R.id.card_thumb)
        val thumbLoading = card.findViewById<ProgressBar>(R.id.card_thumb_loading)
        val playOverlay = card.findViewById<FrameLayout>(R.id.card_play_overlay)
        // Skeleton shimmer — pulsing placeholder until thumbnail loads.
        // Animator disimpan di tag card (Array), tidak menabrak tag name yang dipakai filterCards.
        val shimmer = android.animation.ObjectAnimator.ofFloat(thumbLoading, "alpha", 0.3f, 1f).apply {
            duration = 800; repeatMode = android.animation.ObjectAnimator.REVERSE; repeatCount = android.animation.ObjectAnimator.INFINITE
        }
        shimmer.start()
        card.tag = arrayOf(photo.name, shimmer)
        val nameTv = card.findViewById<TextView>(R.id.card_name); val dateTv = card.findViewById<TextView>(R.id.card_date)
        val linkBtn = card.findViewById<ImageView>(R.id.card_link_btn)
        nameTv.setTextColor(ThemeHelper.getLabelColor(isDark)); dateTv.setTextColor(ThemeHelper.getSecLabelColor(isDark)); linkBtn.setColorFilter(ThemeHelper.PRIMARY_CYAN)
        nameTv.text = photo.name; dateTv.text = formatDate(photo.date)
        if (photo.isVideo) playOverlay.visibility = View.VISIBLE
        linkBtn.setOnClickListener { com.tkjone.app.service.CustomTabLauncher.openUrl(this@MainActivity, AppConfig.photoLink(photo.name)) }
        card.setOnClickListener { navigateToPhoto(photo.name) }

        // Show placeholder immediately with photo name
        thumb.setBackgroundColor(ThemeHelper.getSepColor(isDark))

        // Instan dari cache bitmap in-memory bila sudah pernah didecode sesi ini
        val memBmp = cardBitmapCache[photo.name]
        if (memBmp != null && !memBmp.isRecycled) {
            thumb.setImageBitmap(memBmp); thumb.setBackgroundColor(Color.TRANSPARENT)
            thumbLoading?.visibility = View.GONE
            shimmer.cancel()
            return card
        }

        // Load thumbnail — staggered, satu per satu dari kiri ke kanan
        val job = scope.launch {
            try {
                var needDownload = true
                val cachedPath = ThumbnailCacheService.getCachedPathSync(this@MainActivity, photo.url, photo.date)
                if (cachedPath != null && version == galleryVersion && !isDestroyed) {
                    val bmp = ThumbnailCacheService.decodeBitmapOnIO(cachedPath, 250)
                    if (bmp != null && version == galleryVersion && !isDestroyed) {
                        thumb.setImageBitmap(bmp); thumb.setBackgroundColor(Color.TRANSPARENT); thumbLoading?.visibility = View.GONE
                        shimmer.cancel()
                        trimMemoryCache()
                        cardBitmapCache[photo.name] = bmp
                        needDownload = false // cache valid, no download needed
                    } else {
                        // Cache file corrupt — delete and re-download
                        try { java.io.File(cachedPath).delete() } catch (_: Exception) {}
                    }
                }
                // Download only if cache was missing or corrupt
                if (needDownload) {
                    // Wait while photo detail is open (paused)
                    while (thumbnailsPaused && !isDestroyed && version == galleryVersion) { delay(500) }
                    delay((index * 60).toLong())
                    val path = ThumbnailCacheService.cacheThumbnail(this@MainActivity, photo.url, version = photo.date, isVideo = photo.isVideo)
                    if (path != null && version == galleryVersion && !isDestroyed) {
                        val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, 250)
                        if (bmp != null && version == galleryVersion && !isDestroyed) {
                            thumb.setImageBitmap(bmp); thumb.setBackgroundColor(Color.TRANSPARENT)
                            shimmer.cancel()
                            trimMemoryCache()
                            cardBitmapCache[photo.name] = bmp
                        }
                    }
                }
                thumbLoading?.visibility = View.GONE
                shimmer.cancel()
            } catch (_: Exception) { thumbLoading?.visibility = View.GONE; shimmer.cancel() }
        }
        thumbnailJobs.add(job)
        return card
    }

    /**
     * Batasi bitmap cache in-memory agar tidak menumpuk → mencegah OOM
     * (penyebab force-close paling sering saat scroll galeri panjang).
     */
    private fun trimMemoryCache(maxEntries: Int = 48) {
        while (cardBitmapCache.size > maxEntries) {
            val oldest = cardBitmapCache.keys.first()
            try { cardBitmapCache.remove(oldest)?.recycle() } catch (_: Exception) {}
        }
    }

    private fun navigateToPhoto(name: String) {
        val match = photos.find { it.name.equals(name, ignoreCase = true) }
        if (match != null) {
            if (match.isVideo) showVideoPlayer(match, photos.indexOf(match))
            else showPhotoDetailScreen(match, photos.indexOf(match))
        } else DialogHelper.showToast(this, getString(R.string.toast_photo_not_found, name))
    }

    // ─── PHOTO DETAIL ───

    private var photoSwipeHelper: com.tkjone.app.service.PhotoSwipeHelper? = null

    private fun showPhotoDetailScreen(photo: Photo, initialIndex: Int) {
        thumbnailsPaused = true
        photoDetailJob?.cancel()
        try {
            val gv = cachedGalleryView ?: return
            val scrollView = gv.findViewById<ScrollView>(R.id.photo_scroll)
            galleryScrollY = scrollView?.scrollY ?: 0
        } catch (_: Exception) {}

        currentScreenTag = "photo_detail"

        if (photoSwipeHelper == null) {
            photoSwipeHelper = com.tkjone.app.service.PhotoSwipeHelper(
                activity = this,
                rootContainer = rootContainer,
                onBack = { photoSwipeHelper?.destroy(); photoSwipeHelper = null; showHomeScreen() },
                onDownload = { p -> handleDownloadClick(p) },
                onShare = { p ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppConfig.photoLink(p.name)) }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)))
                },
                onVideoOpen = { p, idx -> showVideoPlayer(p, idx) },
                getCachedBitmap = { name -> cardBitmapCache[name] },
                loadHighQuality = { p, imgView ->
                    photoDetailJob = scope.launch {
                        try {
                            val path = ThumbnailCacheService.cacheThumbnail(this@MainActivity, p.url, version = p.date)
                            if (path != null && !isDestroyed) {
                                if (p.url.lowercase().let { it.endsWith(".gif") || it.contains(".gif?") }) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        try {
                                            val drawable = withContext(Dispatchers.IO) { ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, Uri.parse(p.url))) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; decoder.isMutableRequired = true } }
                                            if (drawable != null && !isDestroyed) imgView.setImageDrawable(drawable)
                                        } catch (_: Exception) {
                                            val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, 1200); if (bmp != null && !isDestroyed) imgView.setImageBitmap(bmp)
                                        }
                                    } else { val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, 1200); if (bmp != null && !isDestroyed) imgView.setImageBitmap(bmp) }
                                } else { val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, 1200); if (bmp != null && !isDestroyed) imgView.setImageBitmap(bmp) }
                            }
                        } catch (e: Exception) { android.util.Log.e("PhotoDetail", "Load failed: ${p.url}", e) }
                    }
                },
            )
        }
        photoSwipeHelper!!.show(photo, initialIndex, photos)
    }

    // ─── VIDEO PLAYER ───

    private fun showVideoPlayer(photo: Photo, initialIndex: Int) {
        thumbnailsPaused = true // pause thumbnails while video is open
        photoDetailJob?.cancel()
        // Save gallery scroll
        try {
            val gv = cachedGalleryView ?: return
            val scrollView = gv.findViewById<ScrollView>(R.id.photo_scroll)
            galleryScrollY = scrollView?.scrollY ?: 0
        } catch (_: Exception) {}

        val v = layoutInflater.inflate(R.layout.screen_video_player, null) as ViewGroup
        v.setBackgroundColor(Color.BLACK)

        v.findViewById<ImageView>(R.id.video_back_btn).setOnClickListener { showHomeScreen() }
        v.findViewById<TextView>(R.id.video_title).text = getString(R.string.photo_name_format, photo.name)


        v.findViewById<ImageView>(R.id.video_download_btn).setOnClickListener { handleDownloadClick(photo) }
        v.findViewById<TextView>(R.id.video_photo_name).text = photo.name
        v.findViewById<TextView>(R.id.video_counter).text = getString(R.string.photo_counter_format, initialIndex + 1, photos.size)

        val videoView = v.findViewById<VideoView>(R.id.video_view)
        val loadingLayout = v.findViewById<LinearLayout>(R.id.video_loading)
        val loadingText = v.findViewById<TextView>(R.id.video_loading_text)

        // Set up video controller
        val controller = android.widget.MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)

        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            loadingLayout.visibility = View.GONE
            videoView.start()
        }

        videoView.setOnErrorListener { _, what, extra ->
            loadingText.text = getString(R.string.toast_video_failed)
            loadingLayout.postDelayed({ loadingLayout.visibility = View.GONE }, 2000)
            false
        }

        // Load video
        loadingLayout.visibility = View.VISIBLE
        videoView.setVideoURI(Uri.parse(photo.url))
        videoView.start()

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "video_player"
        animateScreenIn(v)
    }

    // ─── PROFILE ───

    private fun createProfileView(): View {
        val isDark = isDark
        val v = layoutInflater.inflate(R.layout.screen_profile, null) as ViewGroup; v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        makeFocusableOutside(v)
        v.findViewById<LinearLayout>(R.id.profile_header).setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        val avatarLetter = v.findViewById<TextView>(R.id.profile_avatar_letter); avatarLetter.text = (loggedInUser?.firstOrNull()?.uppercase() ?: "?"); avatarLetter.setTextColor(ThemeHelper.PRIMARY_CYAN); avatarLetter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ThemeHelper.ACCENT_BLUE and 0x1FFFFFFF or 0x20000000) }
        val profilePhotoPath = getSharedPreferences("tkjone_prefs", MODE_PRIVATE).getString("profile_photo", null)
        if (profilePhotoPath != null) { val photoIv = v.findViewById<ImageView>(R.id.profile_avatar_photo); scope.launch { try { val bmp = ThumbnailCacheService.decodeBitmapOnIO(profilePhotoPath, 200); if (bmp != null && !isDestroyed) { photoIv.setImageBitmap(bmp); photoIv.visibility = View.VISIBLE; avatarLetter.visibility = View.GONE } } catch (_: Exception) {} } }
        v.findViewById<ImageView>(R.id.profile_camera_badge).parent?.let { (it as? View)?.setOnClickListener { try { startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 1001) } catch (_: Exception) {} } }
        val nameTv = v.findViewById<TextView>(R.id.profile_name); nameTv.setTextColor(ThemeHelper.getLabelColor(isDark)); nameTv.text = loggedInUser ?: ""
        try { v.findViewById<View>(R.id.profile_theme_dropdown).visibility = View.GONE } catch (_: Exception) {}
        try { v.findViewById<View>(R.id.profile_layout_dropdown).visibility = View.GONE } catch (_: Exception) {}
        v.findViewById<LinearLayout>(R.id.profile_upload_tile).apply { background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }; setOnClickListener { com.tkjone.app.service.CustomTabLauncher.openUrl(this@MainActivity, AppConfig.UPLOAD_URL) } }
        v.findViewById<LinearLayout>(R.id.profile_github_tile).apply { background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }; setOnClickListener { com.tkjone.app.service.CustomTabLauncher.openUrl(this@MainActivity, AppConfig.GITHUB_AVATAR_URL.replace(".png", "")) } }; setOnLongClickListener { showDevPopup(); true } }
        v.findViewById<LinearLayout>(R.id.profile_logout_btn).apply { background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }; setOnClickListener { DialogHelper.showConfirmDialog(this@MainActivity, getString(R.string.dialog_logout_title), getString(R.string.dialog_logout_message), getString(R.string.dialog_logout_confirm), getString(R.string.dialog_cancel), ThemeHelper.ACCENT_RED) { handleLogout() } } }
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val versionTv = v.findViewById<TextView>(R.id.profile_version); versionTv.setTextColor(ThemeHelper.getLabelColor(isDark)); versionTv.text = pInfo?.versionName?.let { "v$it" } ?: ""
        return v
    }

    // ─── STORY ───

    private fun showStoryView() {
        if (photos.isEmpty()) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)); return }
        val v = layoutInflater.inflate(R.layout.screen_story, null) as ViewGroup
        val isDark = isDark; v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        v.findViewById<ImageView>(R.id.story_close_btn).setColorFilter(ThemeHelper.ACCENT_BLUE); v.findViewById<ImageView>(R.id.story_close_btn).setOnClickListener { showHomeScreen() }
        val webView = v.findViewById<android.webkit.WebView>(R.id.story_webview)
        webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; cacheMode = android.webkit.WebSettings.LOAD_DEFAULT; allowFileAccess = true; allowContentAccess = true; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW; databaseEnabled = true; setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false }
        webView.addJavascriptInterface(object { @android.webkit.JavascriptInterface fun postMessage(json: String) { try { val o = org.json.JSONObject(json); if (o.optString("command") == "closeStory") runOnUiThread { if (!isFinishing && !isDestroyed) showHomeScreen() } } catch (_: Exception) {} } }, "AndroidInterface")
        val recent = photos.sortedByDescending { it.date }.take(7)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) { if (isDestroyed || isFinishing || view == null) return; val arr = org.json.JSONArray(); for (p in recent) { val o = org.json.JSONObject(); o.put("name", p.name); o.put("date", p.date); o.put("url", p.url); o.put("type", if (p.isVideo) "video" else "image"); arr.put(o) }; view.evaluateJavascript("window.bootFromFlutter($arr)", null) }
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean { val url = request?.url?.toString() ?: return false; if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:///")) return false; try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}; return true }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() { override fun onPermissionRequest(request: android.webkit.PermissionRequest?) { request?.grant(request.resources) } }
        try { val html = assets.open("story/index.html").use { it.bufferedReader().readText() }; webView.loadDataWithBaseURL("file:///android_asset/story/", html, "text/html", "UTF-8", null) } catch (_: Exception) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)) }
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "story"; animateScreenIn(v)
    }

    // ─── ERROR ───

    private fun showErrorView() { destroyWebViews(); val isDark = isDark; val v = layoutInflater.inflate(R.layout.screen_error, null); v.setBackgroundColor(ThemeHelper.getBgColor(isDark)); v.findViewById<TextView>(R.id.error_message).setTextColor(ThemeHelper.getSecLabelColor(isDark)); v.findViewById<TextView>(R.id.error_retry_btn).background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); v.findViewById<TextView>(R.id.error_retry_btn).setOnClickListener { isLoading = true; showSplash(); scope.launch { initialize(); isLoading = false; showAppropriateScreen() } }; rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "error"; animateScreenIn(v) }

    // ─── DIALOGS ───

    private fun showUpdateDialog(info: UpdateInfo) {
        if (isFinishing || isDestroyed) return; val isDark = isDark
        val dialog = Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = ScrollView(this).apply { isFillViewport = true }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 20f); setPadding(dp(24), dp(24), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(ImageView(this).apply { setImageResource(R.drawable.download); setColorFilter(ThemeHelper.ACCENT_BLUE); layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }; background = GradientDrawable().apply { setColor(ThemeHelper.ACCENT_BLUE and 0x1FFFFFFF or 0x10000000); cornerRadius = dpFloat(18f) }; setPadding(dp(16), dp(16), dp(16), dp(16)) })
        c.addView(TextView(this).apply { text = getString(R.string.update_title); textSize = 18f; setTextColor(ThemeHelper.getLabelColor(isDark)); typeface = resources.getFont(R.font.torus_bold); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
        c.addView(TextView(this).apply { text = getString(R.string.update_version, info.tagVersion); textSize = 13f; setTextColor(ThemeHelper.ACCENT_BLUE); typeface = resources.getFont(R.font.torus_bold); gravity = Gravity.CENTER })
        c.addView(TextView(this).apply { text = getString(R.string.update_size, info.formattedSize); textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) })
        if (info.description.isNotEmpty()) { c.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = ThemeHelper.createRoundedBackground(ThemeHelper.getSepColor(isDark), 12f); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(12)) }; addView(TextView(this@MainActivity).apply { text = getString(R.string.update_changelog); textSize = 11f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); typeface = resources.getFont(R.font.torus_bold) }); addView(TextView(this@MainActivity).apply { text = info.description; textSize = 13f; setTextColor(ThemeHelper.getLabelColor(isDark)); setPadding(0, dp(4), 0, 0) }) }) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(TextView(this).apply { text = getString(R.string.update_later); textSize = 15f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { dialog.dismiss() } })
        btnRow.addView(TextView(this).apply { text = getString(R.string.update_now); textSize = 15f; setTextColor(Color.WHITE); typeface = resources.getFont(R.font.torus_bold); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); setOnClickListener { dialog.dismiss(); startUpdateDownload(info) } })
        c.addView(btnRow); sv.addView(c); dialog.setContentView(sv)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; dialog.show()
    }

    private fun showDevPopup() {
        if (isFinishing || isDestroyed) return; val isDark = isDark
        val dialog = Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = ScrollView(this).apply { isFillViewport = true }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 18f); setPadding(dp(20), dp(16), dp(20), dp(20)); gravity = Gravity.CENTER_HORIZONTAL }
        val avatarIv = ImageView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ThemeHelper.getSepColor(isDark)) }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        // Load GitHub profile picture — download bytes directly via OkHttp
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
                    val request = okhttp3.Request.Builder().url(AppConfig.GITHUB_AVATAR_URL).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } else null
                    } else null
                }
                if (bitmap != null && !isDestroyed) {
                    avatarIv.setImageBitmap(bitmap)
                } else if (!isDestroyed) {
                    avatarIv.setImageResource(R.drawable.person)
                }
            } catch (_: Exception) {
                if (!isDestroyed) avatarIv.setImageResource(R.drawable.person)
            }
        }
        c.addView(avatarIv)
        c.addView(TextView(this@MainActivity).apply { text = "ZHYLLAN FYLLLAH"; textSize = 20f; setTextColor(ThemeHelper.getLabelColor(isDark)); typeface = resources.getFont(R.font.torus_bold); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_subtitle); textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER })
        c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }; setBackgroundColor(ThemeHelper.getSepColor(isDark)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_license_section); textSize = 10f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); letterSpacing = 0.5f; setPadding(dp(8), dp(4), 0, dp(4)) })
        val licenses = listOf(Pair(getString(R.string.dev_license_mit), "LICENSE"), Pair(getString(R.string.dev_license_apache), "licenses/apache.txt"), Pair(getString(R.string.dev_license_flutter), "licenses/flutter.txt"))
        for ((name, assetPath) in licenses) { c.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(12)); isClickable = true; isFocusable = true; addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.document_text); setColorFilter(ThemeHelper.PRIMARY_CYAN); layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)); background = GradientDrawable().apply { setColor(ThemeHelper.PRIMARY_CYAN and 0x1FFFFFFF or 0x1F000000); cornerRadius = dpFloat(6f) }; setPadding(dp(4), dp(4), dp(4), dp(4)) }); addView(TextView(this@MainActivity).apply { text = name; textSize = 14f; setTextColor(ThemeHelper.getLabelColor(isDark)); setPadding(dp(10), 0, 0, 0) }); setOnClickListener { try { val content = assets.open(assetPath).use { it.bufferedReader().readText() }; DialogHelper.showInfoDialog(this@MainActivity, name, content) } catch (_: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.dev_license_not_found)) } } }); c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(ThemeHelper.getSepColor(isDark)) }) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_close); textSize = 15f; setTextColor(Color.WHITE); typeface = resources.getFont(R.font.torus_bold); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }; setOnClickListener { dialog.dismiss() } })
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_portfolio); textSize = 15f; setTextColor(Color.WHITE); typeface = resources.getFont(R.font.torus_bold); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_GREEN, 12f); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); setOnClickListener { com.tkjone.app.service.CustomTabLauncher.openUrl(this@MainActivity, AppConfig.PORTFOLIO_URL) } })
        c.addView(btnRow); sv.addView(c); dialog.setContentView(sv)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; dialog.show()
    }

    // ─── DOWNLOAD ───

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED } else { ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED }
    private fun handleDownloadClick(photo: Photo) { if (hasStoragePermission()) startDownloadWithProgress(photo) else DialogHelper.showConfirmDialog(this, getString(R.string.dialog_permission_title), getString(R.string.dialog_permission_message), getString(R.string.dialog_permission_grant), getString(R.string.dialog_cancel), ThemeHelper.ACCENT_BLUE) { pendingDownloadPhoto = photo; openPermissionSettings() } }
    private fun openPermissionSettings() { val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }; try { startActivityForResult(intent, PERMISSION_REQUEST_CODE) } catch (_: Exception) { try { startActivityForResult(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), PERMISSION_REQUEST_CODE) } catch (_: Exception) { DialogHelper.showToast(this, getString(R.string.toast_permission_needed)) } } }

    private fun startUpdateDownload(info: UpdateInfo) {
        val isDark = isDark
        val progressDialog = Dialog(this); progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20)); background = GradientDrawable().apply { setColor(ThemeHelper.getCardColor(isDark)); cornerRadius = dpFloat(16f) } }
        val titleText = TextView(this).apply { text = getString(R.string.dialog_download_progress, 0); textSize = 16f; setTextColor(ThemeHelper.getLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) } }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)); max = 100; progress = 0 }
        container.addView(titleText); container.addView(progressBar); progressDialog.setContentView(container); progressDialog.setCancelable(false)
        progressDialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; progressDialog.show()
        scope.launch {
            try {
                val filePath = UpdateService.downloadApk(this@MainActivity, info.apkUrl, info.title) { progress ->
                    val percent = (progress * 100).toInt()
                    runOnUiThread { try { if (progressDialog.isShowing) { progressBar.progress = percent; titleText.text = getString(R.string.dialog_download_progress, percent) } } catch (_: Exception) {} }
                }
                try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {}
                UpdateService.installApk(this@MainActivity, filePath)
            } catch (e: Exception) {
                try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {}
                DialogHelper.showToast(this@MainActivity, getString(R.string.toast_update_failed, e.message ?: ""))
            }
        }
    }

    private fun startDownloadWithProgress(photo: Photo) {
        DialogHelper.showToast(this, getString(R.string.toast_download_started)); val isDark = isDark
        val progressDialog = Dialog(this); progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20)); background = GradientDrawable().apply { setColor(ThemeHelper.getCardColor(isDark)); cornerRadius = dpFloat(16f) } }
        val titleText = TextView(this).apply { text = getString(R.string.download_enhancing); textSize = 16f; setTextColor(ThemeHelper.getLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) } }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)); max = 100; progress = 0 }
        container.addView(titleText); container.addView(progressBar); progressDialog.setContentView(container); progressDialog.setCancelable(false)
        progressDialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; progressDialog.show()
        scope.launch {
            try {
                // Step 1: Get cached file or download
                val cachedPath = ThumbnailCacheService.getCachedPathSync(this@MainActivity, photo.url, photo.date)
                val filePath = cachedPath ?: run {
                    runOnUiThread { titleText.text = getString(R.string.dialog_download_progress, 0) }
                    ThumbnailCacheService.cacheThumbnail(this@MainActivity, photo.url, version = photo.date, isVideo = photo.isVideo)
                }
                if (filePath == null) throw Exception("Gagal mendapatkan file foto")

                // Step 2: Decode and enhance with GPUImage
                runOnUiThread { titleText.text = getString(R.string.download_enhancing) }
                val original = ImageEnhancer.decodeForEnhance(filePath) ?: throw Exception("Gagal decode foto")
                val enhanced = ImageEnhancer.enhance(this@MainActivity, original)
                if (enhanced !== original) original.recycle()

                // Step 3: Save to DCIM/TKJ Photos
                runOnUiThread { titleText.text = getString(R.string.download_saving) }
                val savedPath = ImageEnhancer.saveToDcim(this@MainActivity, enhanced, photo.name)
                enhanced.recycle()

                if (savedPath != null) {
                    runOnUiThread { try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {} }
                    DialogHelper.showToast(this@MainActivity, getString(R.string.toast_download_complete))
                } else {
                    throw Exception("Gagal menyimpan foto")
                }
            } catch (e: Exception) {
                runOnUiThread { try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {} }
                DialogHelper.showToast(this@MainActivity, e.message ?: "Error")
            }
        }
    }

    // ─── HELPERS ───

    private fun formatDate(dateStr: String): String { return try { val parts = dateStr.split("-"); if (parts.size == 3) { val m = resources.getStringArray(R.array.months); "${parts[2].toInt()} ${m[parts[1].toInt() - 1]} ${parts[0]}" } else dateStr } catch (_: Exception) { dateStr } }
    // dp(), dpFloat(), hideKeyboardFrom(), makeFocusableOutside(), isDark — inherited from AppActivity
}
