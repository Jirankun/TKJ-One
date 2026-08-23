package com.tkjone.app

import android.app.Application

class TKJOneApp : Application() {
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
