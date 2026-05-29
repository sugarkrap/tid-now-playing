package com.tid.nowplaying

const val REQUIRED_FIRMWARE_VERSION = "1.2.0"

enum class ArduinoBoard(
    val displayName: String,
    val hexAsset: String,
) {
    NANO("Arduino Nano", "firmware/nano.hex"),
    UNO("Arduino Uno", "firmware/uno.hex"),
    MICRO("Arduino Micro", "firmware/micro.hex"),
    NANO_DIAG("Arduino Nano (diag)", "firmware/nano_diag.hex"),
}
