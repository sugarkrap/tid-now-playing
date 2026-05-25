package com.tid.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SelectedBoardRepository {
    private val _board = MutableStateFlow(ArduinoBoard.NANO)
    val board: StateFlow<ArduinoBoard> = _board.asStateFlow()

    fun set(board: ArduinoBoard) { _board.value = board }
}
