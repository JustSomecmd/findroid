package dev.jdtech.jellyfin

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.session.MediaSession
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

abstract class BasePlayerActivity : AppCompatActivity() {

    abstract val viewModel: PlayerViewModel

    /** Whether playback continues with the screen off or the app in the background. */
    protected abstract fun isBackgroundPlaybackEnabled(): Boolean

    // Read once, so changing the setting mid-playback can't leave things half set up
    protected val backgroundPlayback: Boolean by lazy { isBackgroundPlaybackEnabled() }

    private var mediaSession: MediaSession? = null
    private var wasPip: Boolean = false

    /** Set when the picture-in-picture window is closed to keep listening in the background. */
    protected var continueInBackgroundAfterPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onStart() {
        super.onStart()

        val session =
            mediaSession
                ?: MediaSession.Builder(this, viewModel.player)
                    .setSessionActivity(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, javaClass),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                    .build()
                    .also { mediaSession = it }

        if (backgroundPlayback) {
            // Started while the player is on screen, which Android allows
            PlaybackService.currentSession = session
            startService(Intent(this, PlaybackService::class.java))
            viewModel.setVideoEnabled(true)
            viewModel.onPlayerShown()
        }
    }

    override fun onResume() {
        super.onResume()

        if (wasPip) {
            wasPip = false
        } else if (!backgroundPlayback) {
            viewModel.player.playWhenReady = viewModel.playWhenReady
        }
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()

        if (isInPictureInPictureMode) {
            wasPip = true
        } else if (backgroundPlayback && !isFinishing) {
            // Keeps playing; only save where it is
            viewModel.updatePlaybackProgress()
        } else {
            viewModel.playWhenReady = viewModel.player.playWhenReady
            viewModel.player.playWhenReady = false
            viewModel.updatePlaybackProgress()
        }
    }

    override fun onStop() {
        super.onStop()

        // The screen went off with the small window showing, which stops it too. With background
        // playback on, it plays on with the sound only, and the window comes back with the screen.
        // Closing it later still ends playback.
        val screenOffInPip = wasPip && backgroundPlayback && !isFinishing && !isScreenOn()

        // Closing picture-in-picture normally ends playback; this keeps it going instead
        if (wasPip && continueInBackgroundAfterPip) {
            wasPip = false
            continueInBackgroundAfterPip = false
        }

        if (!backgroundPlayback || isFinishing) {
            releaseSession()
        } else if (!wasPip || screenOffInPip) {
            // Nothing is shown, so stop decoding video and only play the sound
            viewModel.setVideoEnabled(false)
            viewModel.onPlayerHidden()
        }

        if (wasPip && !screenOffInPip) {
            finish()
        }
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun releaseSession() {
        val session = mediaSession ?: return
        if (backgroundPlayback) {
            if (PlaybackService.currentSession === session) {
                PlaybackService.currentSession = null
            }
            stopService(Intent(this, PlaybackService::class.java))
        }
        session.release()
        mediaSession = null
    }

    protected fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    protected fun configureInsets(playerControls: View) {
        playerControls.setOnApplyWindowInsetsListener { _, windowInsets ->
            val cutout = windowInsets.displayCutout
            // The camera cutout is on the left or the right depending on which way the phone is
            // held. Padding both sides equally keeps the controls clear of it and centred, instead
            // of shifting them sideways every time the phone is turned around.
            val horizontal = maxOf(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetRight ?: 0)
            val vertical = maxOf(cutout?.safeInsetTop ?: 0, cutout?.safeInsetBottom ?: 0)
            playerControls.updatePadding(
                left = horizontal,
                top = vertical,
                right = horizontal,
                bottom = vertical,
            )
            return@setOnApplyWindowInsetsListener windowInsets
        }
    }
}
