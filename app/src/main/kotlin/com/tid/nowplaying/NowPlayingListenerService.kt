package com.tid.nowplaying

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat

private const val TAG = "NowPlayingListener"
private const val CHANNEL_ID = "now_playing"
private const val NOTIF_ID = 1

private val IDLE_TEXTS = listOf(
    "Welcome to your Opel",
    "Opel, wir liben autos",
    "Zoom zoom Opel",
    "Opel Corsa Comfort",
    "Opel Corsa C 1.2L",
    "Have fun on the road!",
    "Off to a joyride?",
)

class NowPlayingListenerService : NotificationListenerService() {

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var tidSerial: TidSerialManager

    private val mediaSessionManager by lazy {
        getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val componentName by lazy {
        ComponentName(this, NowPlayingListenerService::class.java)
    }

    private val activeControllers = mutableListOf<MediaController>()
    private var lastSentDisplayText: String? = null
    private var currentIdleText: String = IDLE_TEXTS.random()

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshNowPlaying()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateControllers(sessions.orEmpty())
        }

    override fun onCreate() {
        super.onCreate()
        tidSerial = TidSerialManager(this)
        tidSerial.onPortReady = {
            // Force re-evaluation so the current state is sent over the newly opened port
            lastSentDisplayText = null
            refreshNowPlaying()
        }
        tidSerial.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        tidSerial.disconnect()
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
        val pausedController = activeControllers
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val info = playingController?.metadata?.toNowPlayingInfo()
        NowPlayingRepository.update(info)

        val displayText = info?.displayText
        if (displayText != null) {
            if (displayText != lastSentDisplayText) {
                lastSentDisplayText = displayText
                tidSerial.send("$displayText\n")
            }
        } else if (pausedController != null) {
            val pausedText = "Paused"
            if (pausedText != lastSentDisplayText) {
                lastSentDisplayText = pausedText
                tidSerial.send("$pausedText\n")
            }
        } else {
            // Only pick a new random idle text when transitioning from playing/paused to idle
            if (lastSentDisplayText != null && lastSentDisplayText !in IDLE_TEXTS) {
                currentIdleText = IDLE_TEXTS.random()
            }
            if (currentIdleText != lastSentDisplayText) {
                lastSentDisplayText = currentIdleText
                tidSerial.send("$currentIdleText\n")
            }
        }

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

private fun MediaMetadata.toNowPlayingInfo() = NowPlayingInfo(
    title = getString(MediaMetadata.METADATA_KEY_TITLE),
    artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
    album = getString(MediaMetadata.METADATA_KEY_ALBUM),
)
