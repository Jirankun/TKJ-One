package com.tkjone.app.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import com.tkjone.app.R
import com.tkjone.app.ui.ThemeHelper

/**
 * CustomTabLauncher — membuka URL (mis. halaman upload) di Chrome Custom Tab.
 *
 * Semua halaman web (upload, uploaders, privacy, terms, github) dibuka di Custom Tab.
 * In-app WebView (WebFallbackActivity) sudah DIHAPUS total — kalau perangkat tidak punya
 * provider Custom Tab, URL dibuka di browser default lewat ACTION_VIEW (bukan WebView).
 *
 *  - Warna toolbar/status disesuaikan tema aplikasi (polos, konsisten).
 *  - Warm-up client di background supaya tab pertama terbuka instan (bebas stuck).
 */
object CustomTabLauncher {

    private const val TAG = "CustomTabLauncher"
    private var warmClient: CustomTabsClient? = null
    private var warmedPackage: String? = null

    /** Panggil saat app startup — menyiapkan browser provider agar tab buka cepat. */
    fun warmUp(context: Context) {
        try {
            val pkg = resolveBrowserPackage(context) ?: return
            if (pkg == warmedPackage && warmClient != null) return
            val connection = object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                    client.warmup(0)
                    warmClient = client
                    warmedPackage = pkg
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    warmClient = null
                    warmedPackage = null
                }
            }
            CustomTabsClient.bindCustomTabsService(context, pkg, connection)
        } catch (e: Exception) {
            Log.w(TAG, "warmUp gagal (bukan masalah): ${e.message}")
        }
    }

    /** Cari package browser yang mendukung Custom Tabs. */
    private fun resolveBrowserPackage(context: Context): String? {
        val intent = Intent("android.support.customtabs.action.CustomTabsService")
            .setPackage("com.android.chrome")
        val pm = context.packageManager
        val services = pm.queryIntentServices(intent, 0)
        // Browser chrome tersedia?
        services?.firstOrNull()?.let { return it.serviceInfo.packageName }
        // Fallback: semua provider custom tabs
        val all = pm.queryIntentServices(
            Intent("android.support.customtabs.action.CustomTabsService"), 0
        )
        return all?.firstOrNull()?.serviceInfo?.packageName
    }

    /**
     * Buka [url] di Custom Tab. Jika tidak ada provider Custom Tab sama sekali,
     * fallback ke browser default (ACTION_VIEW) — tanpa WebView in-app.
     */
    fun openUrl(context: Context, url: String) {
        try {
            val pkg = resolveBrowserPackage(context)
            val builder = CustomTabsIntent.Builder().apply {
                setShowTitle(true)
                setToolbarColor(ThemeHelper.BG2)
                setStartAnimations(context, android.R.anim.fade_in, android.R.anim.slide_out_right)
                setExitAnimations(context, android.R.anim.fade_out, android.R.anim.slide_in_left)
            }
            val intent = builder.build()
            if (pkg != null) {
                intent.intent.setPackage(pkg)
            }
            // FLAG_ACTIVITY_NEW_TASK aman dipanggil dari mana saja
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(context, Uri.parse(url))
            return
        } catch (_: ActivityNotFoundException) {
            // tidak ada browser → fallback
        } catch (e: Exception) {
            Log.w(TAG, "Custom Tab gagal: ${e.message}")
        }

        // Fallback terakhir: browser default (bukan WebView in-app)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
            Log.e(TAG, "Fallback browser juga gagal", e2)
            com.tkjone.app.ui.DialogHelper.showToast(
                context, context.getString(R.string.toast_link_failed)
            )
        }
    }
}
