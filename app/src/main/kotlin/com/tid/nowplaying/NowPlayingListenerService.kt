package com.tid.nowplaying

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

class NowPlayingListenerService : NotificationListenerService() {

    private val mediaSessionManager by lazy {
        getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val componentName by lazy {
        ComponentName(this, NowPlayingListenerService::class.java)
    }

    private val activeControllers = mutableListOf<MediaController>()

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshNowPlaying()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateControllers(sessions.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
        updateControllers(mediaSessionManager.getActiveSessions(componentName))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        clearControllers()
        NowPlayingRepository.update(null)
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
        val controller = activeControllers
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: activeControllers.firstOrNull()
        NowPlayingRepository.update(controller?.metadata?.toNowPlayingInfo())
    }
}

private fun MediaMetadata.toNowPlayingInfo() = NowPlayingInfo(
    title = getString(MediaMetadata.METADATA_KEY_TITLE),
    artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
    album = getString(MediaMetadata.METADATA_KEY_ALBUM),
)
