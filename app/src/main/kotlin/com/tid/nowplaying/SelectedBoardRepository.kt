package com.tid.nowplaying

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "board_prefs"
private const val KEY_BOARD = "selected_board"

object SelectedBoardRepository {
    private val _board = MutableStateFlow(ArduinoBoard.NANO)
    val board: StateFlow<ArduinoBoard> = _board.asStateFlow()

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BOARD, null)
        _board.value = ArduinoBoard.entries.firstOrNull { it.name == saved } ?: ArduinoBoard.NANO
    }

    fun set(context: Context, board: ArduinoBoard) {
        _board.value = board
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOARD, board.name).apply()
    }
}
