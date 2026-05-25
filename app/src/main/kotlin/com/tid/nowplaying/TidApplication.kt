package com.tid.nowplaying

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

private const val TAG = "TidApplication"

class TidApplication : Application() {

    lateinit var serialManager: TidSerialManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        serialManager = TidSerialManager(this)
        serialManager.connect()

        scope.launch {
            UsbStatusRepository.status
                .distinctUntilChangedBy { it is UsbStatus.Ready }
                .collect { status ->
                    if (status is UsbStatus.Ready) onPortReady()
                }
        }
    }

    private fun onPortReady() {
        val checker = FirmwareChecker(serialManager, this)
        val version = checker.queryVersion()
        if (version != REQUIRED_FIRMWARE_VERSION) {
            logD(TAG, "Firmware $version outdated — auto-flashing ${REQUIRED_FIRMWARE_VERSION}…")
            checker.flash(SelectedBoardRepository.board.value)
        }
    }
}
