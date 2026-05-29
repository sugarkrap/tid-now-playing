package com.tid.nowplaying

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.RemoteException
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.nwd.btmusic.aidl.BtmusicAidlCallback
import com.nwd.btmusic.aidl.BtmusicFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "NowPlayingListener"
private const val TAG_NAV = "NavListener"
private const val CHANNEL_ID = "now_playing"
private const val NOTIF_ID = 1

private const val HUR_PACKAGE = "com.andrerinas.headunitrevived"

// Matches localized "In 500 m — Turn right" (any prefix, various dash chars).
// Also supports "500 m — Turn right" or similar formats.
private val NAV_TITLE_RE = Regex("""^.*?(\d+)\s*m\s*[-–—]\s*(.+)$""")

// How long each rotation step stays on the TID (ms).
private const val ROT_DIRECTIONS_MS = 10_000L
private const val ROT_TIME_MS       =  8_000L
private const val ROT_MEDIA_MS      =  8_000L

class NowPlayingListenerService : NotificationListenerService() {

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    // Shared serial manager owned by TidApplication — do NOT call connect/disconnect here.
    private val tidSerial get() = (application as TidApplication).serialManager

    private val localizedCtx: android.content.Context get() = LocaleManager.applyLocale(this)

    private val mediaSessionManager by lazy {
        getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val componentName by lazy {
        ComponentName(this, NowPlayingListenerService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeControllers = mutableListOf<MediaController>()
    private var lastSentDisplayText: String? = null

    // Navigation semi-idle rotation state (main thread only).
    private var lastNavKey: String? = null   // "action:road" identity of current maneuver
    private var lastNavNotifId: Int? = null  // tracks which notification is the active nav one
    private var rotationJob: Job? = null

    // ── Bluetooth AVRCP music (proprietary nwdapp stack) ─────────────────────

    private var btMusicFeature: BtmusicFeature? = null

    private val btMusicCallback = object : BtmusicAidlCallback.Stub() {
        override fun onBtmusicPlayInfoChange(
            isPlaying: Boolean, isBtMusicPlaying: Boolean, currentPosition: Int, totalSize: Int,
            name: String?, artist: String?, album: String?, str4: String?, i3: Int, i4: Int,
        ) {
            // isPlaying is always false in the ROM's dispatch; isBtMusicPlaying is the real state.
            val hasInfo = !name.isNullOrBlank() || !artist.isNullOrBlank()
            val info = if (hasInfo) {
                BluetoothMusicInfo(
                    title = name?.takeIf { it.isNotBlank() },
                    artist = artist?.takeIf { it.isNotBlank() },
                    isPlaying = isBtMusicPlaying,
                )
            } else {
                null
            }
            logD(TAG, "BT music: playing=$isBtMusicPlaying name=$name artist=$artist")
            BluetoothMusicRepository.update(info)
            scope.launch { refreshDisplay() }
        }
    }

    private val btMusicConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            btMusicFeature = BtmusicFeature.Stub.asInterface(binder)
            try {
                btMusicFeature?.registerBtmusicCallback(btMusicCallback)
                btMusicFeature?.requestCurrentBtMusicInfo()
            } catch (e: RemoteException) {
                logD(TAG, "BT music callback registration failed: $e")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            btMusicFeature = null
            BluetoothMusicRepository.update(null)
        }
    }

    // ── Radio / RDS via kernel service ────────────────────────────────────────

    private var kernelServiceBinder: IBinder? = null

    private val radioCallback = object : RadioCallbackBinder() {
        override fun notifyCurrentFrequency(band: Byte, freqKhz: Int, psName: String?, extra: Int) {
            RadioRepository.updateFrequency(band, freqKhz, psName)
            scope.launch { refreshDisplay() }
        }

        override fun notifyRtMessage(rt: String?) {
            RadioRepository.updateRtMessage(rt)
            scope.launch { refreshDisplay() }
        }
    }

    private val kernelServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            kernelServiceBinder = binder
            try {
                binder.registRadioCallback(radioCallback)
            } catch (e: RemoteException) {
                logD(TAG, "Radio callback registration failed: $e")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            kernelServiceBinder = null
            RadioRepository.update(null)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshNowPlaying()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateControllers(sessions.orEmpty())
        }

    // ── Navigation via HUR broadcast (head unit blocks HUR notifications) ─────

    private var navTimeoutJob: Job? = null

    private val hurNavReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "com.andrerinas.headunitrevived.NAVIGATION_UPDATE") return

            // Normalize Waze's empty placeholder ("Action:" / "Action") to blank.
            val rawAction = intent.getStringExtra("action_text") ?: ""
            val actionText = rawAction.trim().let {
                if (it.equals("Action:", ignoreCase = true) || it.equals("Action", ignoreCase = true)) "" else it
            }
            val road = intent.getStringExtra("road") ?: ""
            val distanceMeters = intent.getIntExtra("distance_meters", -1).takeIf { it >= 0 }
            val totalTimeSeconds = intent.getLongExtra("total_time_seconds", -1L).takeIf { it >= 0L }
            val nextEventType = intent.getIntExtra("next_event_type", -1).takeIf { it >= 0 }
            val turnSide = intent.getIntExtra("turn_side", 3).takeIf { it in 1..2 }

            val roundaboutExit = intent.getIntExtra("roundabout_exit_number", -1).takeIf { it >= 0 }
            logD(TAG_NAV, "HUR broadcast action='$actionText' road='$road' dist=$distanceMeters totalTime=$totalTimeSeconds eventType=$nextEventType side=$turnSide roundaboutExit=$roundaboutExit")

            if (actionText.isBlank() && road.isBlank()) {
                // Navigation likely stopped / no active route.
                logD(TAG_NAV, "HUR broadcast empty — clearing nav")
                lastNavKey = null
                lastNavNotifId = null
                rotationJob?.cancel()
                rotationJob = null
                NavigationRepository.update(null)
                refreshDisplay()
                return
            }

            val navInfo = NavInfo(
                action = actionText,
                distanceMeters = distanceMeters,
                road = road,
                totalTimeSeconds = totalTimeSeconds,
                nextEventType = nextEventType,
                turnSide = turnSide,
            )
            NavigationRepository.update(navInfo)

            val newKey = "$actionText:$road"
            if (newKey != lastNavKey) {
                lastNavKey = newKey
                rotationJob?.cancel()
                rotationJob = null
            }
            // refreshDisplay sends the latest nav text (dedup prevents double-sends).
            // startNavRotation is a no-op when cycling is disabled.
            refreshDisplay()
            startNavRotation()

            // Reset the nav timeout — if no update for 30s, clear nav.
            navTimeoutJob?.cancel()
            navTimeoutJob = scope.launch {
                delay(30_000)
                logD(TAG_NAV, "Nav timeout — clearing nav")
                lastNavKey = null
                lastNavNotifId = null
                rotationJob?.cancel()
                rotationJob = null
                NavigationRepository.update(null)
                refreshDisplay()
            }
        }
    }

    // ── Navigation via HUR notification (fallback) ────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != HUR_PACKAGE) return

        val title = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: return
        val text  = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""

        // Log every HUR notification so we can see channel/format in the debug view.
        logD(TAG_NAV, "HUR notif id=${sbn.id} ch=\"${sbn.notification.channelId}\" title=\"$title\" text=\"$text\"")

        val titleMatch = NAV_TITLE_RE.matchEntire(title)
        val action: String
        val distanceMeters: Int?
        if (titleMatch != null) {
            distanceMeters = titleMatch.groupValues[1].toIntOrNull()
            action = titleMatch.groupValues[2]
        } else {
            // HUR sometimes posts a notification with just the action text
            // (e.g., when NEXTTURNDETAILS arrives before NEXTTURNDISTANCEANDTIME).
            distanceMeters = null
            action = title
        }
        // HUR's text is localized "Street: {name}" — strip the label before the first ": ".
        val road = text.indexOf(": ").takeIf { it >= 0 }?.let { text.substring(it + 2) } ?: text

        logD(TAG_NAV, "Nav parsed: action='$action' dist=${distanceMeters}m road='$road' display='${NavInfo(action, distanceMeters, road, null).displayText}'")

        lastNavNotifId = sbn.id
        val navInfo = NavInfo(action = action, distanceMeters = distanceMeters, road = road, totalTimeSeconds = null)
        NavigationRepository.update(navInfo)

        val newKey = "$action:$road"
        if (newKey != lastNavKey) {
            lastNavKey = newKey
            rotationJob?.cancel()
            rotationJob = null
        }
        refreshDisplay()
        startNavRotation()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != HUR_PACKAGE) return
        if (sbn.id != lastNavNotifId) return

        logD(TAG_NAV, "Nav notification removed (id=${sbn.id})")
        lastNavKey = null
        lastNavNotifId = null
        rotationJob?.cancel()
        rotationJob = null
        NavigationRepository.update(null)
        refreshDisplay()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // Register HUR nav broadcast receiver (head unit ROM blocks HUR notifications).
        val filter = IntentFilter("com.andrerinas.headunitrevived.NAVIGATION_UPDATE")
        registerReceiver(hurNavReceiver, filter, Context.RECEIVER_EXPORTED)
        // Bind to the proprietary BT music service to receive AVRCP track metadata.
        try {
            val btIntent = Intent("com.nwd.bt.music.ACTION_BTMUSIC_SERVICE").apply {
                setClassName("com.nwd.android.phone", "com.nwd.bt.music.BtMusicControlService")
            }
            bindService(btIntent, btMusicConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logD(TAG, "BT music service bind failed: $e")
        }
        // Bind to the kernel service to receive radio/RDS metadata.
        try {
            val kernelIntent = Intent("com.nwd.kernel.service.KernelService").apply {
                setPackage("com.nwd.kernel")
            }
            bindService(kernelIntent, kernelServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logD(TAG, "Kernel service bind failed: $e")
        }
        // Re-send current state whenever the port (re)opens.
        scope.launch {
            UsbStatusRepository.status.collect { status ->
                if (status is UsbStatus.Ready) {
                    lastSentDisplayText = null
                    refreshDisplay()
                }
            }
        }
        // Keep the Arduino's idle timer alive while content is actively displayed.
        scope.launch {
            while (true) {
                delay(20_000)
                if (lastSentDisplayText != null) {
                    tidSerial.send("keepalive\n")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        navTimeoutJob?.cancel()
        try { unregisterReceiver(hurNavReceiver) } catch (_: IllegalArgumentException) {}
        try {
            btMusicFeature?.unregisterBtmusicCallback(btMusicCallback)
            unbindService(btMusicConnection)
        } catch (_: Exception) {}
        try {
            kernelServiceBinder?.unRegistRadioCallback(radioCallback)
            unbindService(kernelServiceConnection)
        } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        createNotificationChannel()
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
        updateControllers(mediaSessionManager.getActiveSessions(componentName))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        clearControllers()
        NowPlayingRepository.update(null)
        notificationManager.cancel(NOTIF_ID)
    }

    // ── Media ─────────────────────────────────────────────────────────────────

    private fun updateControllers(sessions: List<MediaController>) {
        clearControllers()
        sessions.forEach { controller ->
            controller.registerCallback(mediaCallback)
            activeControllers.add(controller)
        }
        refreshNowPlaying()
    }

    private fun clearControllers() {
        activeControllers.forEach { it.unregisterCallback(mediaCallback) }
        activeControllers.clear()
    }

    private fun refreshNowPlaying() {
        activeControllers.forEach {
            val stateName = when (it.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "PLAYING"
                PlaybackState.STATE_PAUSED -> "PAUSED"
                PlaybackState.STATE_STOPPED -> "STOPPED"
                PlaybackState.STATE_BUFFERING -> "BUFFERING"
                else -> "state=${it.playbackState?.state}"
            }
            logD(TAG, "Controller: ${it.packageName} — $stateName — ${it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
        }

        val playingController = activeControllers
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

        // ZLink / Android Auto proxy sessions have null PlaybackState during active playback;
        // fall back to any session that at least has a title.
        val metadataFallbackController = activeControllers
            .firstOrNull { it.playbackState == null && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

        val info = (playingController ?: metadataFallbackController)?.metadata?.toNowPlayingInfo()
        NowPlayingRepository.update(info)

        if (info != null) {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(info.title ?: "Unknown title")
                .setContentText(listOfNotNull(info.artist, info.album).joinToString(" — "))
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            notificationManager.notify(NOTIF_ID, notification)
        } else {
            notificationManager.cancel(NOTIF_ID)
        }

        refreshDisplay()
    }

    // ── Display ───────────────────────────────────────────────────────────────

    // Decides what to send to the TID. No-op when the rotation coroutine is managing the display.
    private fun refreshDisplay() {
        if (rotationJob?.isActive == true) return

        val nav = NavigationRepository.navInfo.value
        if (nav != null) {
            sendToTid(nav.toTidText(localizedCtx))
            return
        }

        val mediaInfo = NowPlayingRepository.nowPlaying.value
        if (mediaInfo != null) {
            mediaInfo.displayText?.let { sendToTid(it) }
            return
        }

        val btInfo = BluetoothMusicRepository.info.value
        if (btInfo?.isPlaying == true) {
            btInfo.displayText?.let { sendToTid(it) }
            return
        }

        val radioInfo = RadioRepository.info.value
        if (radioInfo != null) {
            radioInfo.displayText?.let { sendToTid(it) }
            return
        }

        // HUR reports STATE_STOPPED (not STATE_PAUSED) when Android Auto media pauses.
        val pausedController = activeControllers.firstOrNull {
            it.playbackState?.state.let { s ->
                s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED
            }
        }
        if (pausedController != null || btInfo != null) {
            sendToTid(localizedCtx.getString(R.string.tid_paused))
        }
        // Idle: send nothing — the Arduino handles idle phrases autonomously.
    }

    // Cycles through: directions → remaining time → current media → repeat.
    // Only starts when cycling is enabled in settings.
    // When distance < 3 km, holds on nav only (no media cycling).
    private fun startNavRotation() {
        if (!AppSettings.enableCycling) return
        if (rotationJob?.isActive == true) return
        rotationJob = scope.launch {
            while (true) {
                val nav = NavigationRepository.navInfo.value ?: break

                // Short-distance lock: < 3 km → stay on nav, poll fast, skip media.
                if ((nav.distanceMeters ?: Int.MAX_VALUE) < 3000) {
                    sendToTid(nav.toTidText(localizedCtx))
                    delay(250L)
                    continue
                }

                // Directions phase: poll every 250 ms so distance changes feel instant.
                var elapsed = 0L
                while (elapsed < ROT_DIRECTIONS_MS) {
                    val current = NavigationRepository.navInfo.value ?: break
                    if ((current.distanceMeters ?: Int.MAX_VALUE) < 3000) break
                    sendToTid(current.toTidText(localizedCtx))
                    delay(250L)
                    elapsed += 250L
                }

                if (NavigationRepository.navInfo.value == null) break

                // Remaining trip time (only available from broadcast-based nav).
                val secs = NavigationRepository.navInfo.value?.totalTimeSeconds
                if (secs != null) {
                    sendToTid(formatRemainingTime(localizedCtx, secs))
                    delay(ROT_TIME_MS)
                }

                // Current media: MediaSession → BT AVRCP → Radio (last resort).
                val mediaText = NowPlayingRepository.nowPlaying.value?.displayText
                    ?: BluetoothMusicRepository.info.value?.takeIf { it.isPlaying }?.displayText
                    ?: RadioRepository.info.value?.displayText
                if (mediaText != null) {
                    sendToTid(mediaText)
                    delay(ROT_MEDIA_MS)
                }
            }
        }
    }

    private fun sendToTid(text: String) {
        if (text == lastSentDisplayText) return
        lastSentDisplayText = text
        tidSerial.send("$text\n")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Now Playing",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }
}

private fun formatRemainingTime(context: android.content.Context, seconds: Long): String {
    val hours   = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours >= 1   -> context.getString(R.string.time_left_hm, hours.toInt(), minutes.toInt())
        minutes >= 1 -> context.getString(R.string.time_left_m, minutes.toInt())
        else         -> context.getString(R.string.time_left_less_1min)
    }
}

private fun MediaMetadata.toNowPlayingInfo() = NowPlayingInfo(
    title  = getString(MediaMetadata.METADATA_KEY_TITLE),
    artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
    album  = getString(MediaMetadata.METADATA_KEY_ALBUM),
)
