package dev.jdtech.jellyfin.jrwiaudio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.MainActivity
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the JRWI audio player is playing, and how. */
data class JrwiAudioState(
    val item: JrwiItem? = null,
    val showName: String? = null,
    /** The episode's picture: an archiver path, or the phone's copy as a file address. */
    val picture: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleep: JrwiSleep? = null,
    /** Played from the copy on the phone. */
    val fromPhone: Boolean = false,
    val error: String? = null,
) {
    /** Something is loaded, playing or not, which the mini player shows. */
    val active: Boolean
        get() = item != null || loading
}

/** A sleep timer: pausing at a time, or at the end of the episode. */
data class JrwiSleep(val endsAtMs: Long? = null, val atEnd: Boolean = false)

/**
 * The JRWI audio player, apart from the video player: plays an episode's audio from the archiver,
 * or from the phone when it's downloaded, in the background with its notification, as a podcast app
 * does. Its progress goes to the archive as the video player's did: every 15 seconds while it
 * plays, and when it's paused or stopped also to the episode's video (decision 8). Most of the way
 * through counts as watched.
 *
 * It keeps playing with the app closed, through [JrwiAudioService], and has a sleep timer, a speed
 * that's remembered, and goes back a few seconds when it's played again after a long pause, as
 * storii does.
 */
