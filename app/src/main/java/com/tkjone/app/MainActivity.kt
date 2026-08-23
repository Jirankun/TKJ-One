package com.tkjone.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tkjone.app.model.BannerAd
import com.tkjone.app.model.InfoMessage
import com.tkjone.app.model.Photo
import com.tkjone.app.service.AppThemeMode
import com.tkjone.app.service.ApiService
import com.tkjone.app.service.AuthService
import com.tkjone.app.service.CachePriority
import com.tkjone.app.service.GridLayout
import com.tkjone.app.service.SettingsService
import com.tkjone.app.service.ThumbnailCacheService
import com.tkjone.app.service.UpdateCheckResult
import com.tkjone.app.service.UpdateInfo
import com.tkjone.app.service.UpdateService
import com.tkjone.app.ui.DialogHelper
import com.tkjone.app.ui.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private lateinit var rootContainer: FrameLayout
    private var currentScreenTag: String? = null

    // State
    private var loggedInUser: String? = null
    private val photos = mutableListOf<Photo>()
    private val banners = mutableListOf<BannerAd>()
    private val infoMessages = mutableListOf<InfoMessage>()
    private var isOffline = false
    private var isLoading = true
    private var errorMessage: String? = null
    private var isMaintenanceShowing = false
    private var themeMode = AppThemeMode.DARK

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private var dataRefreshRunnable: Runnable? = null
    private var suspendCheckRunnable: Runnable? = null
    private var infoCheckRunnable: Runnable? = null
    private var bannerTimerRunnable: Runnable? = null

    // Gallery state
    private var searchQuery = ""
    private var isSearching = false
    private var gridLayoutMode = GridLayout.GRID
    private var initialPhotoName: String? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─── LIFECYCLE ───

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        rootContainer = findViewById(R.id.root_container)
        themeMode = SettingsService.getThemeMode(this)
        applyTheme()
        parseDeepLink(intent)
        showSplash()
        scope.launch { initialize() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDeepLink(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
                if (bitmap != null) {
                    val f = java.io.File(filesDir, "profile_photo.jpg")
                    f.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out) }
                    getSharedPreferences("tkjone_prefs", MODE_PRIVATE).edit().putString("profile_photo", f.absolutePath).apply()
                    DialogHelper.showToast(this, getString(R.string.profile_photo_pick))
                    showHomeScreen()
                }
            } catch (e: Exception) {
                DialogHelper.showToast(this, getString(R.string.profile_photo_error, e.message ?: ""))
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is EditText) {
                val rect = android.graphics.Rect()
                f.getWindowVisibleDisplayFrame(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(f.windowToken, 0)
                    f.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (loggedInUser != null && !isLoading) {
            scope.launch { fetchAllData(); fetchBanners() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimers()
        bannerTimerRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        ApiService.cancelAll()
        ThumbnailCacheService.cancelAll()
    }

    // ─── INIT ───

    private fun parseDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme ?: return
        val host = data.host
        val segs = data.pathSegments
        when {
            scheme == "tkjone" && host == "photo" && segs.isNotEmpty() -> initialPhotoName = segs[0]
            scheme == "tkjone" && segs.size >= 2 && segs[0] == "photo" -> initialPhotoName = segs[1]
            scheme == "https" && host == "tkj1.pages.dev" && segs.size >= 2 && segs[0] == "photo" -> initialPhotoName = segs[1]
            scheme == "https" && host == "tkj1.pages.dev" && segs.size == 1 && segs[0] != "photo" -> initialPhotoName = segs[0]
        }
    }

    private fun applyTheme() {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        ThemeHelper.applyStatusBarStyle(window, isDark)
        ThemeHelper.applyNavBarColor(window, isDark)
    }

    private suspend fun initialize() {
        loggedInUser = AuthService.getLoggedInUser(this)
        coroutineScope { launch { fetchAllData() }; launch { fetchBanners() } }
        isLoading = false
        showAppropriateScreen()
        if (loggedInUser != null) {
            startSuspendTimer(); startDataRefreshTimer()
            initialPhotoName?.let { n ->
                if (photos.isNotEmpty()) { initialPhotoName = null; navigateToPhoto(n) }
            }
            showInfoMessages(); checkForUpdate()
        }
        startInfoCheckTimer()
    }

    private fun showAppropriateScreen() {
        when {
            loggedInUser == null -> showLoginScreen()
            photos.isEmpty() && errorMessage != null -> showErrorView()
            else -> showHomeScreen()
        }
    }

    // ─── DATA FETCHING ───

    private suspend fun fetchAllData() {
        try {
            val r1 = withContext(Dispatchers.IO) { ApiService.fetchPhotos(this@MainActivity) }
            val r2 = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }
            if (r1.isSuccess) { photos.clear(); photos.addAll(r1.data!!); isOffline = false; errorMessage = null }
            else { val c = ApiService.loadCachedPhotos(this); if (!c.isNullOrEmpty()) { photos.clear(); photos.addAll(c); isOffline = true } else { photos.clear(); errorMessage = r1.error } }
            if (r2.isSuccess) { infoMessages.clear(); infoMessages.addAll(r2.data!!) }
            checkForMaintenance()
        } catch (e: Exception) { errorMessage = e.message }
    }

    private suspend fun fetchBanners() {
        try {
            val r = withContext(Dispatchers.IO) { ApiService.fetchBanners(this@MainActivity) }
            if (r.isSuccess) { banners.clear(); banners.addAll(r.data!!) }
        } catch (_: Exception) {}
    }

    // ─── TIMERS ───

    private fun startDataRefreshTimer() {
        dataRefreshRunnable?.let { handler.removeCallbacks(it) }
        dataRefreshRunnable = object : Runnable {
            override fun run() { scope.launch { fetchAllData(); fetchBanners() }; handler.postDelayed(this, 180_000L) }
        }
        handler.postDelayed(dataRefreshRunnable!!, 180_000L)
    }

    private fun startSuspendTimer() {
        suspendCheckRunnable?.let { handler.removeCallbacks(it) }
        suspendCheckRunnable = object : Runnable {
            override fun run() { scope.launch { checkUserSuspendStatus() }; handler.postDelayed(this, 300_000L) }
        }
        handler.postDelayed(suspendCheckRunnable!!, 300_000L)
    }

    private fun startInfoCheckTimer() {
        infoCheckRunnable?.let { handler.removeCallbacks(it) }
        infoCheckRunnable = object : Runnable {
            override fun run() {
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }
                        if (r.isSuccess) { infoMessages.clear(); infoMessages.addAll(r.data!!); checkForMaintenance() }
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 30_000L)
            }
        }
        handler.postDelayed(infoCheckRunnable!!, 30_000L)
    }

    private fun cancelTimers() {
        dataRefreshRunnable?.let { handler.removeCallbacks(it) }
        suspendCheckRunnable?.let { handler.removeCallbacks(it) }
        infoCheckRunnable?.let { handler.removeCallbacks(it) }
    }

    // ─── AUTH ───

    private suspend fun checkUserSuspendStatus() {
        val user = loggedInUser ?: return
        val reason = AuthService.checkUserStatus(this, user) ?: return
        cancelTimers(); forceLogout(reason)
    }

    private fun forceLogout(reason: String) {
        runOnUiThread { DialogHelper.showInfoDialog(this, getString(R.string.dialog_suspended_title), reason) { finish() } }
        loggedInUser = null
        scope.launch { AuthService.logout(this@MainActivity) }
    }

    private fun handleLogout() {
        cancelTimers()
        scope.launch {
            AuthService.logout(this@MainActivity)
            loggedInUser = null
            showLoginScreen()
            DialogHelper.showToast(this@MainActivity, getString(R.string.toast_logout))
        }
    }

    // ─── MAINTENANCE ───

    private fun checkForMaintenance() {
        if (isMaintenanceShowing) return
        val m = infoMessages.filter { it.isActive && it.category == "main" }
        if (m.isNotEmpty()) { isMaintenanceShowing = true; runOnUiThread { DialogHelper.showInfoDialog(this, getString(R.string.dialog_maintenance_title), m.first().message) { finish() } } }
        else isMaintenanceShowing = false
    }

    private fun showInfoMessages() {
        val active = infoMessages.filter { it.isActive && it.category == "info" }
        val prefs = getSharedPreferences("info_shown", MODE_PRIVATE)
        for (msg in active) {
            val key = "shown_${msg.message.hashCode()}"
            if (prefs.getBoolean(key, false)) continue
            prefs.edit().putBoolean(key, true).apply()
            runOnUiThread { DialogHelper.showInfoDialog(this, getString(R.string.dialog_info_title), msg.message) }
        }
    }

    private suspend fun checkForUpdate() {
        try {
            val r = UpdateService.checkForUpdate(this)
            if (r.hasUpdate) runOnUiThread { showUpdateDialog(r.updateInfo!!) }
        } catch (_: Exception) {}
    }

    // ─── SCREEN MANAGEMENT ───

    private fun switchScreen(layoutRes: Int, tag: String) {
        val view = layoutInflater.inflate(layoutRes, rootContainer, false)
        rootContainer.removeAllViews()
        rootContainer.addView(view)
        currentScreenTag = tag
    }

    // ─── SPLASH ───

    private fun showSplash() {
        val v = layoutInflater.inflate(R.layout.screen_splash, rootContainer, false)
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        v.findViewById<TextView>(R.id.splash_text).setTextColor(ThemeHelper.getSecLabelColor(isDark))
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "splash"
    }

    // ─── LOGIN ───

    private fun showLoginScreen() {
        val v = layoutInflater.inflate(R.layout.screen_login, rootContainer, false)
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        val logoBg = v.findViewById<View>(R.id.login_logo_bg)
        logoBg.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; colors = intArrayOf(ThemeHelper.PRIMARY_CYAN, ThemeHelper.PRIMARY_CYAN) }
        v.findViewById<ImageView>(R.id.login_logo_icon).setColorFilter(Color.WHITE)

        v.findViewById<TextView>(R.id.login_title).setTextColor(ThemeHelper.ACCENT_BLUE)
        v.findViewById<TextView>(R.id.login_subtitle).setTextColor(ThemeHelper.getSecLabelColor(isDark))

        val nameInput = v.findViewById<EditText>(R.id.name_input)
        nameInput.setTextColor(ThemeHelper.getLabelColor(isDark))
        nameInput.setHintTextColor(ThemeHelper.getSecLabelColor(isDark))
        nameInput.background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(10f); setStroke(1, ThemeHelper.getSepColor(isDark)) }

        val errorText = v.findViewById<TextView>(R.id.error_text)
        errorText.setTextColor(ThemeHelper.ACCENT_RED)

        val loginBtnContainer = v.findViewById<LinearLayout>(R.id.login_btn_container)
        loginBtnContainer.background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f)
        val loginBtnText = v.findViewById<TextView>(R.id.login_btn_text)
        val loginSpinner = v.findViewById<ProgressBar>(R.id.login_spinner)

        val hintCard = v.findViewById<LinearLayout>(R.id.hint_card)
        hintCard.background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, ThemeHelper.getSepColor(isDark)) }

        // Login handler
        var isLoggingIn = false
        loginBtnContainer.setOnClickListener {
            if (isLoggingIn) return@setOnClickListener
            val name = nameInput.text.toString()
            val error = AuthService.validateName(this, name)
            if (error != null) { errorText.text = error; errorText.visibility = View.VISIBLE; return@setOnClickListener }
            isLoggingIn = true; loginBtnText.visibility = View.GONE; loginSpinner.visibility = View.VISIBLE; errorText.visibility = View.GONE
            scope.launch {
                delay(400)
                val result = AuthService.login(this@MainActivity, name)
                if (result != null) {
                    loggedInUser = result; startSuspendTimer()
                    coroutineScope { launch { fetchAllData() }; launch { fetchBanners() } }
                    startDataRefreshTimer(); showInfoMessages(); checkForUpdate()
                    DialogHelper.showToast(this@MainActivity, getString(R.string.toast_welcome, result))
                    showHomeScreen()
                    initialPhotoName?.let { n -> if (photos.isNotEmpty()) { initialPhotoName = null; navigateToPhoto(n) } }
                } else {
                    errorText.text = AuthService.getLoginErrorMessage(this@MainActivity, name)
                    errorText.visibility = View.VISIBLE
                }
                isLoggingIn = false; loginBtnText.visibility = View.VISIBLE; loginSpinner.visibility = View.GONE
            }
        }

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (errorText.visibility == View.VISIBLE) errorText.visibility = View.GONE }
            override fun afterTextChanged(s: Editable?) {}
        })

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "login"
    }

    // ─── HOME ───

    private fun showHomeScreen() {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val v = layoutInflater.inflate(R.layout.screen_home, rootContainer, false)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        val bottomNav = v.findViewById<LinearLayout>(R.id.bottom_nav)
        bottomNav.setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        v.findViewById<View>(R.id.nav_separator_top).setBackgroundColor(ThemeHelper.getSepColor(isDark))
        v.findViewById<View>(R.id.nav_divider).setBackgroundColor(ThemeHelper.getSepColor(isDark))

        val galleryTab = v.findViewById<LinearLayout>(R.id.nav_gallery)
        val galleryIcon = v.findViewById<ImageView>(R.id.nav_gallery_icon)
        val galleryLabel = v.findViewById<TextView>(R.id.nav_gallery_label)
        val profileTab = v.findViewById<LinearLayout>(R.id.nav_profile)
        val profileIcon = v.findViewById<ImageView>(R.id.nav_profile_icon)
        val profileLabel = v.findViewById<TextView>(R.id.nav_profile_label)
        val contentFrame = v.findViewById<FrameLayout>(R.id.home_content)

        var activeTab = 0
        fun updateNav() {
            galleryIcon.setImageResource(if (activeTab == 0) R.drawable.photo else R.drawable.image)
            galleryLabel.setTextColor(if (activeTab == 0) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            galleryLabel.setTypeface(null, if (activeTab == 0) Typeface.BOLD else Typeface.NORMAL)
            profileIcon.setImageResource(if (activeTab == 1) R.drawable.person else R.drawable.person)
            profileLabel.setTextColor(if (activeTab == 1) ThemeHelper.ACCENT_BLUE else ThemeHelper.getSecLabelColor(isDark))
            profileLabel.setTypeface(null, if (activeTab == 1) Typeface.BOLD else Typeface.NORMAL)
        }

        fun showTab(tab: Int) {
            activeTab = tab; updateNav(); contentFrame.removeAllViews()
            when (tab) { 0 -> contentFrame.addView(createGalleryView()); 1 -> contentFrame.addView(createProfileView()) }
        }

        galleryTab.setOnClickListener { showTab(0) }
        profileTab.setOnClickListener { showTab(1) }

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "home"
        showTab(0)
    }

    // ─── GALLERY ───

    private fun createGalleryView(): View {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val v = layoutInflater.inflate(R.layout.screen_gallery, null as ViewGroup?)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        gridLayoutMode = SettingsService.getGridLayout(this)

        // Nav bar
        val title = v.findViewById<TextView>(R.id.gallery_title)
        title.setTextColor(ThemeHelper.ACCENT_BLUE)
        title.text = getString(R.string.gallery_filter_all)
        v.findViewById<ImageView>(R.id.gallery_search_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)

        // Search
        val searchContainer = v.findViewById<LinearLayout>(R.id.search_container)
        val searchInput = v.findViewById<EditText>(R.id.search_input)
        searchInput.setTextColor(ThemeHelper.getLabelColor(isDark))
        searchInput.setHintTextColor(ThemeHelper.getSecLabelColor(isDark))
        searchInput.background = GradientDrawable().apply { setColor(ThemeHelper.getBgColor(isDark)); cornerRadius = dpFloat(10f) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchQuery = s?.toString() ?: ""; refreshPhotoGrid(v) }
            override fun afterTextChanged(s: Editable?) {}
        })

        v.findViewById<ImageView>(R.id.gallery_search_btn).setOnClickListener {
            isSearching = !isSearching
            searchContainer.visibility = if (isSearching) View.VISIBLE else View.GONE
            if (isSearching) searchInput.requestFocus() else { searchQuery = ""; searchInput.text.clear() }
        }

        // Offline banner
        val offlineBanner = v.findViewById<LinearLayout>(R.id.offline_banner)
        if (isOffline) { offlineBanner.visibility = View.VISIBLE; offlineBanner.findViewById<TextView>(android.R.id.text1)?.setTextColor(Color.WHITE) }
            else offlineBanner.visibility = View.GONE

        // Photo count
        val countTv = v.findViewById<TextView>(R.id.photo_count)
        countTv.setTextColor(ThemeHelper.getSecLabelColor(isDark))

        // Photo grid
        val grid = v.findViewById<LinearLayout>(R.id.photo_grid)
        refreshPhotoGrid(v)

        // Banner carousel
        val bannerContainer = v.findViewById<android.widget.HorizontalScrollView>(R.id.banner_carousel)
        if (banners.isNotEmpty()) {
            bannerContainer.visibility = View.VISIBLE
            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (banner in banners) {
                val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(320), dp(80)).apply { setMargins(dp(4), 0, dp(4), 0) } }
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
                frame.addView(iv)
                frame.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(banner.link))) } catch (_: Exception) {} }
                inner.addView(frame)
                scope.launch {
                    try { val p = ThumbnailCacheService.cacheThumbnail(this@MainActivity, banner.url, version = banner.id, priority = CachePriority.HIGH); val b = android.graphics.BitmapFactory.decodeFile(p); if (b != null && !isDestroyed) iv.setImageBitmap(b) } catch (_: Exception) {}
                }
            }
            bannerContainer.addView(inner)
            bannerTimerRunnable?.let { handler.removeCallbacks(it) }
            bannerTimerRunnable = object : Runnable {
                private var page = 0
                override fun run() {
                    if (banners.size <= 1 || isDestroyed) return
                    page = (page + 1) % banners.size
                    bannerContainer.smoothScrollTo(page * dp(328), 0)
                    handler.postDelayed(this, 3000L)
                }
            }
            handler.postDelayed(bannerTimerRunnable!!, 3000L)
        }

        // Story FAB
        val fab = v.findViewById<FrameLayout>(R.id.story_fab)
        v.findViewById<View>(R.id.story_fab_bg).background = GradientDrawable().apply { colors = intArrayOf(0xFF6366F1.toInt(), 0xFF8B5CF6.toInt()); cornerRadius = dpFloat(16f) }
        fab.setOnClickListener { showStoryView() }

        return v
    }

    private fun getFilteredPhotos(): List<Photo> {
        val q = searchQuery.lowercase()
        return photos.filter { p -> p.name.lowercase().contains(q) }
    }

    private fun refreshPhotoGrid(galleryView: View) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val countTv = galleryView.findViewById<TextView>(R.id.photo_count)
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        grid.removeAllViews()
        val filtered = getFilteredPhotos()
        countTv?.text = "${filtered.size}"
        if (filtered.isEmpty()) {
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(64), 0, dp(64))
                addView(ImageView(this@MainActivity).apply { setImageResource(if (searchQuery.isNotEmpty()) R.drawable.close else R.drawable.image); setColorFilter(ThemeHelper.getSecLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)); gravity = Gravity.CENTER })
                addView(TextView(this@MainActivity).apply {
                    text = if (searchQuery.isNotEmpty()) getString(R.string.gallery_empty_search, searchQuery) else getString(R.string.gallery_empty)
                    textSize = 15f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
                })
            })
            return
        }
        var row: LinearLayout? = null
        for ((idx, photo) in filtered.withIndex()) {
            if (idx % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }; grid.addView(row) }
            val card = createPhotoCard(photo, isDark)
            row?.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(if (idx % 2 == 0) 0 else dp(6), 0, if (idx % 2 == 0) dp(6) else 0, 0)
            })
        }
    }

    private fun createPhotoCard(photo: Photo, isDark: Boolean): LinearLayout {
        val card = layoutInflater.inflate(R.layout.item_photo_card, null) as LinearLayout
        card.background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 12f)
        card.findViewWithTag<View>("")?.let {} // set stroke via code
        (card.background as? GradientDrawable)?.setStroke(1, ThemeHelper.getSepColor(isDark))

        val thumb = card.findViewById<ImageView>(R.id.card_thumb)
        val thumbContainer = card.findViewById<FrameLayout>(R.id.card_thumb_container)
        val playOverlay = card.findViewById<FrameLayout>(R.id.card_play_overlay)
        val nameTv = card.findViewById<TextView>(R.id.card_name)
        val dateTv = card.findViewById<TextView>(R.id.card_date)
        val linkBtn = card.findViewById<ImageView>(R.id.card_link_btn)

        nameTv.setTextColor(ThemeHelper.getLabelColor(isDark))
        dateTv.setTextColor(ThemeHelper.getSecLabelColor(isDark))
        linkBtn.setColorFilter(ThemeHelper.PRIMARY_CYAN)

        nameTv.text = photo.name
        dateTv.text = formatDate(photo.date)

        if (photo.isVideo) {
            playOverlay.visibility = View.VISIBLE
        }

        linkBtn.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.url))) } catch (_: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.toast_link_failed)) }
        }

        card.setOnClickListener { navigateToPhoto(photo.name) }

        scope.launch {
            try {
                val path = ThumbnailCacheService.cacheThumbnail(this@MainActivity, photo.url, version = photo.date, isVideo = photo.isVideo)
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                if (bmp != null && !isDestroyed) thumb.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }
        return card
    }

    private fun navigateToPhoto(name: String) {
        val match = photos.find { it.name.equals(name, ignoreCase = true) }
        if (match != null) showPhotoDetailScreen(match, photos.indexOf(match))
        else DialogHelper.showToast(this, getString(R.string.toast_photo_not_found, name))
    }

    // ─── PHOTO DETAIL ───

    private fun showPhotoDetailScreen(photo: Photo, initialIndex: Int) {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val v = layoutInflater.inflate(R.layout.screen_photo_detail, null) as ViewGroup
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        val navBar = v.findViewById<LinearLayout>(R.id.detail_nav_bar)
        navBar.setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        v.findViewById<TextView>(R.id.detail_title).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_title).text = getString(R.string.photo_name_format, photo.name)
        v.findViewById<TextView>(R.id.detail_counter).text = getString(R.string.photo_counter_format, initialIndex + 1, photos.size)
        v.findViewById<ImageView>(R.id.detail_back_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)
        v.findViewById<ImageView>(R.id.detail_link_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)

        v.findViewById<ImageView>(R.id.detail_back_btn).setOnClickListener { showHomeScreen() }
        v.findViewById<ImageView>(R.id.detail_link_btn).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.url))) } catch (_: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.toast_link_failed2)) }
        }

        // Media type badge
        val badge = v.findViewById<TextView>(R.id.detail_media_badge)
        val (badgeText, badgeColor) = if (photo.isVideo) getString(R.string.type_video) to ThemeHelper.ACCENT_YELLOW else getString(R.string.type_photo) to ThemeHelper.ACCENT_BLUE
        badge.text = badgeText; badge.setTextColor(badgeColor)
        badge.background = GradientDrawable().apply { setColor((badgeColor and 0x1FFFFFFF) or 0x15000000); cornerRadius = dpFloat(12f); setStroke(1, (badgeColor and 0x3FFFFFFF) or 0x30000000) }

        v.findViewById<TextView>(R.id.detail_photo_name).setTextColor(ThemeHelper.getLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_photo_name).text = getString(R.string.photo_name_format, photo.name)
        v.findViewById<TextView>(R.id.detail_date).setTextColor(ThemeHelper.getSecLabelColor(isDark))
        v.findViewById<TextView>(R.id.detail_date).text = photo.date
        v.findViewById<TextView>(R.id.detail_date).compoundDrawablePadding = dp(6)

        // Video play
        if (photo.isVideo) v.findViewById<FrameLayout>(R.id.detail_play_overlay).visibility = View.VISIBLE

        // Footer colors
        val footer = v.findViewById<LinearLayout>(R.id.detail_footer)
        footer.setBackgroundColor(ThemeHelper.getCardColor(isDark))

        // Load image
        val imgView = v.findViewById<ImageView>(R.id.detail_image)
        scope.launch {
            try {
                val p = ThumbnailCacheService.getCachedPath(this@MainActivity, photo.url, photo.date)
                    ?: ThumbnailCacheService.cacheThumbnail(this@MainActivity, photo.url, version = photo.date)
                val bmp = android.graphics.BitmapFactory.decodeFile(p)
                if (bmp != null && !isDestroyed) imgView.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "photo_detail"
    }

    // ─── PROFILE ───

    private fun createProfileView(): View {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val v = layoutInflater.inflate(R.layout.screen_profile, null) as ViewGroup
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        val header = v.findViewById<LinearLayout>(R.id.profile_header)
        header.setBackgroundColor(ThemeHelper.getBg2Color(isDark))

        val avatarLetter = v.findViewById<TextView>(R.id.profile_avatar_letter)
        avatarLetter.text = (loggedInUser?.firstOrNull()?.uppercase() ?: "?")
        avatarLetter.setTextColor(ThemeHelper.PRIMARY_CYAN)
        avatarLetter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ThemeHelper.ACCENT_BLUE and 0x1FFFFFFF or 0x20000000) }

        val profilePhotoPath = getSharedPreferences("tkjone_prefs", MODE_PRIVATE).getString("profile_photo", null)
        if (profilePhotoPath != null) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(profilePhotoPath)
                if (bmp != null) {
                    val photoIv = v.findViewById<ImageView>(R.id.profile_avatar_photo)
                    photoIv.setImageBitmap(bmp); photoIv.visibility = View.VISIBLE; avatarLetter.visibility = View.GONE
                }
            } catch (_: Exception) {}
        }

        // Avatar click - find the parent FrameLayout of camera badge
        val cameraBadgeParent = v.findViewById<ImageView>(R.id.profile_camera_badge).parent as? View
        cameraBadgeParent?.setOnClickListener {
            try { startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 1001) } catch (_: Exception) {}
        }

        val nameTv = v.findViewById<TextView>(R.id.profile_name)
        nameTv.setTextColor(ThemeHelper.getLabelColor(isDark))
        nameTv.text = loggedInUser ?: ""

        // Theme dropdown
        setupDropdown(v, R.id.profile_theme_header, R.id.profile_theme_chevron, R.id.profile_theme_options, isDark,
            listOf(getString(R.string.profile_theme_dark) to AppThemeMode.DARK, getString(R.string.profile_theme_light) to AppThemeMode.LIGHT, getString(R.string.profile_theme_auto) to AppThemeMode.AUTO)
        ) { mode -> themeMode = mode; SettingsService.setThemeMode(this, mode); applyTheme(); showHomeScreen() }

        // Layout dropdown
        setupDropdown(v, R.id.profile_layout_header, R.id.profile_layout_chevron, R.id.profile_layout_options, isDark,
            listOf(getString(R.string.profile_layout_grid) to GridLayout.GRID, getString(R.string.profile_layout_staggered) to GridLayout.STAGGERED)
        ) { layout -> gridLayoutMode = layout; SettingsService.setGridLayout(this, layout) }

        // Upload
        v.findViewById<LinearLayout>(R.id.profile_upload_tile).apply {
            background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }
            setOnClickListener { showUploadScreen() }
        }

        // GitHub
        v.findViewById<LinearLayout>(R.id.profile_github_tile).apply {
            background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.github.com/Jirankun"))) } catch (_: Exception) {} }
            setOnLongClickListener { showDevPopup(); true }
        }

        // Logout
        v.findViewById<LinearLayout>(R.id.profile_logout_btn).apply {
            background = GradientDrawable().apply { setColor(ThemeHelper.getBg2Color(isDark)); cornerRadius = dpFloat(12f); setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt())) }
            setOnClickListener {
                DialogHelper.showConfirmDialog(this@MainActivity, getString(R.string.dialog_logout_title), getString(R.string.dialog_logout_message),
                    getString(R.string.dialog_logout_confirm), getString(R.string.dialog_cancel), ThemeHelper.ACCENT_RED) { handleLogout() }
            }
        }

        // Version
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val versionTv = v.findViewById<TextView>(R.id.profile_version)
        versionTv.setTextColor(ThemeHelper.getLabelColor(isDark))
        versionTv.text = pInfo?.versionName?.let { "v$it" } ?: ""

        return v
    }

    private fun <T> setupDropdown(parent: View, headerId: Int, chevronId: Int, optionsId: Int, isDark: Boolean,
                                   options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
        val header = parent.findViewById<LinearLayout>(headerId)
        val chevron = parent.findViewById<ImageView>(chevronId)
        val optionsContainer = parent.findViewById<LinearLayout>(optionsId)
        header.background = ThemeHelper.createRoundedBackground(ThemeHelper.getCardColor(isDark), 16f)
        header.findViewById<TextView>(android.R.id.text1)?.setTextColor(ThemeHelper.getLabelColor(isDark))
        var expanded = false
        header.setOnClickListener {
            expanded = !expanded
            optionsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
        }
        for ((label, value) in options) {
            val opt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true; isFocusable = true }
            opt.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(ThemeHelper.getLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            opt.addView(TextView(this).apply { text = "\u25C9"; textSize = 16f; setTextColor(ThemeHelper.PRIMARY_CYAN) })
            opt.setOnClickListener { onSelect(value); expanded = false; optionsContainer.visibility = View.GONE; chevron.rotation = 0f }
            optionsContainer.addView(opt)
        }
    }

    // ─── UPLOAD ───

    private fun showUploadScreen() {
        val v = layoutInflater.inflate(R.layout.screen_upload, null) as ViewGroup
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        v.findViewById<ImageView>(R.id.upload_back_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)
        v.findViewById<ImageView>(R.id.upload_back_btn).setOnClickListener { showHomeScreen() }

        val webView = v.findViewById<WebView>(R.id.upload_webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.databaseEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://wa.me/") || url.startsWith("whatsapp://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun postMessage(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }, "FlutterUploadHandler")

        try {
            val html = assets.open("upload/index.html").use { it.bufferedReader().readText() }
            webView.loadDataWithBaseURL("file:///android_asset/upload/", html, "text/html", "UTF-8", null)
        } catch (_: Exception) { DialogHelper.showToast(this, getString(R.string.toast_upload_hint)) }

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "upload"
    }

    // ─── STORY ───

    private fun showStoryView() {
        if (photos.isEmpty()) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)); return }
        val v = layoutInflater.inflate(R.layout.screen_story, null) as ViewGroup
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))

        v.findViewById<ImageView>(R.id.story_close_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)
        v.findViewById<ImageView>(R.id.story_close_btn).setOnClickListener { showHomeScreen() }

        val webView = v.findViewById<WebView>(R.id.story_webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun postMessage(json: String) {
                try { val o = org.json.JSONObject(json); if (o.optString("command") == "closeStory") runOnUiThread { showHomeScreen() } } catch (_: Exception) {}
            }
        }, "AndroidInterface")

        val recent = photos.sortedByDescending { it.date }.take(7)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val arr = org.json.JSONArray()
                for (p in recent) { val o = org.json.JSONObject(); o.put("name", p.name); o.put("date", p.date); o.put("url", p.url); o.put("type", if (p.isVideo) "video" else "image"); arr.put(o) }
                view?.evaluateJavascript("window.bootFromFlutter($arr)", null)
            }
        }

        try {
            val html = assets.open("story/index.html").use { it.bufferedReader().readText() }
            webView.loadDataWithBaseURL("file:///android_asset/story/", html, "text/html", "UTF-8", null)
        } catch (_: Exception) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)) }

        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "story"
    }

    // ─── ERROR VIEW ───

    private fun showErrorView() {
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val v = layoutInflater.inflate(R.layout.screen_error, null)
        v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        v.findViewById<TextView>(R.id.error_message).setTextColor(ThemeHelper.getSecLabelColor(isDark))
        v.findViewById<TextView>(R.id.error_retry_btn).background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f)
        v.findViewById<TextView>(R.id.error_retry_btn).setOnClickListener {
            isLoading = true; showSplash()
            scope.launch { initialize(); isLoading = false; showAppropriateScreen() }
        }
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "error"
    }

    // ─── DIALOGS ───

    private fun showUpdateDialog(info: UpdateInfo) {
        if (isFinishing || isDestroyed) return
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val dialog = Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sv = ScrollView(this).apply { isFillViewport = true }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 20f); setPadding(dp(24), dp(24), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }

        // Icon
        c.addView(ImageView(this).apply { setImageResource(R.drawable.download); setColorFilter(ThemeHelper.ACCENT_BLUE); layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }; background = GradientDrawable().apply { setColor(ThemeHelper.ACCENT_BLUE and 0x1FFFFFFF or 0x10000000); cornerRadius = dpFloat(18f) }; setPadding(dp(16), dp(16), dp(16), dp(16)) })

        c.addView(TextView(this).apply { text = getString(R.string.update_title); textSize = 18f; setTextColor(ThemeHelper.getLabelColor(isDark)); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
        c.addView(TextView(this).apply { text = getString(R.string.update_version, info.tagVersion); textSize = 13f; setTextColor(ThemeHelper.ACCENT_BLUE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER })
        c.addView(TextView(this).apply { text = getString(R.string.update_size, info.formattedSize); textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) })

        if (info.description.isNotEmpty()) {
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = ThemeHelper.createRoundedBackground(ThemeHelper.getSepColor(isDark), 12f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(12)) }
                addView(TextView(this@MainActivity).apply { text = getString(R.string.update_changelog); textSize = 11f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); setTypeface(null, Typeface.BOLD) })
                addView(TextView(this@MainActivity).apply { text = info.description; textSize = 13f; setTextColor(ThemeHelper.getLabelColor(isDark)); setPadding(0, dp(4), 0, 0) })
            })
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(TextView(this).apply { text = getString(R.string.update_later); textSize = 15f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { dialog.dismiss() } })
        btnRow.addView(TextView(this).apply { text = getString(R.string.update_now); textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); setOnClickListener { dialog.dismiss(); scope.launch { try { val p = UpdateService.downloadApk(this@MainActivity, info.apkUrl, info.title); UpdateService.installApk(this@MainActivity, p) } catch (e: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.toast_update_failed, e.message ?: "")) } } } })
        c.addView(btnRow)
        sv.addView(c); dialog.setContentView(sv)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }
        dialog.show()
    }

    private fun showDevPopup() {
        if (isFinishing || isDestroyed) return
        val isDark = ThemeHelper.isDarkMode(this, themeMode)
        val dialog = Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = ScrollView(this).apply { isFillViewport = true }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 18f); setPadding(dp(20), dp(16), dp(20), dp(20)); gravity = Gravity.CENTER_HORIZONTAL }

        c.addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.person); setColorFilter(ThemeHelper.ACCENT_BLUE); layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)).apply { gravity = Gravity.CENTER_HORIZONTAL }; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ThemeHelper.getSepColor(isDark)) }; setPadding(dp(30), dp(30), dp(30), dp(30)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.app_name); textSize = 20f; setTextColor(ThemeHelper.getLabelColor(isDark)); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_subtitle); textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER })
        c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }; setBackgroundColor(ThemeHelper.getSepColor(isDark)) })

        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_license_section); textSize = 10f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); letterSpacing = 0.5f; setPadding(dp(8), dp(4), 0, dp(4)) })

        val licenses = listOf(Pair(getString(R.string.dev_license_mit), "LICENSE"), Pair(getString(R.string.dev_license_apache), "licenses/apache.txt"), Pair(getString(R.string.dev_license_flutter), "licenses/flutter.txt"))
        for ((name, assetPath) in licenses) {
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(12)); isClickable = true; isFocusable = true
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.document_text); setColorFilter(ThemeHelper.PRIMARY_CYAN); layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)); background = GradientDrawable().apply { setColor(ThemeHelper.PRIMARY_CYAN and 0x1FFFFFFF or 0x1F000000); cornerRadius = dpFloat(6f) }; setPadding(dp(4), dp(4), dp(4), dp(4)) })
                addView(TextView(this@MainActivity).apply { text = name; textSize = 14f; setTextColor(ThemeHelper.getLabelColor(isDark)); setPadding(dp(10), 0, 0, 0) })
                setOnClickListener {
                    try { val content = assets.open(assetPath).use { it.bufferedReader().readText() }; DialogHelper.showInfoDialog(this@MainActivity, name, content) }
                    catch (_: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.dev_license_not_found)) }
                }
            })
            c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(ThemeHelper.getSepColor(isDark)) })
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_close); textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { dialog.dismiss() } })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 0) })
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_portfolio); textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_GREEN, 12f); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jirankun.github.io/portofoliozhyllan"))) } catch (_: Exception) {} } })
        c.addView(btnRow)

        sv.addView(c); dialog.setContentView(sv)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }
        dialog.show()
    }

    // ─── HELPERS ───

    private fun formatDate(dateStr: String): String {
        return try {
            val parts = dateStr.split("-")
            if (parts.size == 3) { val m = resources.getStringArray(R.array.months); "${parts[2].toInt()} ${m[parts[1].toInt() - 1]} ${parts[0]}" } else dateStr
        } catch (_: Exception) { dateStr }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float): Float = value * resources.displayMetrics.density
}
