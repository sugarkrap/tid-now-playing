package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOG_LINES = 50

enum class LogLevel { D, I, W, E }

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

object DebugLogRepository {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
        )
        _logs.value = (_logs.value + entry).takeLast(MAX_LOG_LINES)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}

fun logD(tag: String, message: String) {
    android.util.Log.d(tag, message)
    DebugLogRepository.log(LogLevel.D, tag, message)
}

fun logE(tag: String, message: String) {
    android.util.Log.e(tag, message)
    DebugLogRepository.log(LogLevel.E, tag, message)
}
