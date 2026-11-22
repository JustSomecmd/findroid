package dev.jdtech.jellyfin

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps the player running with the screen off or the app in the background, when background
 * playback is turned on in the settings.
 *
 * The player itself belongs to the player screen. This service shows the playback notification,
 * keeps Android from stopping the app while it plays, and keeps the CPU and Wi-Fi awake while
 * something is playing.
 */
class PlaybackService : MediaSessionService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observedPlayer: Player? = null

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateLocks(shouldStayAwake(player), streams(player))
            }
        }

    /**
     * Playing, or loading in order to play. Loading counts too, so the phone doesn't fall asleep
     * while the stream is switched or buffers with the screen off.
     */
    private fun shouldStayAwake(player: Player): Boolean =
        player.playWhenReady &&
            (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING)

    /**
     * Whether what plays may come over the network, so Wi-Fi has to stay awake too: all but a file
     * on the phone, which is a path or a file or content address.
     */
    private fun streams(player: Player): Boolean {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return true
        val scheme = uri.scheme ?: return false
        return scheme != "file" && scheme != "content"
    }

    override fun onCreate() {
        super.onCreate()
        currentSession?.let { attach(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The player screen starts the service again whenever it has a new session
        currentSession?.let { attach(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun attach(session: MediaSession) {
        if (!isSessionAdded(session)) {
            addSession(session)
        }
        if (observedPlayer !== session.player) {
            observedPlayer?.removeListener(playerListener)
            observedPlayer = session.player
            session.player.addListener(playerListener)
            updateLocks(shouldStayAwake(session.player), streams(session.player))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        currentSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recent apps stops playback
        currentSession?.player?.pause()
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        observedPlayer?.removeListener(playerListener)
        observedPlayer = null
        updateLocks(isPlaying = false, streaming = false)
        super.onDestroy()
    }

    /** The processor awake while it plays, and Wi-Fi too while what plays is a stream. */
    private fun updateLocks(isPlaying: Boolean, streaming: Boolean) {
        if (isPlaying) {
            val wake =
                wakeLock
                    ?: getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Findroid:playback")
                        .apply { setReferenceCounted(false) }
                        .also { wakeLock = it }
            val wifi =
                wifiLock
                    ?: applicationContext
                        .getSystemService(WifiManager::class.java)
                        .createWifiLock(WIFI_LOCK_MODE, "Findroid:playback")
                        .apply { setReferenceCounted(false) }
                        .also { wifiLock = it }
            if (!wake.isHeld) wake.acquire(WAKE_LOCK_TIMEOUT_MS)
            // A video on the phone needs no Wi-Fi, which can then sleep
            if (streaming && !wifi.isHeld) wifi.acquire()
            if (!streaming && wifi.isHeld) wifi.release()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    companion object {
        /** The session of the player screen that is open, if background playback is on. */
        @Volatile var currentSession: MediaSession? = null

        // Long enough for any movie; renewed every time playback starts again
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        @Suppress("DEPRECATION")
        private const val WIFI_LOCK_MODE = WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
