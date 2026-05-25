package com.tid.nowplaying

import android.app.Application

class TidApplication : Application() {

    lateinit var serialManager: TidSerialManager

    override fun onCreate() {
        super.onCreate()
        serialManager = TidSerialManager(this)
        serialManager.connect()
    }
}
