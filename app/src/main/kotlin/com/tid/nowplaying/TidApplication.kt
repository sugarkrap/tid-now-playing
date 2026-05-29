package com.tid.nowplaying

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TidApplication"

class TidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var serialManager: TidSerialManager

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        SelectedBoardRepository.init(this)
        appScope.launch {
            AppSettings.showConnectionLogsFlow().collect { AppSettings.updateCache(it) }
        }
        serialManager = TidSerialManager(this)
        serialManager.connect()
    }
}
