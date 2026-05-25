package com.tid.nowplaying

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.InputStream

private const val TAG = "ArduinoFlasher"

class ArduinoFlashException(message: String) : Exception(message)

class ArduinoFlasher(
    private val port: UsbSerialPort,
    private val onProgress: (percent: Int) -> Unit,
) {
    companion object {
        private const val STK_OK = 0x10
        private const val STK_INSYNC = 0x14
        private const val CRC_EOP = 0x20
        private const val STK_GET_SYNC = 0x30
        private const val STK_LOAD_ADDRESS = 0x55
        private const val STK_PROG_PAGE = 0x64
        private const val STK_LEAVE_PROGMODE = 0x51
        private const val PAGE_SIZE = 128
        private const val FLASH_SIZE = 32768
        private const val BAUD_FLASH = 115200
        private const val BAUD_NORMAL = 9600
        private const val READ_TIMEOUT_MS = 500L
    }

    fun flash(hexInputStream: InputStream) {
        val flashImage = parseHex(hexInputStream)

        // Only write pages that contain actual data (not blank 0xFF pages)
        val dirtyPages = (0 until FLASH_SIZE / PAGE_SIZE).filter { pageIndex ->
            val start = pageIndex * PAGE_SIZE
            flashImage.copyOfRange(start, start + PAGE_SIZE).any { it != 0xFF.toByte() }
        }
        val totalPages = dirtyPages.size
        if (totalPages == 0) throw ArduinoFlashException("HEX file contains no data")

        logD(TAG, "Starting flash: $totalPages pages to write")
        port.setParameters(BAUD_FLASH, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try {
            resetIntoBootloader()
            sync()

            var pagesWritten = 0
            for (pageIndex in dirtyPages) {
                val byteAddr = pageIndex * PAGE_SIZE
                val wordAddr = byteAddr / 2
                loadAddress(wordAddr)
                programPage(flashImage.copyOfRange(byteAddr, byteAddr + PAGE_SIZE))
                pagesWritten++
                val percent = (pagesWritten * 100) / totalPages
                logD(TAG, "Page $pagesWritten/$totalPages at 0x%04X ($percent%%)".format(byteAddr))
                onProgress(percent)
            }

            leaveProgrammingMode()
            logD(TAG, "Flash complete, leaving programming mode")
        } catch (e: ArduinoFlashException) {
            logE(TAG, "Flash failed: ${e.message}")
            throw e
        } finally {
            port.setParameters(BAUD_NORMAL, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.setDTR(true)
            port.setRTS(true)
        }
    }

    private fun resetIntoBootloader() {
        logD(TAG, "Resetting into bootloader via DTR pulse")
        // CH340 inverts DTR, so low→high produces the reset pulse on the Nano
        port.setDTR(false)
        Thread.sleep(50)
        port.setDTR(true)
        Thread.sleep(50)
        // Drain any bytes the bootloader banner may have produced
        try { port.read(ByteArray(256), 100) } catch (_: Exception) {}
    }

    private fun sync() {
        logD(TAG, "Syncing with bootloader")
        val cmd = byteArrayOf(STK_GET_SYNC.toByte(), CRC_EOP.toByte())
        repeat(3) { attempt ->
            try {
                port.write(cmd, 500)
                val resp = readBytes(2)
                if (resp[0].toUByte().toInt() == STK_INSYNC && resp[1].toUByte().toInt() == STK_OK) {
                    logD(TAG, "Bootloader sync OK (attempt ${attempt + 1})")
                    return
                }
            } catch (e: ArduinoFlashException) {
                logD(TAG, "Sync attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) Thread.sleep(100) else throw e
            }
        }
        throw ArduinoFlashException("Failed to sync with bootloader after 3 attempts")
    }

    private fun loadAddress(wordAddr: Int) {
        val cmd = byteArrayOf(
            STK_LOAD_ADDRESS.toByte(),
            (wordAddr and 0xFF).toByte(),
            ((wordAddr shr 8) and 0xFF).toByte(),
            CRC_EOP.toByte(),
        )
        sendAndExpectOk(cmd)
    }

    private fun programPage(pageData: ByteArray) {
        val cmd = ByteArray(4 + PAGE_SIZE + 1)
        cmd[0] = STK_PROG_PAGE.toByte()
        cmd[1] = 0x00
        cmd[2] = 0x80.toByte()  // 128 bytes
        cmd[3] = 0x46            // 'F' = flash memory
        pageData.copyInto(cmd, 4)
        cmd[4 + PAGE_SIZE] = CRC_EOP.toByte()
        sendAndExpectOk(cmd)
    }

    private fun leaveProgrammingMode() {
        sendAndExpectOk(byteArrayOf(STK_LEAVE_PROGMODE.toByte(), CRC_EOP.toByte()))
    }

    private fun sendAndExpectOk(cmd: ByteArray) {
        port.write(cmd, 500)
        val resp = readBytes(2)
        val insync = resp[0].toUByte().toInt()
        val ok = resp[1].toUByte().toInt()
        if (insync != STK_INSYNC)
            throw ArduinoFlashException("Expected STK_INSYNC (0x14), got 0x%02X".format(insync))
        if (ok != STK_OK)
            throw ArduinoFlashException("Expected STK_OK (0x10), got 0x%02X".format(ok))
    }

    private fun readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var read = 0
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (read < count) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw ArduinoFlashException("Timeout waiting for bootloader response")
            val buf = ByteArray(count - read)
            val n = port.read(buf, remaining.coerceAtLeast(1).toInt())
            if (n > 0) {
                buf.copyInto(result, read, 0, n)
                read += n
            }
        }
        return result
    }

    private fun parseHex(inputStream: InputStream): ByteArray {
        val flash = ByteArray(FLASH_SIZE) { 0xFF.toByte() }
        val reader = inputStream.bufferedReader()
        var line = reader.readLine()
        while (line != null) {
            if (line.startsWith(":")) {
                val nibbles = line.substring(1)
                if (nibbles.length >= 10) {
                    val bytes = nibbles.chunked(2).map { it.toInt(16) }
                    val byteCount = bytes[0]
                    val address = (bytes[1] shl 8) or bytes[2]
                    when (bytes[3]) {
                        0x00 -> {
                            for (i in 0 until byteCount) {
                                val addr = address + i
                                if (addr < FLASH_SIZE) flash[addr] = bytes[4 + i].toByte()
                            }
                        }
                        0x01 -> return flash
                    }
                }
            }
            line = reader.readLine()
        }
        return flash
    }
}
