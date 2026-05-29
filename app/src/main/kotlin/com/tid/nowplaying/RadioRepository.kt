package com.tid.nowplaying

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadioInfo(
    val frequencyKhz: Int?,
    val band: Byte?,
    val psName: String?,
    val rtMessage: String?,
) {
    val displayText: String? get() = when {
        !rtMessage.isNullOrBlank() -> rtMessage
        !psName.isNullOrBlank() && frequencyKhz != null -> "${formatFreq(frequencyKhz, band)} $psName"
        !psName.isNullOrBlank() -> psName
        frequencyKhz != null -> formatFreq(frequencyKhz, band)
        else -> null
    }
}

private fun formatFreq(khz: Int, band: Byte?): String {
    // Band 0 = FM (frequency in 10kHz steps, e.g. 9600 = 96.0 MHz)
    // Band 1 = AM (frequency in kHz)
    return if (band == null || band.toInt() == 0) {
        val mhz = khz / 100.0
        "${mhz}FM"
    } else {
        "${khz}AM"
    }
}

object RadioRepository {
    private val _info = MutableStateFlow<RadioInfo?>(null)
    val info: StateFlow<RadioInfo?> = _info.asStateFlow()

    fun update(info: RadioInfo?) {
        _info.value = info
    }

    fun updateFrequency(band: Byte, freqKhz: Int, psName: String?) {
        val current = _info.value
        _info.value = RadioInfo(
            frequencyKhz = freqKhz,
            band = band,
            psName = psName?.takeIf { it.isNotBlank() },
            rtMessage = current?.rtMessage,
        )
    }

    fun updateRtMessage(rt: String?) {
        val current = _info.value
        _info.value = if (current != null) {
            current.copy(rtMessage = rt?.takeIf { it.isNotBlank() })
        } else if (!rt.isNullOrBlank()) {
            RadioInfo(frequencyKhz = null, band = null, psName = null, rtMessage = rt)
        } else {
            null
        }
    }
}

// RadioCallback AIDL descriptor — must match com.nwd.kernel.aidl.RadioCallback.
private const val RADIO_CB_DESCRIPTOR = "com.nwd.kernel.aidl.RadioCallback"
private const val INTERFACE_TOKEN = 1598968902 // IBinder.INTERFACE_TRANSACTION

// Transaction codes from the decompiled RadioCallback interface.
private const val T_NOTIFY_STATE = 1
private const val T_NOTIFY_CURRENT_FREQUENCY = 2
private const val T_NOTIFY_NEAR_ON = 3
private const val T_NOTIFY_STEREO = 4
private const val T_NOTIFY_STEREO_ON = 5
private const val T_NOTIFY_RDS_STATE = 6
private const val T_NOTIFY_PTY_TYPE = 7
private const val T_NOTIFY_PREFAB_FREQUENCY = 8
private const val T_NOTIFY_RADIO_POINT = 10
private const val T_NOTIFY_RDS_SHOW = 12
private const val T_NOTIFY_RT_MESSAGE = 13
private const val T_NOTIFY_CHANGE_RADIO_STATE = 14

// IKernelFeature transaction codes for radio callback registration.
private const val T_REGIST_RADIO_CALLBACK = 95
private const val T_UNREGIST_RADIO_CALLBACK = 96

/**
 * Raw Binder implementing the RadioCallback AIDL stub without needing the parcelable
 * Frequency/RadioPoint types (we ignore those transactions).
 */
abstract class RadioCallbackBinder : Binder() {

    init {
        attachInterface(null, RADIO_CB_DESCRIPTOR)
    }

    override fun getInterfaceDescriptor(): String = RADIO_CB_DESCRIPTOR

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TOKEN) {
            reply?.writeString(RADIO_CB_DESCRIPTOR)
            return true
        }
        if (code < 1 || code > 16777215) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(RADIO_CB_DESCRIPTOR)
        when (code) {
            T_NOTIFY_CURRENT_FREQUENCY -> {
                val band = data.readByte()
                val freq = data.readInt()
                val psName = data.readString()
                val extra = data.readInt()
                reply?.writeNoException()
                notifyCurrentFrequency(band, freq, psName, extra)
            }
            T_NOTIFY_RT_MESSAGE -> {
                val rt = data.readString()
                reply?.writeNoException()
                notifyRtMessage(rt)
            }
            T_NOTIFY_RDS_STATE, T_NOTIFY_RDS_SHOW, T_NOTIFY_CHANGE_RADIO_STATE,
            T_NOTIFY_STATE, T_NOTIFY_NEAR_ON, T_NOTIFY_STEREO, T_NOTIFY_STEREO_ON,
            T_NOTIFY_PTY_TYPE, T_NOTIFY_PREFAB_FREQUENCY, T_NOTIFY_RADIO_POINT -> {
                reply?.writeNoException()
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    abstract fun notifyCurrentFrequency(band: Byte, freqKhz: Int, psName: String?, extra: Int)
    abstract fun notifyRtMessage(rt: String?)
}

/**
 * Registers/unregisters a RadioCallbackBinder with the kernel service IBinder
 * using direct transact calls (IKernelFeature transactions 95/96).
 */
fun IBinder.registRadioCallback(callback: RadioCallbackBinder) {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken("com.nwd.kernel.aidl.IKernelFeature")
        data.writeStrongBinder(callback)
        transact(T_REGIST_RADIO_CALLBACK, data, reply, 0)
        reply.readException()
    } finally {
        data.recycle()
        reply.recycle()
    }
}

fun IBinder.unRegistRadioCallback(callback: RadioCallbackBinder) {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken("com.nwd.kernel.aidl.IKernelFeature")
        data.writeStrongBinder(callback)
        transact(T_UNREGIST_RADIO_CALLBACK, data, reply, 0)
        reply.readException()
    } finally {
        data.recycle()
        reply.recycle()
    }
}
