package dev.jdtech.jellyfin.audio

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the audio player going with the app in the background or closed, with its notification and
 * the phone's media controls. The player itself is [AudioPlayer]'s.
 */
@AndroidEntryPoint
class AudioService : MediaSessionService() {
    @Inject lateinit var audio: AudioPlayer

    override fun onCreate() {
        super.onCreate()
        attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again for each episode the player opens
        attach()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun attach() {
        val session = audio.session ?: return
        if (!isSessionAdded(session)) addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        audio.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from the recent apps: it plays on, as podcast apps do, unless it's paused
        if (audio.session?.player?.playWhenReady != true) {
            audio.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
