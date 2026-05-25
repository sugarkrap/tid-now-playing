package com.tid.nowplaying

import android.content.Context

private const val TAG = "FirmwareChecker"

class FirmwareChecker(
    private val serialManager: TidSerialManager,
    private val context: Context,
) {
    fun queryVersion(): String? {
        FirmwareOperationRepository.setBusy(true)
        try {
            logD(TAG, "Querying firmware version…")
            val version = serialManager.queryFirmwareVersion()
            if (version == null) logD(TAG, "No response (timeout)")
            else logD(TAG, "Board reports: $version")
            return version
        } finally {
            FirmwareOperationRepository.setBusy(false)
        }
    }

    fun flash(board: ArduinoBoard) {
        val port = serialManager.port ?: run {
            logE(TAG, "Port not available for flashing")
            return
        }
        FirmwareOperationRepository.setBusy(true)
        serialManager.stopReadLoop()
        try {
            logD(TAG, "Flashing ${board.displayName}…")
            context.assets.open(board.hexAsset).use { hex ->
                ArduinoFlasher(port) { percent ->
                    logD(TAG, "Flash ${board.displayName}: $percent%")
                }.flash(hex)
            }
            logD(TAG, "Flash complete — ${board.displayName} running $REQUIRED_FIRMWARE_VERSION")
        } catch (e: ArduinoFlashException) {
            logE(TAG, "Flash failed: ${e.message}")
        } finally {
            serialManager.port?.let { serialManager.startReadLoop(it) }
            FirmwareOperationRepository.setBusy(false)
        }
    }
}
