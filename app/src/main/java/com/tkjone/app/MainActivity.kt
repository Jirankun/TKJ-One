package com.tkjone.app
import com.tkjone.app.config.AppConfig

import android.Manifest
import android.app.Dialog
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.tkjone.app.model.InfoMessage
import com.tkjone.app.model.Photo
import com.tkjone.app.service.AnalyticsService
import com.tkjone.app.service.ApiService
import com.tkjone.app.service.AuthService
import com.tkjone.app.service.CustomTabLauncher
import com.tkjone.app.service.DownloadService
import com.tkjone.app.service.ProfileImageLoader
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
import java.security.MessageDigest

class MainActivity : com.tkjone.app.ui.AppActivity() {

    private companion object {
        /** Batas decode gambar clean di layar detail — cukup untuk layar HP, hemat RAM. */
        const val CLEAN_DETAIL_MAX_DIMENSION = 2048

        /** Milestone scroll galeri yang dikirim ke analytics (sekali per milestone). */
        val SCROLL_MILESTONES = intArrayOf(25, 50, 75, 90, 100)
    }

    private lateinit var rootContainer: FrameLayout
    private var currentScreenTag: String? = null

    // State
    private var loggedInUser: String? = null
    private val photos = mutableListOf<Photo>()
    private val infoMessages = mutableListOf<InfoMessage>()
    private var isOffline = false
    private var isCachedRefreshFailed = false
    private var isLoading = true
    private var errorMessage: String? = null
    private var isMaintenanceShowing = false

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private var suspendCheckRunnable: Runnable? = null
    private var infoCheckRunnable: Runnable? = null

    // Gallery state
    private var searchQuery = ""
    private var isSearching = false
    private var initialPhotoName: String? = null
    private var galleryScrollY = 0
    private var galleryFilter = 0 // 0=all, 1=photo, 2=video
    private val FILTER_PREF_KEY = "gallery_filter"
    private val cardBitmapCache = mutableMapOf<String, Bitmap>() // stable URL hash -> bitmap
    private val galleryCardViews = linkedMapOf<String, LinearLayout>()
    private var activeFilterPopup: android.widget.PopupMenu? = null
    private var photosChangedOnLastFetch = false
    private var galleryBuilt = false // track if grid already built
    private var galleryItems: List<Photo> = emptyList()
    private var galleryRenderedCount = 0
    private var isAppendingGalleryPage = false
    private val galleryPageSize = 30
    private var photoDetailJob: Job? = null // cancel when navigating to new photo
    private var lastResumeTime = 0L // guard against rapid onResume calls
    private var backgroundedAt = 0L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingDownloadPhoto: Photo? = null
    private lateinit var mediaPermissionLauncher: ActivityResultLauncher<Array<String>>

    private var galleryVersion = 0
    private val loggedScrollMilestones = mutableSetOf<Int>()
    private var storyOpenedAt = 0L
    private var uploadTabOpenedAt = 0L
    private var uploadRefreshPending = false
    private var thumbnailJobs = mutableListOf<Job>()
    private var thumbnailsPaused = false // pause thumbnails when photo detail is open

    // Cached views for nav switching (no rebuild)
    private var cachedGalleryView: View? = null
    private var cachedProfileView: View? = null
    private var cachedHomeView: View? = null
    private var selectedHomeTab = 0

