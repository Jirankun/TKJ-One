package com.tkjone.app

import android.app.Application

class TKJOneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TKJOneApp
            private set
    }
}
