package com.tid.nowplaying

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

private const val TAG = "TidSerialManager"
private const val ACTION_USB_PERMISSION = "TID_USB_PERMISSION"

class TidSerialManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()
    private var port: UsbSerialPort? = null
    var onPortReady: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) openPort(device)
                    else {
                        Log.d(TAG, "USB permission denied")
                        UsbStatusRepository.update(UsbStatus.Error("Permission denied"))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) connectDevice(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB device detached")
                    UsbStatusRepository.update(UsbStatus.Disconnected)
                    closePort()
                }
            }
        }
    }

    fun connect() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        val prober = UsbSerialProber.getDefaultProber()
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            Log.d(TAG, "No USB devices found at startup")
        } else {
            devices.forEach { d ->
                val driver = prober.probeDevice(d)
                Log.d(TAG, "USB device: ${d.productName} vid=0x%04X pid=0x%04X driver=${driver?.javaClass?.simpleName ?: "none"}".format(d.vendorId, d.productId))
            }
            devices.firstOrNull { prober.probeDevice(it) != null }?.let { connectDevice(it) }
        }
    }

    fun disconnect() {
        closePort()
        try { context.unregisterReceiver(usbReceiver) } catch (_: IllegalArgumentException) {}
    }

    fun send(text: String) {
        executor.execute {
            val p = port ?: return@execute
            try {
                p.write(text.toByteArray(), 2000)
                SerialLogRepository.logSent(text)
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}")
            }
        }
    }

    private fun connectDevice(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openPort(device)
        } else {
            UsbStatusRepository.update(UsbStatus.AwaitingPermission)
            val intent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, intent)
        }
    }

    private fun openPort(device: UsbDevice) {
        executor.execute {
            try {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: run {
                        UsbStatusRepository.update(UsbStatus.Error("No driver for ${device.productName}"))
                        return@execute
                    }
                val connection = usbManager.openDevice(device)
                    ?: run {
                        UsbStatusRepository.update(UsbStatus.Error("Failed to open USB connection"))
                        return@execute
                    }
                val p = driver.ports[0]
                p.open(connection)
                p.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                p.setDTR(true)
                p.setRTS(true)
                port = p
                UsbStatusRepository.update(
                    UsbStatus.Connected(
                        deviceName = device.productName ?: "USB Device",
                        vendorId = device.vendorId,
                        productId = device.productId,
                    )
                )
                Log.d(TAG, "Serial port opened — ${device.productName} (${device.vendorId.hex}:${device.productId.hex})")
                // Wait for Arduino to finish rebooting after DTR assertion
                Thread.sleep(500)
                UsbStatusRepository.update(
                    UsbStatus.Ready(
                        deviceName = device.productName ?: "USB Device",
                        vendorId = device.vendorId,
                        productId = device.productId,
                    )
                )
                onPortReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open port: ${e.message}")
                UsbStatusRepository.update(UsbStatus.Error(e.message ?: "Unknown error"))
            }
        }
    }

    private fun closePort() {
        executor.execute {
            try { port?.close() } catch (_: Exception) {}
            port = null
            Log.d(TAG, "Serial port closed")
        }
    }
}

private val Int.hex get() = "0x%04X".format(this)

fun requestUsbPermission(context: Context) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val prober = UsbSerialProber.getDefaultProber()
    val allDevices = usbManager.deviceList.values
    allDevices.forEach { d ->
        Log.d(TAG, "requestUsbPermission: scanning vid=0x%04X pid=0x%04X driver=${prober.probeDevice(d)?.javaClass?.simpleName ?: "none"}".format(d.vendorId, d.productId))
    }
    val device = allDevices.firstOrNull { prober.probeDevice(it) != null }
        ?: run {
            val ids = allDevices.joinToString { "0x%04X:0x%04X".format(it.vendorId, it.productId) }
            Log.e(TAG, "No supported USB device found. Connected: $ids")
            UsbStatusRepository.update(UsbStatus.Error("No supported device (found: ${ids.ifEmpty { "none" }})"))
            return
        }
    val intent = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    UsbStatusRepository.update(UsbStatus.AwaitingPermission)
    usbManager.requestPermission(device, intent)
}
