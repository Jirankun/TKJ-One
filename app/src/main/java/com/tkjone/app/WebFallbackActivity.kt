package com.tkjone.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.tkjone.app.ui.ThemeHelper

/**
 * Fallback WebView in-app — dipakai bila Custom Tab tidak tersedia
 * (tidak ada browser terpasang). UI polos dengan koordinat presisi,
 * tanpa Material 2/3. Dikelola aman: destroy() selalu dipanggil di
 * onDestroy agar tidak terjadi memori leak → mencegah force close.
 */
class WebFallbackActivity : com.tkjone.app.ui.AppActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url")

        // ─── Root: FrameLayout polos, warna solid BG ───
        val root = FrameLayout(this).apply {
            setBackgroundColor(ThemeHelper.BG)
            fitsSystemWindows = true
        }

        // ─── Top bar: tinggi presisi 48dp ───
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeHelper.BG2)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }

        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.arrow_back)
            setColorFilter(ThemeHelper.LABEL)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(10)
            }
            setOnClickListener { finish() }
        }

        val titleTv = TextView(this).apply {
            text = getString(R.string.nav_upload)
            textSize = 16f
            setTextColor(ThemeHelper.LABEL)
            typeface = resources.getFont(R.font.torus_bold)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }

        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(3)).apply { marginEnd = dp(4) }
            max = 100
            progress = 0
            visibility = View.GONE
        }

        bar.addView(backBtn)
        bar.addView(titleTv)
        bar.addView(spinner)

        // ─── Separator 1px presisi ───
        val sep = View(this).apply {
            setBackgroundColor(ThemeHelper.SEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(48) }
        }

        // ─── WebView isi ───
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(49) }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(ThemeHelper.BG)
            isVerticalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val u = request?.url ?: return false
                    return if (u.scheme == "http" || u.scheme == "https") false
                    else try {
                        startActivity(Intent(Intent.ACTION_VIEW, u)); true
                    } catch (_: Exception) { true }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    spinner.visibility = View.GONE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 1..99) {
                        spinner.visibility = View.VISIBLE
                        spinner.progress = newProgress
                    } else if (newProgress >= 100) {
                        spinner.visibility = View.GONE
                    }
                }
            }
        }
        webView = wv

        root.addView(wv)
        root.addView(sep)
        root.addView(bar)
        setContentView(root)

        ThemeHelper.applyStatusBarStyle(window)
        ThemeHelper.applyNavBarColor(window)

        if (url != null) {
            try { wv.loadUrl(url) } catch (_: Exception) { finish() }
        } else finish()
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        // Pembersihan total WebView — penyebab umum kebocoran memori & FC
        try {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                removeAllViews()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
        } catch (_: Exception) {}
        webView = null
        super.onDestroy()
    }
}
