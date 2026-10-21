package com.tkjone.app.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/** Ad unit banner halaman utama (unit produksi, bukan test unit). */
private const val BANNER_AD_UNIT_ID = "ca-app-pub-6754329161781195/8080677633"

/**
 * Banner AdMob anchored-adaptive selebar layar, dipasang di bawah bottom navigation.
 *
 * Bila iklan gagal termuat (mis. NO_FILL untuk ad unit baru) view disembunyikan supaya
 * tidak menyisakan ruang kosong; background disamakan dengan bottom nav agar transisi halus.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val adView = remember(screenWidthDp) {
        AdView(context).apply {
            setAdSize(
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidthDp)
                    ?: AdSize.BANNER
            )
            adUnitId = BANNER_AD_UNIT_ID
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    visibility = View.GONE
                }
            }
        }
    }

    DisposableEffect(adView) {
        adView.loadAd(AdRequest.Builder().build())
        onDispose {
            // Wajib: tanpa destroy, AdView menahan Activity context dan bocor.
            adView.destroy()
        }
    }

    AndroidView(
        factory = { adView },
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Color(0xFF2C2C2E))
    )
}
