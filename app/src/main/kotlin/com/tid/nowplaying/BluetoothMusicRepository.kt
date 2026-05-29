package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BluetoothMusicInfo(
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
) {
    val displayText: String?
        get() = when {
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title - $artist"
            !title.isNullOrBlank() -> title
            else -> null
        }
}

object BluetoothMusicRepository {
    private val _info = MutableStateFlow<BluetoothMusicInfo?>(null)
    val info: StateFlow<BluetoothMusicInfo?> = _info.asStateFlow()

    fun update(info: BluetoothMusicInfo?) {
        _info.value = info
    }
}
