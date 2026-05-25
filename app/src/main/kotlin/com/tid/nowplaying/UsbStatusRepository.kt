package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UsbStatus {
    data object Disconnected : UsbStatus()
    data object AwaitingPermission : UsbStatus()
    data class Connected(val deviceName: String, val vendorId: Int, val productId: Int) : UsbStatus()
    data class Ready(val deviceName: String, val vendorId: Int, val productId: Int) : UsbStatus()
    data class Error(val message: String) : UsbStatus()
}

object UsbStatusRepository {
    private val _status = MutableStateFlow<UsbStatus>(UsbStatus.Disconnected)
    val status: StateFlow<UsbStatus> = _status.asStateFlow()

    fun update(status: UsbStatus) {
        _status.value = status
    }
}
