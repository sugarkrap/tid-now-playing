package com.tid.nowplaying

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

// HUR broadcast next_event_type values (NavigationStatus.NextTurnDetail.NextEvent proto enum).
private const val NEXT_EVENT_DEPART = 1
private const val NEXT_EVENT_SLIGHT_TURN = 3
private const val NEXT_EVENT_TURN = 4
private const val NEXT_EVENT_SHARP_TURN = 5
private const val NEXT_EVENT_U_TURN = 6
private const val NEXT_EVENT_ON_RAMP = 7
private const val NEXT_EVENT_OFFRAMP = 8
private const val NEXT_EVENT_FORK = 9
private const val NEXT_EVENT_MERGE = 10
private const val NEXT_EVENT_ROUNDABOUT_ENTER = 11
private const val NEXT_EVENT_ROUNDABOUT_EXIT = 12
private const val NEXT_EVENT_ROUNDABOUT_ENTER_AND_EXIT = 13
private const val NEXT_EVENT_STRAIGHT = 14
private const val NEXT_EVENT_DESTINATION = 18

// HUR broadcast turn_side values.
private const val TURN_SIDE_LEFT = 1
private const val TURN_SIDE_RIGHT = 2

data class NavInfo(
    val action: String,
    val distanceMeters: Int?,
    val road: String,
    val totalTimeSeconds: Long?,
    val nextEventType: Int? = null,
    val turnSide: Int? = null,
) {
    // Used by the UI (MainActivity). Not localized — always from action_text.
    val displayText: String get() = if (distanceMeters != null) {
        "$action in ${distanceMeters}m"
    } else {
        "$action: $road"
    }
}

// Returns a compact localized TID string that fits within the 10-char display.
// Format: "[sym][abbrev][spaces][dist]" — direction symbol first, then padded to 10 chars.
// Uses ASCII-only abbreviations (VFD is 7-bit).
fun NavInfo.toTidText(context: Context): String {
    val dist = distanceMeters

    // Broadcast-based nav with full event type — use rich compact format.
    if (nextEventType != null && dist != null) {
        return compactTidText(context, nextEventType, turnSide, dist)
    }

    // Notification-based nav or broadcast without event type.
    val cleanAction = action.trim()
    if (dist != null) {
        // HUR placeholder when approaching destination (no real action name available).
        if (cleanAction.startsWith("Action in", ignoreCase = true)) {
            return tidText("", context.getString(R.string.tid_abbrev_destination), formatDist(dist))
        }
        // Blank action (Waze "Action:" normalized to "") — show road name instead.
        if (cleanAction.isBlank()) {
            return if (road.isNotBlank()) road.take(10) else formatDist(dist)
        }
        return context.getString(R.string.tid_nav_with_distance, cleanAction, dist)
    }
    return if (cleanAction.isNotBlank()) "$cleanAction: $road" else road
}

private fun NavInfo.compactTidText(context: Context, eventType: Int, side: Int?, dist: Int): String {
    val left = side == TURN_SIDE_LEFT
    val right = side == TURN_SIDE_RIGHT
    // Direction symbol: < left, > right, ^ straight/unknown.
    val sym = if (left) "<" else if (right) ">" else "^"
    val d = formatDist(dist)

    return when (eventType) {
        NEXT_EVENT_SLIGHT_TURN, NEXT_EVENT_TURN, NEXT_EVENT_SHARP_TURN ->
            tidText(sym, context.getString(R.string.tid_abbrev_turn), d)
        NEXT_EVENT_U_TURN ->
            tidText(sym, context.getString(R.string.tid_abbrev_uturn), d)
        NEXT_EVENT_ON_RAMP, NEXT_EVENT_OFFRAMP ->
            tidText(sym, context.getString(R.string.tid_abbrev_ramp), d)
        NEXT_EVENT_FORK, NEXT_EVENT_MERGE ->
            tidText(sym, "", d)
        NEXT_EVENT_ROUNDABOUT_ENTER, NEXT_EVENT_ROUNDABOUT_EXIT, NEXT_EVENT_ROUNDABOUT_ENTER_AND_EXIT ->
            tidText("", context.getString(R.string.tid_abbrev_roundabout), d)
        NEXT_EVENT_STRAIGHT ->
            tidText("^", "", d)
        NEXT_EVENT_DESTINATION ->
            tidText("", context.getString(R.string.tid_abbrev_destination), d)
        NEXT_EVENT_DEPART ->
            tidText("", context.getString(R.string.tid_abbrev_depart), d)
        else ->
            context.getString(R.string.tid_nav_with_distance, action, dist)
    }
}

// Pads the TID string to exactly 10 chars when there's room: [symbol][abbrev][spaces][dist].
private fun tidText(symbol: String, abbrev: String, dist: String): String {
    val prefix = symbol + abbrev
    val slack = 10 - prefix.length - dist.length
    return if (slack > 0) prefix + " ".repeat(slack) + dist else prefix + dist
}

// Formats distance: meters below 1000, half-km steps above (1.0, 1.5, 2.0 … 99km max).
private fun formatDist(meters: Int): String {
    if (meters < 1000) return "${meters}m"
    val km = meters / 1000.0
    val rounded = (km * 2).roundToInt() / 2.0
    val capped = minOf(rounded, 99.0)
    return if (capped % 1.0 == 0.0) "${capped.toInt()}km" else "${capped}km"
}

object NavigationRepository {
    private val _navInfo = MutableStateFlow<NavInfo?>(null)
    val navInfo: StateFlow<NavInfo?> = _navInfo.asStateFlow()

    fun update(info: NavInfo?) {
        _navInfo.value = info
    }
}
