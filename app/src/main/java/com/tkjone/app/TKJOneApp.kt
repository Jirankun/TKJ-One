package com.tkjone.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File

class TKJOneApp : Application() {
    val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(File(cacheDir, "coil_images"))
                    .maxSizeBytes(80L * 1024 * 1024)
                    .build()
            }
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Init fast HTTP client with DNS caching at app startup
        com.tkjone.app.service.ApiService.initClient(this)
    }

    companion object {
        lateinit var instance: TKJOneApp
            private set
    }
}