@Singleton
class JrwiAudioPlayer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(JrwiAudioState(speed = savedSpeed()))
    val state: StateFlow<JrwiAudioState> = _state.asStateFlow()

    private var exo: ExoPlayer? = null

    /** The session for the notification and Bluetooth controls, while something is loaded. */
    var session: MediaSession? = null
        private set

    private val _openRequested = MutableStateFlow(false)

    /** Set when the notification was tapped, for the app to open the player. */
    val openRequested: StateFlow<Boolean> = _openRequested.asStateFlow()

    fun requestOpen() {
        if (_state.value.active) _openRequested.value = true
    }

    fun openHandled() {
        _openRequested.value = false
    }

    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var sleepJob: Job? = null
    private var lastSaveMs = 0L
    private var lastSave: Job? = null
    private var pausedAtMs: Long? = null

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exo ?: return
                if (isPlaying) {
                    pausedAtMs = null
                    startTicking()
                } else if (player.playbackState != Player.STATE_ENDED) {
                    // Paused: saved, and handed to the video at the same moment
                    pausedAtMs = SystemClock.elapsedRealtime()
                    save(handOff = true)
                }
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    save(handOff = true, ended = true)
                    if (_state.value.sleep?.atEnd == true) cancelSleep()
                }
                publish()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Played again after a long pause, such as a call: a few seconds back
                val player = exo ?: return
                val pausedAt = pausedAtMs
                if (playWhenReady && pausedAt != null) {
                    val paused = SystemClock.elapsedRealtime() - pausedAt
                    if (paused >= REWIND_AFTER_PAUSE_MS) {
                        player.seekTo(max(0L, player.currentPosition - REWIND_MS))
                    }
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.localizedMessage) }
            }
        }

    /**
     * Plays the audio whose post is [postId], where it was left unless [fromStart]. The same
     * episode only plays on, from the start when asked.
     */
    fun play(postId: Long, fromStart: Boolean = false) {
        val player = exo
        val current = _state.value.item
        if (player != null && current != null && current.audio?.postId == postId) {
            if (fromStart) player.seekTo(0)
            if (fromStart || player.isPlaying) {
                player.play()
                return
            }
            loadJob?.cancel()
            loadJob = scope.launch {
                // Played on elsewhere meanwhile, such as in the video, it goes on from there
                val saved =
                    try {
                        jrwiProgress.progress(current, audio = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (saved != null && !saved.finished) {
                    val savedMs = (saved.position * 1000).toLong()
                    if (abs(savedMs - player.currentPosition) > RESYNC_MS) player.seekTo(savedMs)
                }
                player.play()
            }
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            // What was playing keeps its place
            if (current != null) save(handOff = true)
            _state.update { JrwiAudioState(loading = true, speed = it.speed, sleep = it.sleep) }
            val download = jrwiDownloads.forAudio(postId)
            val item =
                try {
                    jrwiApi.getItem(postId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without a connection, what was kept with the download
                    download?.let { jrwiDownloads.savedItem(it.itemId) }
                        ?: run {
                            Timber.w(e, "Could not load JRWI audio $postId")
                            _state.update {
                                it.copy(loading = false, error = e.localizedMessage ?: "")
                            }
                            return@launch
                        }
                }
            val audio = item.audio
            val file = download?.let { jrwiDownloads.audioFile(it.itemId) }?.takeIf { it.exists() }
            val url =
                file?.let { Uri.fromFile(it).toString() } ?: jrwiApi.fileUrlWithToken(audio?.url)
            if (audio == null || url == null) {
                _state.update { it.copy(loading = false, error = "") }
                return@launch
            }
            val progress = if (fromStart) null else jrwiProgress.progress(item, audio = true)
            val startMs = ((progress?.takeIf { !it.finished }?.position ?: 0.0) * 1000).toLong()
            val showName = jrwiProgress.showNameOrNull(item.show) ?: download?.showName
            val localPicture =
                download?.let { jrwiDownloads.thumbnailFile(it.itemId) }?.takeIf { it.exists() }
            val picture = localPicture?.let { Uri.fromFile(it).toString() } ?: item.thumbnail
            val artwork =
                localPicture?.let { Uri.fromFile(it) }
                    ?: jrwiApi.fileUrlWithToken(item.thumbnail)?.toUri()
            val mediaItem =
                MediaItem.Builder()
                    .setMediaId("jrwi-audio-${audio.postId}")
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(showName)
                            .setAlbumTitle(ALBUM)
                            .setArtworkUri(artwork)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                            .build()
                    )
                    .build()
            val ready = ensurePlayer()
            lastSaveMs = SystemClock.elapsedRealtime()
            pausedAtMs = null
            _state.update {
                it.copy(
                    item = item,
                    showName = showName,
                    picture = picture,
                    loading = false,
                    fromPhone = file != null,
                    error = null,
                    ended = false,
                )
            }
            ready.setMediaItem(mediaItem, startMs)
            ready.setPlaybackSpeed(_state.value.speed)
            ready.prepare()
            ready.play()
            startService()
            publish()
        }
    }

    fun toggle() {
        val player = exo ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = exo ?: return
        player.seekTo(positionMs.coerceIn(0L, max(0L, player.duration)))
        publish()
    }

    /** Back or forward by [seconds]. */
    fun skip(seconds: Int) {
        val player = exo ?: return
        seekTo(player.currentPosition + seconds * 1000L)
    }

    /** The speed, kept for the next episode too. */
    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat(SPEED, value).apply()
        exo?.setPlaybackSpeed(value)
        _state.update { it.copy(speed = value) }
    }

    /** Pauses after [minutes], fading out over the last few seconds; null turns it off. */
    fun sleepIn(minutes: Int?) {
        if (minutes == null) {
            cancelSleep()
            return
        }
        sleepUntil(SystemClock.elapsedRealtime() + minutes * 60_000L)
    }

    /** Moves the sleep timer on by [minutes], or back with fewer than none. */
    fun addSleep(minutes: Int) {
        val endsAt = _state.value.sleep?.endsAtMs ?: return
        val now = SystemClock.elapsedRealtime()
        sleepUntil(max(now + MIN_SLEEP_MS, endsAt + minutes * 60_000L))
    }

    private fun sleepUntil(endsAt: Long) {
        sleepJob?.cancel()
        exo?.volume = 1f
        _state.update { it.copy(sleep = JrwiSleep(endsAtMs = endsAt)) }
        sleepJob = scope.launch {
            val fadeFrom = endsAt - SLEEP_FADE_MS
            delay(max(0L, fadeFrom - SystemClock.elapsedRealtime()))
            val player = exo
            if (player != null) {
                // Quieter and quieter, then paused
                while (isActive && SystemClock.elapsedRealtime() < endsAt) {
                    val left = (endsAt - SystemClock.elapsedRealtime()).toFloat()
                    player.volume = (left / SLEEP_FADE_MS).coerceIn(0f, 1f)
                    delay(FADE_STEP_MS)
                }
                player.pause()
                player.volume = 1f
            }
            _state.update { it.copy(sleep = null) }
        }
    }

    /** Pauses when the episode ends; the player stops there anyway, so this only shows it. */
    fun sleepAtEnd() {
        sleepJob?.cancel()
        _state.update { it.copy(sleep = JrwiSleep(atEnd = true)) }
    }

    private fun cancelSleep() {
        sleepJob?.cancel()
        exo?.volume = 1f
        _state.update { it.copy(sleep = null) }
    }

    /**
     * Pauses and closes the player once its place is saved, also on the video, so the video can go
     * on from here. The episode's page then plays the video.
     */
    suspend fun closeForVideo() {
        val player = exo ?: return
        val before = lastSave
        // A pause while it plays saves the place already
        player.pause()
        if (lastSave === before) save(handOff = true)
        lastSave?.join()
        stop(keepPlace = false)
    }

    /** Stops and closes the player, keeping the episode's place unless [keepPlace] is false. */
    fun stop(keepPlace: Boolean = true) {
        loadJob?.cancel()
        if (keepPlace) save(handOff = true)
        sleepJob?.cancel()
        tickJob?.cancel()
        session?.release()
        session = null
        exo?.removeListener(listener)
        exo?.release()
        exo = null
        context.stopService(Intent(context, JrwiAudioService::class.java))
        _state.update { JrwiAudioState(speed = it.speed) }
    }

    private fun ensurePlayer(): ExoPlayer {
        exo?.let {
            return it
        }
        val player =
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    // Pauses for calls and other players, and the video player pauses it
                    true,
                )
                // Pauses when headphones are unplugged
                .setHandleAudioBecomingNoisy(true)
                // Keeps the phone and Wi-Fi awake while it streams with the screen off
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(BACK_SECONDS * 1000L)
                .setSeekForwardIncrementMs(FORWARD_SECONDS * 1000L)
                .build()
        player.addListener(listener)
        exo = player
        session =
            MediaSession.Builder(context, player)
                // The video player has a session of its own
                .setId(SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        context,
                        SESSION_ACTIVITY_REQUEST,
                        Intent(context, MainActivity::class.java)
                            .putExtra(EXTRA_OPEN_JRWI_PLAYER, true)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        return player
    }

    private fun startService() {
        try {
            context.startService(Intent(context, JrwiAudioService::class.java))
        } catch (e: IllegalStateException) {
            // Only from the background, which playing is never started from
            Timber.w(e, "Could not start the JRWI audio service")
        }
    }

    /** Reads the player's position often while it plays, and saves it every 15 seconds. */
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                publish()
                val player = exo ?: break
                if (!player.isPlaying) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastSaveMs >= SAVE_EVERY_MS) save(handOff = false)
                delay(TICK_MS)
            }
        }
    }

    private fun publish() {
        val player = exo
        _state.update {
            if (player == null) it
            else
                it.copy(
                    playing = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                    ended = player.playbackState == Player.STATE_ENDED,
                    positionMs = player.currentPosition,
                    durationMs = max(0L, player.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L),
                )
        }
    }

    /**
     * Saves where the audio is, as the video player did: on the archiver every time, and with
     * [handOff] also on the video. Most of the way through counts as watched.
     */
    private fun save(handOff: Boolean, ended: Boolean = false): Job? {
        val player = exo ?: return null
        val item = _state.value.item ?: return null
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val finished = ended || (duration != null && position >= duration * WATCHED_PART)
        lastSaveMs = SystemClock.elapsedRealtime()
        lastSave = scope.launch {
            // Saved even when the player is closed meanwhile
            withContext(NonCancellable) {
                try {
                    jrwiProgress.audioPlayed(
                        item,
                        position / 1000.0,
                        finished,
                        handOff,
                        duration?.div(1000.0),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not save the JRWI progress of ${item.id}")
                }
            }
        }
        return lastSave
    }

    private fun savedSpeed(): Float = prefs.getFloat(SPEED, 1f)

    companion object {
        /** Set on the app's activity by the notification, to open the player. */
        const val EXTRA_OPEN_JRWI_PLAYER = "openJrwiPlayer"

        /** How far back and forward the buttons go, as podcast apps do. */
        const val BACK_SECONDS = 15
        const val FORWARD_SECONDS = 30

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        private const val PREFS = "jrwi_audio"
        private const val SPEED = "speed"
        private const val SESSION_ID = "jrwi-audio"
        private const val SESSION_ACTIVITY_REQUEST = 7311
        private const val ALBUM = "JRWI"
        private const val SAVE_EVERY_MS = 15_000L
        private const val TICK_MS = 250L
        private const val WATCHED_PART = 0.9
        private const val REWIND_AFTER_PAUSE_MS = 60_000L
        private const val REWIND_MS = 5_000L
        private const val SLEEP_FADE_MS = 10_000L
        private const val MIN_SLEEP_MS = 60_000L
        // A saved place further than this from the player's is taken over
        private const val RESYNC_MS = 3_000L
        private const val FADE_STEP_MS = 100L
    }
}