    // ─── LIFECYCLE ───

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val photo = pendingDownloadPhoto
            pendingDownloadPhoto = null
            if (hasStoragePermission()) {
                photo?.let(::startDownloadWithProgress)
            } else if (photo != null) {
                DialogHelper.showToast(this, getString(R.string.toast_permission_photo_video))
            }
        }
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
        requestMediaPermissionsIfNeeded()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentScreenTag == "home" && selectedHomeTab == 0 && isSearching) {
            cachedGalleryView?.findViewById<ImageView>(R.id.gallery_search_btn)?.performClick()
            return
        }
        when (currentScreenTag) {
            "story" -> { destroyWebViews(); showHomeScreen() }
            "photo_detail" -> closePhotoDetail()
            "upload" -> showHomeScreen()
            "error" -> { /* do nothing, error screen has retry button */ }
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDeepLink(intent)
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
        refreshAfterUploadIfNeeded()
        // Lanjutkan video dari posisi yang sama saat app ditinggalkan (bukan dari awal)
        photoSwipeHelper?.resumeFromBackground()
        val now = System.currentTimeMillis()
        val backgroundDuration = if (backgroundedAt == 0L) 0L else now - backgroundedAt
        backgroundedAt = 0L
        if (loggedInUser != null && !isLoading && backgroundDuration >= 5 * 60 * 1000L && now - lastResumeTime > 3000) {
            lastResumeTime = now
            scope.launch {
                if (!isDestroyed && !isFinishing) {
                    fetchAllData()
                    if (!isDestroyed && !isFinishing && currentScreenTag == "home") {
                        if (photos.isEmpty() && errorMessage != null) {
                            showErrorView()
                        } else if (selectedHomeTab == 0 && photosChangedOnLastFetch) {
                            cachedGalleryView?.let { refreshPhotoGrid(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimers()
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear()
        photoDetailJob?.cancel()
        photoSwipeHelper?.destroy(); photoSwipeHelper = null
        destroyWebViews()
        // Recycle bitmap cache — mencegah memory leak yang berujung OOM/force close
        cardBitmapCache.clear()
        cachedHomeView = null; cachedGalleryView = null; cachedProfileView = null
        scope.cancel()
        ApiService.cancelAll(); ThumbnailCacheService.cancelAll()
    }

    override fun onPause() {
        backgroundedAt = System.currentTimeMillis()
        photoSwipeHelper?.pauseForBackground()
        super.onPause()
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
        // Pilih layar setelah respons server diterima agar error koneksi tidak menampilkan home kosong.
        try { fetchAllData() } catch (_: Exception) {}
        isLoading = false
        showAppropriateScreen()
        if (!isDestroyed && !isFinishing) {
            if (photos.isEmpty() && errorMessage != null) showErrorView()
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
        photosChangedOnLastFetch = false
        try {
            val r1 = withContext(Dispatchers.IO) { ApiService.fetchPhotos(this@MainActivity) }
            val r2 = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }
            if (r1.isSuccess) {
                val incomingPhotos = r1.data.orEmpty()
                photosChangedOnLastFetch = photoCatalogHash(photos) != photoCatalogHash(incomingPhotos)
                if (photosChangedOnLastFetch) {
                    photos.clear()
                    photos.addAll(incomingPhotos)
                    ThumbnailCacheService.prefetchThumbnails(this@MainActivity, photos.toList())
                }
                // Data dari cache/database artinya server tidak terjangkau
                isOffline = r1.fromCache || !hasInternetConnection()
                isCachedRefreshFailed = r1.fromCache
                errorMessage = null
            } else {
                photos.clear()
                isOffline = !hasInternetConnection()
                isCachedRefreshFailed = false
                errorMessage = r1.error ?: getString(R.string.error_network)
            }
            infoMessages.clear()
            if (r2.isSuccess) infoMessages.addAll(r2.data.orEmpty())
            updateGalleryStatusBanner()
            checkForMaintenance()
        } catch (e: Exception) {
            photos.clear()
            infoMessages.clear()
            isOffline = !hasInternetConnection()
            isCachedRefreshFailed = false
            errorMessage = e.message ?: getString(R.string.error_network)
            updateGalleryStatusBanner()
        }
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateGalleryStatusBanner() {
        val gallery = cachedGalleryView ?: return
        val banner = gallery.findViewById<LinearLayout>(R.id.offline_banner) ?: return
        val message = banner.getChildAt(1) as? TextView ?: return
        val showBanner = isOffline || isCachedRefreshFailed
        banner.visibility = if (showBanner) View.VISIBLE else View.GONE
        message.setText(if (isOffline) R.string.gallery_offline else R.string.gallery_cached_refresh_failed)
    }

    // ─── TIMERS ───

    private fun startSuspendTimer() { suspendCheckRunnable?.let { handler.removeCallbacks(it) }; suspendCheckRunnable = object : Runnable { override fun run() { if (isDestroyed || isFinishing) return; scope.launch { checkUserSuspendStatus() }; handler.postDelayed(this, 300_000L) } }; handler.postDelayed(suspendCheckRunnable!!, 300_000L) }
    private fun startInfoCheckTimer() { infoCheckRunnable?.let { handler.removeCallbacks(it) }; infoCheckRunnable = object : Runnable { override fun run() { if (isDestroyed || isFinishing) return; scope.launch { try { val r = withContext(Dispatchers.IO) { ApiService.fetchInfo(this@MainActivity) }; if (r.isSuccess && !isDestroyed) { infoMessages.clear(); infoMessages.addAll(r.data!!); checkForMaintenance() } } catch (_: Exception) {} }; handler.postDelayed(this, 30_000L) } }; handler.postDelayed(infoCheckRunnable!!, 30_000L) }
    private fun cancelTimers() { suspendCheckRunnable?.let { handler.removeCallbacks(it) }; infoCheckRunnable?.let { handler.removeCallbacks(it) } }

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
        destroyWebViews()
        val v = ComposeView(this).apply {
            setContent {
                com.tkjone.app.ui.ComposeLoginScreen { enteredName ->
                    val validationError = AuthService.validateName(this@MainActivity, enteredName)
                    if (validationError != null) return@ComposeLoginScreen validationError

                    val loginError = AuthService.getLoginErrorMessage(this@MainActivity, enteredName)
                    if (loginError.isNotEmpty()) return@ComposeLoginScreen loginError

                    val user = AuthService.login(this@MainActivity, enteredName)
                        ?: return@ComposeLoginScreen getString(R.string.login_error_generic)
                    loggedInUser = user
                    fetchAllData()
                    isLoading = false
                    if (photos.isEmpty() && errorMessage != null) showErrorView() else showHomeScreen()
                    null
                }
            }
        }
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "login"
        AnalyticsService.screen("login")
    }

    // ─── HOME ───

    private fun showHomeScreen() {
        if (currentScreenTag == "story" && storyOpenedAt > 0L) {
            AnalyticsService.storyClosed(System.currentTimeMillis() - storyOpenedAt)
            storyOpenedAt = 0L
        }
        thumbnailsPaused = false // resume thumbnails
        ThumbnailCacheService.setPrefetchPaused(false)
        destroyWebViews(); applyTheme()
        cachedHomeView = null
        val home = ComposeView(this).apply {
            setContent {
                com.tkjone.app.ui.ComposeHomeScreen(
                    selectedTab = selectedHomeTab,
                    onTabSelected = { tab ->
                        selectedHomeTab = tab
                        AnalyticsService.tabSelected(if (tab == 0) "gallery" else "profile")
                        invalidateHomeContent(tab)
                    },
                    contentView = { tab ->
                        when (tab) {
                            0 -> cachedGalleryView ?: createGalleryView().also { cachedGalleryView = it }
                            else -> cachedProfileView ?: createProfileView().also { cachedProfileView = it }
                        }
                    }
                )
            }
        }
        rootContainer.removeAllViews()
        rootContainer.addView(home)
        currentScreenTag = "home"
        AnalyticsService.screen("home")
        animateScreenIn(home)
        if (galleryScrollY > 0) {
            home.post {
                cachedGalleryView?.findViewById<ScrollView>(R.id.photo_scroll)?.scrollTo(0, galleryScrollY)
            }
        }
    }

    private fun invalidateHomeContent(tab: Int) {
        if (tab == 1) cachedProfileView?.let { cachedProfileView = null }
    }

    // ─── GALLERY ───

    private fun createGalleryView(): View {
        val isDark = isDark
        val v = layoutInflater.inflate(R.layout.screen_gallery, null as ViewGroup?)
        v.setBackgroundColor(Color.TRANSPARENT)
        makeFocusableOutside(v)
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear(); galleryVersion++
        galleryCardViews.clear(); galleryBuilt = false

        // Load saved filter preference
        galleryFilter = getSharedPreferences("tkjone_prefs", MODE_PRIVATE).getInt(FILTER_PREF_KEY, 0)
        val title = v.findViewById<TextView>(R.id.gallery_title); title.setTextColor(ThemeHelper.ACCENT_BLUE)
        title.text = when (galleryFilter) { 1 -> getString(R.string.filter_photo); 2 -> getString(R.string.filter_video); else -> getString(R.string.gallery_filter_all) }
        v.findViewById<ImageView>(R.id.gallery_search_btn).setColorFilter(ThemeHelper.ACCENT_BLUE)
        // Filter dropdown
        val filterBtn = v.findViewById<LinearLayout>(R.id.gallery_filter_btn)
        filterBtn?.setOnClickListener { showFilterDropdown(title) }
        val searchContainer = v.findViewById<LinearLayout>(R.id.search_container)
        val searchInput = v.findViewById<EditText>(R.id.search_input)
        searchInput.setTextColor(ThemeHelper.getLabelColor(isDark))
        searchInput.setHintTextColor(ThemeHelper.getSecLabelColor(isDark))
        // Kotak pencarian: tanpa sudut (persegi) dan selebar header (padding 16dp sama).
        searchContainer.background = ThemeHelper.createRoundedBackground(ThemeHelper.getBgColor(isDark), 0f).apply {
            setStroke(dp(1), ThemeHelper.getSepColor(isDark))
        }
        searchContainer.visibility = if (isSearching) View.VISIBLE else View.GONE
        if (searchInput.text.toString() != searchQuery) searchInput.setText(searchQuery)
        var searchJob: Job? = null
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(450)
                    if (!isDestroyed && galleryBuilt && v.isAttachedToWindow) filterCards(v)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                filterCards(v)
                hideKeyboardFrom(searchInput)
                AnalyticsService.search(searchQuery, galleryItems.size)
                true
            } else false
        }
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
                searchJob?.cancel()
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
                if (v.isAttachedToWindow && galleryBuilt) filterCards(v, addMissingResults = true, resetScroll = false)
            }
        }
        v.findViewById<LinearLayout>(R.id.offline_banner).visibility = if (isOffline || isCachedRefreshFailed) View.VISIBLE else View.GONE
        (v.findViewById<LinearLayout>(R.id.offline_banner).getChildAt(1) as? TextView)?.setText(
            if (isOffline) R.string.gallery_offline else R.string.gallery_cached_refresh_failed
        )
        val countTv = v.findViewById<TextView>(R.id.photo_count); countTv.setTextColor(ThemeHelper.getSecLabelColor(isDark))

        val fab = v.findViewById<FrameLayout>(R.id.story_fab)
        v.findViewById<View>(R.id.story_fab_bg).background = GradientDrawable().apply { colors = intArrayOf(0xFF6366F1.toInt(), 0xFF8B5CF6.toInt()); cornerRadius = dpFloat(16f) }
        fab.setOnClickListener { showStoryView() }

        v.findViewById<ScrollView>(R.id.photo_scroll).setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val scrollView = view as ScrollView
            val contentHeight = scrollView.getChildAt(0)?.height ?: 0
            val remaining = contentHeight - (scrollY + scrollView.height)
            if (remaining < scrollView.height) appendGalleryPage(v)
            logScrollDepth(scrollY, scrollView.height, contentHeight)
        }
        refreshPhotoGrid(v)
        return v
    }

    private fun showFilterDropdown(titleTv: TextView) {
        activeFilterPopup?.let { popup ->
            activeFilterPopup = null
            popup.dismiss()
            return
        }
        val items = listOf(
            getString(R.string.filter_all) to 0,
            getString(R.string.filter_photo) to 1,
            getString(R.string.filter_video) to 2
        )
        val popup = android.widget.PopupMenu(this, titleTv)
        items.forEach { (label, value) ->
            popup.menu.add(0, value, value, label).isCheckable = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.findItem(galleryFilter)?.isChecked = true
        activeFilterPopup = popup
        popup.setOnMenuItemClickListener { item ->
            galleryFilter = item.itemId
            item.isChecked = true
            getSharedPreferences("tkjone_prefs", MODE_PRIVATE).edit().putInt(FILTER_PREF_KEY, galleryFilter).apply()
            titleTv.text = items.first { it.second == galleryFilter }.first
            AnalyticsService.filterChanged(items.first { it.second == galleryFilter }.first)
            cachedGalleryView?.let { filterCards(it, addMissingResults = true) }
            true
        }
        popup.setOnDismissListener { if (activeFilterPopup === popup) activeFilterPopup = null }
        popup.show()
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

    private fun photoCatalogHash(items: List<Photo>): String = MessageDigest.getInstance("SHA-256")
        .digest(items.joinToString("\n") { "${it.name}\u0000${it.url}\u0000${it.date}\u0000${it.type}\u0000${it.uploader}" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun imageCacheKey(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun refreshPhotoGrid(galleryView: View, showLoadingIndicator: Boolean = true) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val countTv = galleryView.findViewById<TextView>(R.id.photo_count)
        val isDark = isDark
        // Hentikan shimmer & job thumbnail lama sebelum rebuild — mencegah animasi
        // yatim menumpuk (penyebab UI makin lama makin berat lalu force close).
        thumbnailJobs.forEach { it.cancel() }; thumbnailJobs.clear()
        galleryVersion++
        grid.removeAllViews()
        galleryCardViews.clear()
        activeFilterPopup?.dismiss()
        activeFilterPopup = null
        val filtered = getFilteredPhotos()
        galleryItems = filtered
        galleryRenderedCount = 0
        isAppendingGalleryPage = false
        loggedScrollMilestones.clear()
        countTv?.text = "${filtered.size}"

        if (filtered.isEmpty()) {
            updateGalleryEmptyState(galleryView, true)
            return
        }

        val loadingTv = if (showLoadingIndicator) {
            TextView(this).apply {
                text = getString(R.string.gallery_loading_thumbs, filtered.size)
                textSize = 12f
                setTextColor(ThemeHelper.getSecLabelColor(isDark))
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            }.also { grid.addView(it) }
        } else null

        val ver = galleryVersion
        galleryBuilt = true
        appendGalleryPage(galleryView)

        // Remove loading text after thumbnails load
        loadingTv?.let { loading ->
            scope.launch {
                delay(2000)
                if (!isDestroyed && ver == galleryVersion) loading.visibility = View.GONE
            }
        }
    }

    private fun appendGalleryPage(galleryView: View) {
        if (isAppendingGalleryPage || galleryRenderedCount >= galleryItems.size || isDestroyed) return
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val version = galleryVersion
        val start = galleryRenderedCount
        val end = minOf(start + galleryPageSize, galleryItems.size)
        val darkTheme = isDark
        isAppendingGalleryPage = true
        thumbnailJobs.removeAll { it.isCompleted }
        var row: LinearLayout? = null
        for (index in start until end) {
            val photo = galleryItems[index]
            val key = photoCardKey(photo)
            val card = galleryCardViews[key] ?: createPhotoCard(photo, darkTheme, version, index).also {
                galleryCardViews[key] = it
            }
            (card.parent as? ViewGroup)?.removeView(card)
            card.visibility = View.VISIBLE
            if ((index - start) % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(12))
                }
                grid.addView(row)
            }
            row?.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(if ((index - start) % 2 == 0) 0 else dp(6), 0, if ((index - start) % 2 == 0) dp(6) else 0, 0)
            })
            if (index - start < 12 && card.alpha < 1f) animateCardIn(card, (index - start) * 40)
        }
        galleryRenderedCount = end
        isAppendingGalleryPage = false
        val scrollView = galleryView.findViewById<ScrollView>(R.id.photo_scroll)
        grid.post {
            if (version == galleryVersion && galleryRenderedCount < galleryItems.size &&
                grid.height <= scrollView.height + scrollView.scrollY + dp(80)
            ) appendGalleryPage(galleryView)
        }
    }

    /**
     * Show/hide existing cards based on filter — NO rebuild, NO re-download.
     */
    private fun filterCards(galleryView: View, addMissingResults: Boolean = true, resetScroll: Boolean = true) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val filtered = getFilteredPhotos()
        val previousRenderedCount = galleryRenderedCount
        val previousCachedCount = galleryCardViews.size
        grid.removeAllViews()
        galleryItems = filtered
        galleryRenderedCount = 0
        isAppendingGalleryPage = false
        galleryView.findViewById<TextView>(R.id.photo_count)?.text = filtered.size.toString()
        if (resetScroll) galleryView.findViewById<ScrollView>(R.id.photo_scroll)?.scrollTo(0, 0)
        updateGalleryEmptyState(galleryView, filtered.isEmpty())
        if (filtered.isEmpty()) return

        galleryBuilt = true
        val renderLimit = if (addMissingResults) {
            maxOf(galleryPageSize, previousRenderedCount, previousCachedCount)
        } else {
            maxOf(galleryPageSize, previousRenderedCount)
        }.coerceAtMost(filtered.size)
        while (galleryRenderedCount < renderLimit) appendGalleryPage(galleryView)
    }

    private fun photoCardKey(photo: Photo): String = "${imageCacheKey(photo.url)}:${photo.name.lowercase()}"

    private fun updateGalleryEmptyState(galleryView: View, show: Boolean) {
        val grid = galleryView.findViewById<LinearLayout>(R.id.photo_grid) ?: return
        val emptyState = grid.findViewWithTag<View>("gallery_empty_state")
        if (!show) {
            if (emptyState != null) grid.removeView(emptyState)
            return
        }
        if (emptyState != null) {
            (emptyState as? TextView)?.text = if (searchQuery.isNotEmpty()) {
                getString(R.string.gallery_empty_search, searchQuery)
            } else {
                getString(R.string.gallery_empty)
            }
            return
        }
        val message = TextView(this).apply {
            tag = "gallery_empty_state"
            text = if (searchQuery.isNotEmpty()) getString(R.string.gallery_empty_search, searchQuery) else getString(R.string.gallery_empty)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 8f).apply {
                setStroke(dp(1), ThemeHelper.getSepColor(isDark))
            }
        }
        grid.addView(message, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(24), dp(1), dp(24), dp(8)) })
    }

    private fun createPhotoCard(photo: Photo, isDark: Boolean, version: Int, index: Int = 0): LinearLayout {
        val card = layoutInflater.inflate(R.layout.item_photo_card, null) as LinearLayout
        val cardBg = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 14f)
        cardBg.setStroke(1, ThemeHelper.getSepColor(isDark))
        card.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x22FFFFFF), cardBg, null
        )
        card.elevation = dpFloat(2f)
        val thumb = card.findViewById<ImageView>(R.id.card_thumb)
        val thumbLoading = card.findViewById<ProgressBar>(R.id.card_thumb_loading)
        val playOverlay = card.findViewById<FrameLayout>(R.id.card_play_overlay)
        thumbLoading?.visibility = View.VISIBLE
        val nameTv = card.findViewById<TextView>(R.id.card_name); val dateTv = card.findViewById<TextView>(R.id.card_date)
        val uploaderTv = card.findViewById<TextView>(R.id.card_uploader)
        val linkBtn = card.findViewById<ImageView>(R.id.card_link_btn)
        nameTv.setTextColor(ThemeHelper.getLabelColor(isDark)); dateTv.setTextColor(ThemeHelper.getSecLabelColor(isDark)); linkBtn.setColorFilter(ThemeHelper.PRIMARY_CYAN)
        nameTv.text = photo.name; dateTv.text = formatDate(photo.date)
        if (photo.uploader.isNotBlank()) {
            uploaderTv.visibility = View.VISIBLE
            uploaderTv.setTextColor(ThemeHelper.ACCENT_BLUE)
            uploaderTv.text = getString(R.string.photo_uploader_format, photo.uploader)
        } else {
            uploaderTv.visibility = View.GONE
        }
        if (photo.isVideo) playOverlay.visibility = View.VISIBLE
        linkBtn.setOnClickListener { openExternal(AppConfig.photoLink(photo.name)) }
        card.setOnClickListener { navigateToPhoto(photo.name) }

        // Show placeholder immediately with photo name
        thumb.setBackgroundColor(ThemeHelper.getSepColor(isDark))

        // Reuse sampled bitmaps by source URL, independent of the photo label.
        val bitmapKey = imageCacheKey(photo.url)
        val memBmp = cardBitmapCache[bitmapKey]
        if (memBmp != null && !memBmp.isRecycled) {
            thumb.setImageBitmap(memBmp); thumb.setBackgroundColor(Color.TRANSPARENT)
            thumbLoading?.visibility = View.GONE
            return card
        }

        // Load thumbnail — staggered, satu per satu dari kiri ke kanan
        val job = scope.launch {
            try {
                while (thumbnailsPaused && !isDestroyed && version == galleryVersion) { delay(500) }
                if (version != galleryVersion || isDestroyed) return@launch

                val path = ThumbnailCacheService.cacheThumbnail(
                    this@MainActivity, photo.url, version = photo.date, isVideo = photo.isVideo
                )
                if (path != null && version == galleryVersion && !isDestroyed) {
                    val bmp = ThumbnailCacheService.decodeBitmapOnIO(path, 250)
                    if (bmp != null && version == galleryVersion && !isDestroyed) {
                        thumb.setImageBitmap(bmp)
                        thumb.setBackgroundColor(Color.TRANSPARENT)
                        trimMemoryCache()
                        cardBitmapCache[imageCacheKey(photo.url)] = bmp
                    }
                }
                thumbLoading?.visibility = View.GONE
            } catch (_: Exception) { thumbLoading?.visibility = View.GONE }
        }
        thumbnailJobs.add(job)
        return card
    }

    /**
     * Batasi bitmap cache in-memory agar tidak menumpuk → mencegah OOM
     * (penyebab force-close paling sering saat scroll galeri panjang).
     */
    /** Kirim kedalaman scroll galeri ke analytics sekali per milestone (25/50/75/90/100%). */
    private fun logScrollDepth(scrollY: Int, viewport: Int, contentHeight: Int) {
        if (contentHeight <= 0 || galleryItems.isEmpty()) return
        val maxScroll = (contentHeight - viewport).coerceAtLeast(1)
        val depth = ((scrollY.toFloat() / maxScroll) * 100f).toInt().coerceIn(0, 100)
        for (milestone in SCROLL_MILESTONES) {
            if (depth >= milestone && loggedScrollMilestones.add(milestone)) {
                AnalyticsService.galleryScroll(milestone, galleryRenderedCount, galleryItems.size)
            }
        }
    }

    private fun trimMemoryCache(maxEntries: Int = 24) {
        while (cardBitmapCache.size > maxEntries) {
            val oldest = cardBitmapCache.keys.first()
            cardBitmapCache.remove(oldest)
        }
    }

    private fun navigateToPhoto(name: String, source: String = "grid") {
        val match = photos.find { it.name.equals(name, ignoreCase = true) }
        if (match != null) {
            showPhotoDetailScreen(match, photos.indexOf(match), source)
        } else DialogHelper.showToast(this, getString(R.string.toast_photo_not_found, name))
    }

    // ─── PHOTO DETAIL ───

    private var photoSwipeHelper: com.tkjone.app.service.PhotoSwipeHelper? = null
    private var videoDownX = 0f
    private var videoDownY = 0f

    private fun closePhotoDetail() {
        if (currentScreenTag != "photo_detail") return
        currentScreenTag = "closing_photo_detail"
        photoDetailJob?.cancel()
        photoDetailJob = null
        // Hentikan & lepas video player sebelum view dibuang — cegah kebocoran surface
        photoSwipeHelper?.destroy()
        photoSwipeHelper = null
        showHomeScreen()
    }

    private fun showPhotoDetailScreen(photo: Photo, initialIndex: Int, source: String = "grid") {
        thumbnailsPaused = true
        ThumbnailCacheService.setPrefetchPaused(true)
        AnalyticsService.screen("photo_detail")
        AnalyticsService.photoOpened(photo, initialIndex, source)
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
                onBack = { closePhotoDetail() },
                onDownload = { p -> handleDownloadClick(p) },
                onShare = { p ->
                    AnalyticsService.photoShared(p)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppConfig.photoLink(p.name)) }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)))
                },
                getCachedBitmap = { photo -> cardBitmapCache[imageCacheKey(photo.url)] },
                loadPlaceholder = { p -> loadDetailDrop(p) },
                loadFullImage = { p -> loadDetailClean(p) },
            )
        }
        photoSwipeHelper!!.show(photo, initialIndex, photos)
    }

    /**
     * Placeholder layar detail — kualitas drop (cepat, dari cache yang sudah ada).
     * Video tidak pernah diunduh hanya untuk poster.
     */
    private suspend fun loadDetailDrop(p: Photo): Bitmap? {
        val mem = cardBitmapCache[imageCacheKey(p.url)]
        if (mem != null && !mem.isRecycled) return mem
        if (p.isVideo) return null
        return withContext(Dispatchers.IO) {
            try {
                val path = ThumbnailCacheService.cacheThumbnail(
                    this@MainActivity, p.url, version = p.date
                )
                if (path != null) ThumbnailCacheService.decodeBitmapOnIO(path, 1600) else null
            } catch (error: Exception) {
                android.util.Log.e("PhotoDetail", "Drop load failed for ${p.name}", error)
                null
            }
        }
    }

    /**
     * Gambar utama layar detail — kualitas bersih (byte asli dari server).
     * Disimpan di cache clean dengan nama hash supaya buka ulang tetap instan,
     * dan otomatis ter-invalidasi ketika URL/tanggal fotonya berubah.
     */
    private suspend fun loadDetailClean(p: Photo): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val path = if (p.isVideo) {
                    // Video: poster kualitas bersih (frame resolusi penuh) dari cache/view
                    ThumbnailCacheService.cacheVideoPoster(this@MainActivity, p.url, version = p.date)
                } else {
                    ThumbnailCacheService.cacheCleanImage(this@MainActivity, p.url, version = p.date)
                } ?: return@withContext null
                ThumbnailCacheService.decodeBitmapOnIO(path, CLEAN_DETAIL_MAX_DIMENSION)
            } catch (error: Exception) {
                android.util.Log.e("PhotoDetail", "Clean load failed for ${p.name}", error)
                null
            }
        }
    }

    /** Link biasa dibuka di browser/handler luar (bukan Custom Tab). */
    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            DialogHelper.showToast(this, getString(R.string.toast_link_failed))
        }
    }

    // ─── PROFILE ───

    private fun createProfileView(): View {
        val isDark = isDark
        val v = layoutInflater.inflate(R.layout.screen_profile, null) as ViewGroup; v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        makeFocusableOutside(v)
        v.findViewById<LinearLayout>(R.id.profile_header).setBackgroundColor(ThemeHelper.getBg2Color(isDark))
        val avatarLetter = v.findViewById<TextView>(R.id.profile_avatar_letter)
        val avatarFrame = v.findViewById<FrameLayout>(R.id.profile_avatar_frame)
        val photoIv = v.findViewById<ImageView>(R.id.profile_avatar_photo)
        avatarLetter.text = (loggedInUser?.firstOrNull()?.uppercase() ?: "?")
        avatarLetter.setTextColor(ThemeHelper.PRIMARY_CYAN)
        avatarLetter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ThemeHelper.ACCENT_BLUE and 0x1FFFFFFF or 0x20000000)
        }
        avatarFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        avatarFrame.clipToOutline = true
        photoIv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        photoIv.clipToOutline = true
        photoIv.visibility = View.GONE
        avatarLetter.visibility = View.VISIBLE
        val userForAvatar = loggedInUser
        if (!userForAvatar.isNullOrBlank()) scope.launch {
            // URL dari status.json dinormalisasi + diunduh lewat loader sendiri (ikut redirect).
            val url = ApiService.fetchProfileImageUrl(userForAvatar)
            val bitmap = ProfileImageLoader.loadAvatar(this@MainActivity, url)
            if (!isDestroyed && !isFinishing && loggedInUser == userForAvatar &&
                bitmap != null && !bitmap.isRecycled
            ) {
                photoIv.setImageBitmap(bitmap)
                photoIv.visibility = View.VISIBLE
                avatarLetter.visibility = View.GONE
            }
        }
        val nameTv = v.findViewById<TextView>(R.id.profile_name); nameTv.setTextColor(ThemeHelper.getLabelColor(isDark)); nameTv.text = loggedInUser ?: ""
        try { v.findViewById<View>(R.id.profile_theme_dropdown).visibility = View.GONE } catch (_: Exception) {}
        try { v.findViewById<View>(R.id.profile_layout_dropdown).visibility = View.GONE } catch (_: Exception) {}
        styleProfileTile(v.findViewById(R.id.profile_upload_tile), isDark)
        v.findViewById<LinearLayout>(R.id.profile_upload_tile).setOnClickListener {
            val user = loggedInUser ?: ""
            val url = if (user.isNotEmpty()) {
                AppConfig.UPLOAD_URL + "?u=" + java.net.URLEncoder.encode(user, "UTF-8")
            } else AppConfig.UPLOAD_URL
            openUploadTab(url)
        }

        styleProfileTile(v.findViewById(R.id.profile_github_tile), isDark)
        v.findViewById<LinearLayout>(R.id.profile_github_tile).apply {
            setOnClickListener { CustomTabLauncher.openUrl(this@MainActivity, AppConfig.GITHUB_AVATAR_URL.replace(".png", "")) }
            setOnLongClickListener { showDevPopup(); true }
        }

        styleProfileTile(v.findViewById(R.id.profile_uploaders_tile), isDark)
        v.findViewById<LinearLayout>(R.id.profile_uploaders_tile).setOnClickListener {
            CustomTabLauncher.openUrl(this@MainActivity, AppConfig.UPLOADERS_URL)
        }

        styleProfileTile(v.findViewById(R.id.profile_privacy_tile), isDark)
        v.findViewById<LinearLayout>(R.id.profile_privacy_tile).setOnClickListener {
            CustomTabLauncher.openUrl(this@MainActivity, AppConfig.PRIVACY_URL)
        }

        styleProfileTile(v.findViewById(R.id.profile_terms_tile), isDark)
        v.findViewById<LinearLayout>(R.id.profile_terms_tile).setOnClickListener {
            CustomTabLauncher.openUrl(this@MainActivity, AppConfig.TERMS_URL)
        }

        styleProfileTile(v.findViewById(R.id.profile_logout_btn), isDark)
        v.findViewById<LinearLayout>(R.id.profile_logout_btn).setOnClickListener {
            DialogHelper.showConfirmDialog(
                this@MainActivity,
                getString(R.string.dialog_logout_title),
                getString(R.string.dialog_logout_message),
                getString(R.string.dialog_logout_confirm),
                getString(R.string.dialog_cancel),
                ThemeHelper.ACCENT_RED
            ) { handleLogout() }
        }
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val versionTv = v.findViewById<TextView>(R.id.profile_version); versionTv.setTextColor(ThemeHelper.getLabelColor(isDark)); versionTv.text = pInfo?.versionName?.let { "v$it" } ?: ""
        return v
    }

    /** Background seragam untuk semua tile di halaman profil. */
    private fun styleProfileTile(view: View?, isDark: Boolean) {
        val tile = view as? LinearLayout ?: return
        tile.background = GradientDrawable().apply {
            setColor(ThemeHelper.getBg2Color(isDark))
            cornerRadius = dpFloat(12f)
            setStroke(1, (ThemeHelper.getSepColor(isDark) and 0x9FFFFFFF.toInt()))
        }
    }

    /**
     * Buka halaman upload di Custom Tab, lalu tandai agar data di-refresh begitu user
     * kembali (menutup tab). Dengan begitu foto yang baru diunggah langsung muncul.
     */
    private fun openUploadTab(url: String) {
        uploadTabOpenedAt = System.currentTimeMillis()
        uploadRefreshPending = true
        CustomTabLauncher.openUrl(this, url)
    }

    /** Dipanggil saat activity kembali ke foreground — refresh setelah upload selesai. */
    private fun refreshAfterUploadIfNeeded() {
        if (!uploadRefreshPending) return
        if (System.currentTimeMillis() - uploadTabOpenedAt < 1500L) return
        uploadRefreshPending = false
        if (isDestroyed || isFinishing || loggedInUser == null) return
        scope.launch {
            try {
                fetchAllData()
                if (!isDestroyed && !isFinishing && selectedHomeTab == 0) {
                    cachedGalleryView?.let { view ->
                        if (photosChangedOnLastFetch) refreshPhotoGrid(view, showLoadingIndicator = false)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // ─── STORY ───

    private fun showStoryView() {
        if (photos.isEmpty()) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)); return }
        storyOpenedAt = System.currentTimeMillis()
        AnalyticsService.screen("story")
        AnalyticsService.storyOpened()
        val v = layoutInflater.inflate(R.layout.screen_story, null) as ViewGroup
        val isDark = isDark; v.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        v.findViewById<ImageView>(R.id.story_close_btn).setColorFilter(ThemeHelper.ACCENT_BLUE); v.findViewById<ImageView>(R.id.story_close_btn).setOnClickListener { showHomeScreen() }
        val webView = v.findViewById<android.webkit.WebView>(R.id.story_webview)
        webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; cacheMode = android.webkit.WebSettings.LOAD_DEFAULT; allowFileAccess = true; allowContentAccess = true; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW; databaseEnabled = true; setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false }
        webView.addJavascriptInterface(object { @android.webkit.JavascriptInterface fun postMessage(json: String) { try { val o = org.json.JSONObject(json); if (o.optString("command") == "closeStory") runOnUiThread { if (!isFinishing && !isDestroyed) showHomeScreen() } } catch (_: Exception) {} } }, "AndroidInterface")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean { val url = request?.url?.toString() ?: return false; if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:///")) return false; try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}; return true }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() { override fun onPermissionRequest(request: android.webkit.PermissionRequest?) { request?.grant(request.resources) } }
        try { val html = assets.open("story/index.html").use { it.bufferedReader().readText() }; webView.loadDataWithBaseURL("${AppConfig.BASE_URL}/story/", html, "text/html", "UTF-8", null) } catch (_: Exception) { DialogHelper.showToast(this, getString(R.string.toast_story_empty)) }
        rootContainer.removeAllViews(); rootContainer.addView(v); currentScreenTag = "story"; animateScreenIn(v)
    }

    // ─── ERROR ───

    private fun showErrorView() {
        destroyWebViews()
        val view = layoutInflater.inflate(R.layout.screen_error, null)
        view.setBackgroundColor(ThemeHelper.getBgColor(isDark))
        view.findViewById<TextView>(R.id.error_message).apply {
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            text = errorMessage?.takeIf { it.isNotBlank() } ?: getString(R.string.error_network)
        }
        view.findViewById<TextView>(R.id.error_retry_btn).apply {
            background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f)
            setOnClickListener {
                isLoading = true
                showSplash()
                scope.launch { initialize() }
            }
        }
        rootContainer.removeAllViews()
        rootContainer.addView(view)
        currentScreenTag = "error"
        animateScreenIn(view)
    }

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
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        // Avatar developer — pakai loader + cache yang sama dengan foto profil user
        scope.launch {
            val bitmap = ProfileImageLoader.loadAvatar(this@MainActivity, AppConfig.GITHUB_AVATAR_URL)
            if (isDestroyed) return@launch
            if (bitmap != null && !bitmap.isRecycled) avatarIv.setImageBitmap(bitmap)
            else avatarIv.setImageResource(R.drawable.person)
        }
        c.addView(avatarIv)
        c.addView(TextView(this@MainActivity).apply { text = "ZHYLLAN FYLLLAH"; textSize = 20f; setTextColor(ThemeHelper.getLabelColor(isDark)); typeface = resources.getFont(R.font.torus_bold); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_subtitle); textSize = 12f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); gravity = Gravity.CENTER })
        c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }; setBackgroundColor(ThemeHelper.getSepColor(isDark)) })
        c.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_license_section); textSize = 10f; setTextColor(ThemeHelper.getSecLabelColor(isDark)); letterSpacing = 0.5f; setPadding(dp(8), dp(4), 0, dp(4)) })
        val licenses = listOf(
            Pair("TKJ OnE (MIT)", "LICENSE"),
            Pair("AndroidX / Jetpack (Apache 2.0)", "licenses/apache.txt"),
            Pair("Plyr 3.7.8 (MIT)", "licenses/plyr.txt"),
            Pair("CompressorJS (MIT)", "licenses/compressorjs.txt"),
            Pair("Font Awesome Free", "assets/fa/LICENSE-fontawesome.txt")
        )
        for ((name, assetPath) in licenses) { c.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(12)); isClickable = true; isFocusable = true; addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.document_text); setColorFilter(ThemeHelper.PRIMARY_CYAN); layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)); background = GradientDrawable().apply { setColor(ThemeHelper.PRIMARY_CYAN and 0x1FFFFFFF or 0x1F000000); cornerRadius = dpFloat(6f) }; setPadding(dp(4), dp(4), dp(4), dp(4)) }); addView(TextView(this@MainActivity).apply { text = name; textSize = 14f; setTextColor(ThemeHelper.getLabelColor(isDark)); setPadding(dp(10), 0, 0, 0) }); setOnClickListener { try { val content = assets.open(assetPath).use { it.bufferedReader().readText() }; DialogHelper.showInfoDialog(this@MainActivity, name, content) } catch (_: Exception) { DialogHelper.showToast(this@MainActivity, getString(R.string.dev_license_not_found)) } } }); c.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(ThemeHelper.getSepColor(isDark)) }) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_close); textSize = 15f; setTextColor(Color.WHITE); typeface = resources.getFont(R.font.torus_bold); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_BLUE, 12f); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }; setOnClickListener { dialog.dismiss() } })
        btnRow.addView(TextView(this@MainActivity).apply { text = getString(R.string.dev_portfolio); textSize = 15f; setTextColor(Color.WHITE); typeface = resources.getFont(R.font.torus_bold); setPadding(dp(24), dp(12), dp(24), dp(12)); background = ThemeHelper.createRoundedBackground(ThemeHelper.ACCENT_GREEN, 12f); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); setOnClickListener { com.tkjone.app.service.CustomTabLauncher.openUrl(this@MainActivity, AppConfig.PORTFOLIO_URL) } })
        c.addView(btnRow); sv.addView(c); dialog.setContentView(sv)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; dialog.show()
    }

    // ─── DOWNLOAD ───

    private fun requiredMediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasStoragePermission(): Boolean = requiredMediaPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMediaPermissionsIfNeeded() {
        if (!hasStoragePermission()) mediaPermissionLauncher.launch(requiredMediaPermissions())
    }

    private fun handleDownloadClick(photo: Photo) {
        AnalyticsService.downloadRequested(photo, "detail")
        if (hasStoragePermission()) {
            startDownloadWithProgress(photo)
        } else {
            DialogHelper.showConfirmDialog(
                this,
                getString(R.string.dialog_permission_title),
                getString(R.string.dialog_permission_message),
                getString(R.string.dialog_permission_grant),
                getString(R.string.dialog_cancel),
                ThemeHelper.ACCENT_BLUE
            ) {
                pendingDownloadPhoto = photo
                mediaPermissionLauncher.launch(requiredMediaPermissions())
            }
        }
    }

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
        val startedAt = System.currentTimeMillis()
        val progressDialog = Dialog(this); progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20)); background = GradientDrawable().apply { setColor(ThemeHelper.getCardColor(isDark)); cornerRadius = dpFloat(16f) } }
        val titleText = TextView(this).apply { text = getString(R.string.dialog_download_progress, 0); textSize = 16f; setTextColor(ThemeHelper.getLabelColor(isDark)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) } }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)); max = 100; progress = 0 }
        container.addView(titleText); container.addView(progressBar); progressDialog.setContentView(container); progressDialog.setCancelable(false)
        progressDialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT); setGravity(Gravity.CENTER) }; progressDialog.show()
        scope.launch {
            try {
                val extension = DownloadService.getFileExtension(photo.url)
                val fileName = "${DownloadService.sanitizeFileName(photo.name)}.$extension"
                val result = DownloadService.downloadToDcim(
                    this@MainActivity,
                    photo.url,
                    fileName
                ) { received, total ->
                    val progress = if (total > 0L) ((received * 100L) / total).toInt() else 0
                    runOnUiThread {
                        if (progressDialog.isShowing) {
                            progressBar.progress = progress
                            titleText.text = getString(R.string.dialog_download_progress, progress)
                        }
                    }
                }
                val savedBytes = result.filePath?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
                AnalyticsService.downloadFinished(
                    photo,
                    result.success,
                    bytes = savedBytes,
                    durationMs = System.currentTimeMillis() - startedAt
                )
                runOnUiThread {
                    try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {}
                    if (result.success) DialogHelper.showToast(this@MainActivity, getString(R.string.toast_download_complete))
                    else DialogHelper.showToast(this@MainActivity, result.message)
                }
            } catch (e: Exception) {
                AnalyticsService.downloadFinished(photo, false, durationMs = System.currentTimeMillis() - startedAt)
                runOnUiThread { try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {} }
                DialogHelper.showToast(this@MainActivity, e.message ?: "Error")
            }
        }
    }

    // ─── HELPERS ───

    private fun formatDate(dateStr: String): String { return try { val parts = dateStr.split("-"); if (parts.size == 3) { val m = resources.getStringArray(R.array.months); "${parts[2].toInt()} ${m[parts[1].toInt() - 1]} ${parts[0]}" } else dateStr } catch (_: Exception) { dateStr } }
    // dp(), dpFloat(), hideKeyboardFrom(), makeFocusableOutside(), isDark — inherited from AppActivity
}
