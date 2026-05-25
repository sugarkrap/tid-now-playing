package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SerialLogRepository {
    private val _lastSent = MutableStateFlow<String?>(null)
    val lastSent: StateFlow<String?> = _lastSent.asStateFlow()

    fun logSent(text: String) {
        _lastSent.value = text.trimEnd('\n', '\r')
    }
}
