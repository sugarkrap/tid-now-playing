package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlayingInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
)

object NowPlayingRepository {
    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<NowPlayingInfo?> = _nowPlaying.asStateFlow()

    fun update(info: NowPlayingInfo?) {
        _nowPlaying.value = info
    }
}
