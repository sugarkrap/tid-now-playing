package com.tid.nowplaying

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "NowPlayingListener"
private const val CHANNEL_ID = "now_playing"
private const val NOTIF_ID = 1

class NowPlayingListenerService : NotificationListenerService() {

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    // Shared serial manager owned by TidApplication — do NOT call connect/disconnect here.
    private val tidSerial get() = (application as TidApplication).serialManager

    private val mediaSessionManager by lazy {
        getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val componentName by lazy {
        ComponentName(this, NowPlayingListenerService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeControllers = mutableListOf<MediaController>()
    private var lastSentDisplayText: String? = null

    private val carConnection by lazy { CarConnection(this) }
    private val carConnectionObserver = Observer<Int> { type ->
        when (type) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> logD(TAG, "Android Auto connected (wireless)")
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> logD(TAG, "Android Auto disconnected")
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

    override fun onCreate() {
        super.onCreate()
        carConnection.type.observeForever(carConnectionObserver)
        // Re-send current state whenever the port (re)opens.
        scope.launch {
            UsbStatusRepository.status.collect { status ->
                if (status is UsbStatus.Ready) {
                    lastSentDisplayText = null
                    refreshNowPlaying()
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
        carConnection.type.removeObserver(carConnectionObserver)
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

        // ZLink / Android Auto proxy sessions have null PlaybackState during active playback;
        // fall back to any session that at least has a title.
        val metadataFallbackController = activeControllers
            .firstOrNull { it.playbackState == null && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

        val info = (playingController ?: metadataFallbackController)?.metadata?.toNowPlayingInfo()
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
        }
        // Idle: send nothing — the Arduino handles idle phrases autonomously.

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
