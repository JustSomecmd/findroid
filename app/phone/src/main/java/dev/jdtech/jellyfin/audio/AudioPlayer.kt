package dev.jdtech.jellyfin.audio

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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.MainActivity
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.domain.TaQueueEntry
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistCache
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessions
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
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
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/** What the audio player has loaded. */
sealed interface AudioEpisode {
    val title: String

    /** A JRWI episode's audio, from the archiver or its download. */
    data class Jrwi(val item: JrwiItem) : AudioEpisode {
        override val title: String
            get() = item.title
    }

    /**
     * A TubeArchivist video, played as sound from its Jellyfin episode. [videoId] is null when the
     * video wasn't found in TubeArchivist, so only Jellyfin keeps its place. [playlistId] is the
     * playlist it was played from, whose other videos follow it.
     */
    data class Ta(
        val episodeId: UUID,
        val videoId: String?,
        override val title: String,
        val playlistId: String?,
    ) : AudioEpisode
}

/** A SponsorBlock segment of a TubeArchivist video, in the player's milliseconds. */
data class AudioSegment(val startMs: Long, val endMs: Long, val category: String)

/** What the audio player is playing, and how. */
data class AudioState(
    val episode: AudioEpisode? = null,
    /** Under the title: the JRWI show, or the video's channel. */
    val subtitle: String? = null,
    /** The episode's picture: an archiver path, a Jellyfin image, or the phone's copy. */
    val picture: String? = null,
    /** A TubeArchivist video, shown in the app's own colours rather than the archiver's. */
    val tubeArchivist: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleep: AudioSleep? = null,
    /** Played from the copy on the phone. */
    val fromPhone: Boolean = false,
    val error: String? = null,
    /** A SponsorBlock segment playing now, which a button skips. */
    val segment: AudioSegment? = null,
    /** The playlist has a video after this one. */
    val hasNext: Boolean = false,
) {
    /** Something is loaded, playing or not, which the mini player shows. */
    val active: Boolean
        get() = episode != null || loading

    val jrwiItem: JrwiItem?
        get() = (episode as? AudioEpisode.Jrwi)?.item

    val taVideo: AudioEpisode.Ta?
        get() = episode as? AudioEpisode.Ta
}

/** A sleep timer: pausing at a time, or at the end of the episode. */
data class AudioSleep(val endsAtMs: Long? = null, val atEnd: Boolean = false)

/** For starting the player from code that isn't injected, such as a play button's handler. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioPlayerEntryPoint {
    fun audioPlayer(): AudioPlayer

    fun appPreferences(): AppPreferences
}

/**
 * The app's audio player, apart from the video player, as a podcast app has it: a JRWI episode's
 * audio from the archiver or the phone, or the sound of a TubeArchivist video from Jellyfin, in the
 * background with its notification.
 *
 * Its progress goes where the other players send it. A JRWI episode's goes to the archive every 15
 * seconds while it plays, and when it's paused or stopped also to the episode's video (decision 8).
 * A TubeArchivist video's goes to Jellyfin and TubeArchivist, and to the archive too for one of the
 * archiver's YouTube-only videos, as the video player does. Most of the way through counts as
 * watched.
 *
 * It has a sleep timer, a speed that's remembered, and goes back a few seconds when it's played
 * again after a long pause, as storii does. A TubeArchivist video skips or offers to skip its
 * SponsorBlock segments, as set in Settings, and the next video of its playlist follows it.
 */
@Singleton
class AudioPlayer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
    private val appPreferences: AppPreferences,
    private val taApi: TaApi,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val taOfflineStore: TaOfflineStore,
    private val taPlaylistCache: TaPlaylistCache,
    private val taPlaylistSessions: TaPlaylistSessions,
    // Online or offline as it is at the time, so asked for each time
    private val repositories: Provider<JellyfinRepository>,
    private val playlistManagers: Provider<PlaylistManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AudioState(speed = savedSpeed()))
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private var exo: ExoPlayer? = null

    /** The session for the notification and Bluetooth controls, while something is loaded. */
    var session: MediaSession? = null
        private set

    private val _openRequested = MutableStateFlow(false)

    /** Set when the notification was tapped or audio was started elsewhere, to open the player. */
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
    private var pausedAtMs: Long? = null

    // A TubeArchivist video's own: the playlist and episodes around it, whether Jellyfin was told
    // it started, the archiver's item when it's one of its YouTube-only videos, and its segments
    private var taPlaylist: PlaylistManager? = null
    private var taPlaylistName: String? = null
    private var jellyfinStarted = false
    private var linkedJrwi: JrwiItem? = null
    private var taSegments: List<AudioSegment> = emptyList()
    private var sponsorBlockAuto = false
    private var skippedSegment: AudioSegment? = null

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exo ?: return
                if (isPlaying) {
                    pausedAtMs = null
                    startTicking()
                    tellJellyfinStarted()
                } else if (player.playbackState != Player.STATE_ENDED) {
                    // Paused: saved, and handed to the video at the same moment
                    pausedAtMs = SystemClock.elapsedRealtime()
                    save(Save.PAUSE)
                }
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    save(Save.END)
                    if (_state.value.taVideo != null) {
                        playNextTa(fromEnd = true)
                    } else if (_state.value.sleep?.atEnd == true) {
                        cancelSleep()
                    }
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
     * Plays the JRWI audio whose post is [postId], where it was left unless [fromStart]. The same
     * episode only plays on, from the start when asked, or from where it was played elsewhere
     * meanwhile.
     */
    fun playJrwi(postId: Long, fromStart: Boolean = false) {
        val player = exo
        val current = _state.value.jrwiItem
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
            // What was playing keeps its place, and stops while this loads
            if (_state.value.episode != null) save(Save.LEAVE)
            forgetTa()
            _state.update { AudioState(loading = true, speed = it.speed, sleep = it.sleep) }
            exo?.pause()
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
                            .setAlbumTitle(JRWI_ALBUM)
                            .setArtworkUri(artwork)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                            .build()
                    )
                    .build()
            _state.update {
                it.copy(
                    episode = AudioEpisode.Jrwi(item),
                    subtitle = showName,
                    picture = picture,
                    loading = false,
                    fromPhone = file != null,
                    error = null,
                    ended = false,
                )
            }
            start(mediaItem, startMs)
        }
    }

    /**
     * Plays the sound of the TubeArchivist video whose Jellyfin episode is [episodeId]: from
     * [positionMs], or else where Jellyfin has it unless [fromStart]. Played from the playlist
     * [playlistId], the playlist's next videos follow it. [videoId] is looked up when it's null.
     */
    fun playTa(
        episodeId: UUID,
        videoId: String?,
        positionMs: Long?,
        playlistId: String?,
        fromStart: Boolean = false,
    ) {
        val player = exo
        val current = _state.value.taVideo
        if (player != null && current != null && current.episodeId == episodeId) {
            when {
                fromStart -> player.seekTo(0)
                positionMs != null -> player.seekTo(positionMs)
            }
            player.play()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            if (_state.value.episode != null) save(Save.LEAVE)
            forgetTa()
            _state.update {
                AudioState(loading = true, tubeArchivist = true, speed = it.speed, sleep = it.sleep)
            }
            exo?.pause()
            // Only the sound, converted by Jellyfin; a download plays from the phone
            val manager = playlistManagers.get().apply { quality = StreamQuality.AUDIO_ONLY }
            if (playlistId != null) loadPlaylist(manager, playlistId)
            val item =
                try {
                    manager.getInitialItem(
                        itemId = episodeId,
                        itemKind = BaseItemKind.EPISODE,
                        startFromBeginning = fromStart,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not load the TubeArchivist video $episodeId")
                    _state.update { it.copy(loading = false, error = e.localizedMessage ?: "") }
                    return@launch
                }
            if (item == null) {
                _state.update { it.copy(loading = false, error = "") }
                return@launch
            }
            taPlaylist = manager
            val startMs = if (fromStart) 0L else positionMs ?: item.playbackPosition
            startTa(item, videoId ?: manager.currentTaVideoId, startMs, playlistId)
        }
    }

    /** The playlist's other videos, as the video player has them: the downloaded ones in order. */
    private suspend fun loadPlaylist(manager: PlaylistManager, playlistId: String) {
        val playlist =
            try {
                taApi.getPlaylist(playlistId).also { taPlaylistCache.updatePlaylist(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load playlist $playlistId, using the saved copy")
                taPlaylistCache.loadPlaylist(playlistId)?.playlist
            } ?: return
        manager.setTubeArchivistQueue(
            playlist.entries
                .filter { it.downloaded }
                .map { TaQueueEntry(videoId = it.youtubeId, title = it.title) }
        )
        taPlaylistName = playlist.name
    }

    /** Starts one of the TubeArchivist videos, the first or one after it in the playlist. */
    private suspend fun startTa(
        item: PlayerItem,
        knownVideoId: String?,
        startMs: Long,
        playlistId: String?,
    ) {
        val episode =
            try {
                repositories.get().getEpisode(item.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load episode ${item.itemId}")
                null
            }
        val videoId = knownVideoId ?: findVideoId(item.itemId)
        val picture = episode?.images?.primary?.toString()
        val subtitle = item.subtitle ?: episode?.seriesName
        jellyfinStarted = false
        linkedJrwi = null
        taSegments = emptyList()
        skippedSegment = null
        sponsorBlockAuto =
            appPreferences.getValue(appPreferences.tubeArchivistSponsorBlock) == SPONSORBLOCK_AUTO
        val mediaItem =
            MediaItem.Builder()
                .setMediaId("ta-audio-${item.itemId}")
                .setUri(item.mediaSourceUri)
                // Converted streams are HLS playlists
                .setMimeType(if (item.isTranscoded) MimeTypes.APPLICATION_M3U8 else null)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setArtist(subtitle)
                        .setArtworkUri(picture?.toUri())
                        .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                        .build()
                )
                .build()
        _state.update {
            it.copy(
                episode = AudioEpisode.Ta(item.itemId, videoId, item.name, playlistId),
                subtitle = subtitle,
                picture = picture,
                tubeArchivist = true,
                loading = false,
                fromPhone = item.isLocal,
                error = null,
                ended = false,
                segment = null,
                hasNext = taPlaylist?.nextTaVideoId != null,
            )
        }
        start(mediaItem, startMs)
        // Where the playlist is, for carrying on with it from Home
        if (playlistId != null && videoId != null) {
            taPlaylistSessions.update(playlistId, taPlaylistName.orEmpty(), videoId)
        }
        if (videoId != null) {
            val episodeId = item.itemId
            scope.launch { loadTaExtras(episodeId, videoId) }
        }
    }

    /** The video's TubeArchivist ID: kept with its download, or looked up in Jellyfin. */
    private suspend fun findVideoId(episodeId: UUID): String? =
        taOfflineStore.videoIdForItem(episodeId)
            ?: try {
                taJellyfinLookup.videoIdForItem(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not find the TubeArchivist video of $episodeId")
                null
            }

    /** The archiver's item of a YouTube-only video, and the video's SponsorBlock segments. */
    private suspend fun loadTaExtras(episodeId: UUID, videoId: String) {
        val isCurrent = { _state.value.taVideo?.episodeId == episodeId }
        try {
            val item = jrwiProgress.itemForYoutube(videoId)
            if (isCurrent()) linkedJrwi = item
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not look $videoId up in the JRWI archive")
        }
        if (appPreferences.getValue(appPreferences.tubeArchivistSponsorBlock) == SPONSORBLOCK_OFF) {
            return
        }
        val video =
            taOfflineStore.savedVideo(videoId)
                ?: try {
                    taApi.getVideo(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not load the segments of $videoId")
                    null
                }
        val segments = video?.segments().orEmpty()
        if (isCurrent()) taSegments = segments
    }

    /** Skippable SponsorBlock segments, as the video player has them. */
    private fun TaVideo.segments(): List<AudioSegment> =
        sponsorblock?.segments.orEmpty().mapNotNull { segment ->
            val start = segment.segment.getOrNull(0) ?: return@mapNotNull null
            val end = segment.segment.getOrNull(1) ?: return@mapNotNull null
            // Segments that only mark a point (such as highlights) can't be skipped
            if (end - start < 1.0 || segment.actionType == "poi") return@mapNotNull null
            AudioSegment((start * 1000).toLong(), (end * 1000).toLong(), segment.category)
        }

    /** Skips the SponsorBlock segment that's playing. */
    fun skipSegment() {
        val segment = _state.value.segment ?: return
        exo?.seekTo(segment.endMs)
        _state.update { it.copy(segment = null) }
    }

    /** Goes on to the playlist's next video now. */
    fun skipToNext() {
        if (_state.value.taVideo == null) return
        save(Save.LEAVE)
        playNextTa(fromEnd = false)
    }

    /**
     * The playlist's next video, or the channel's, as the video player plays them: on its own when
     * one ends, unless that's turned off or the sleep timer stops there.
     */
    private fun playNextTa(fromEnd: Boolean) {
        val manager = taPlaylist ?: return
        if (fromEnd) {
            if (_state.value.sleep?.atEnd == true) {
                cancelSleep()
                return
            }
            if (!appPreferences.getValue(appPreferences.playerAutoplay)) return
        }
        val playlistId = _state.value.taVideo?.playlistId
        loadJob?.cancel()
        loadJob = scope.launch {
            val next =
                try {
                    manager.getNextPlayerItem()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not load the next video")
                    null
                } ?: return@launch
            manager.setCurrentMediaItemIndex(next.itemId)
            startTa(next, manager.currentTaVideoId, next.playbackPosition, playlistId)
        }
    }

    private fun forgetTa() {
        taPlaylist = null
        taPlaylistName = null
        linkedJrwi = null
        taSegments = emptyList()
        skippedSegment = null
        jellyfinStarted = false
    }

    private fun start(mediaItem: MediaItem, startMs: Long) {
        val player = ensurePlayer()
        lastSaveMs = SystemClock.elapsedRealtime()
        pausedAtMs = null
        player.setMediaItem(mediaItem, startMs)
        player.setPlaybackSpeed(_state.value.speed)
        player.prepare()
        player.play()
        startService()
        publish()
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
        _state.update { it.copy(sleep = AudioSleep(endsAtMs = endsAt)) }
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

    /** Pauses when the episode ends, rather than going on to the next video. */
    fun sleepAtEnd() {
        sleepJob?.cancel()
        _state.update { it.copy(sleep = AudioSleep(atEnd = true)) }
    }

    private fun cancelSleep() {
        sleepJob?.cancel()
        exo?.volume = 1f
        _state.update { it.copy(sleep = null) }
    }

    /**
     * Pauses and closes the player once its place is saved, also on the video, so the video can go
     * on from here. Gives where it was, in milliseconds.
     */
    suspend fun closeForVideo(): Long? {
        val player = exo ?: return null
        val position = player.currentPosition
        // Saved once, as left, rather than as paused first
        player.removeListener(listener)
        player.pause()
        save(Save.LEAVE)?.join()
        stop(keepPlace = false)
        return position
    }

    /** Stops and closes the player, keeping the episode's place unless [keepPlace] is false. */
    fun stop(keepPlace: Boolean = true) {
        loadJob?.cancel()
        if (keepPlace) save(Save.LEAVE)
        sleepJob?.cancel()
        tickJob?.cancel()
        session?.release()
        session = null
        exo?.removeListener(listener)
        exo?.release()
        exo = null
        forgetTa()
        context.stopService(Intent(context, AudioService::class.java))
        _state.update { AudioState(speed = it.speed) }
    }

    private fun ensurePlayer(): ExoPlayer {
        exo?.let {
            return it
        }
        // The app's FFmpeg decoders too, for videos' sound that the phone can't play itself
        val renderers =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player =
            ExoPlayer.Builder(context, renderers)
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
        // Only the sound of a video file on the phone, without decoding its picture
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
                            .putExtra(EXTRA_OPEN_AUDIO_PLAYER, true)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        return player
    }

    private fun startService() {
        try {
            context.startService(Intent(context, AudioService::class.java))
        } catch (e: IllegalStateException) {
            // Only from the background, which playing is never started from
            Timber.w(e, "Could not start the audio service")
        }
    }

    /** Jellyfin hears that a TubeArchivist video started, once it plays. */
    private fun tellJellyfinStarted() {
        val episodeId = _state.value.taVideo?.episodeId ?: return
        if (jellyfinStarted) return
        jellyfinStarted = true
        scope.launch {
            try {
                repositories.get().postPlaybackStart(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not tell Jellyfin playback started")
            }
        }
    }

    /**
     * Reads the player's position often while it plays, saves it every 15 seconds, and skips or
     * offers to skip SponsorBlock segments.
     */
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                publish()
                val player = exo ?: break
                if (!player.isPlaying) break
                checkSegment(player.currentPosition)
                val now = SystemClock.elapsedRealtime()
                if (now - lastSaveMs >= SAVE_EVERY_MS) save(Save.TICK)
                delay(TICK_MS)
            }
        }
    }

    private fun checkSegment(positionMs: Long) {
        val segment = taSegments.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs - 500
        }
        if (segment == null) {
            skippedSegment = null
            if (_state.value.segment != null) _state.update { it.copy(segment = null) }
            return
        }
        if (sponsorBlockAuto) {
            // Once, as the seek takes a moment
            if (skippedSegment != segment) {
                skippedSegment = segment
                exo?.seekTo(segment.endMs)
            }
        } else if (_state.value.segment != segment) {
            _state.update { it.copy(segment = segment) }
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

    /** Why the place is saved: a regular save, a pause, the episode left, or its end. */
    private enum class Save {
        TICK,
        PAUSE,
        LEAVE,
        END,
    }

    /**
     * Saves where it is, as the other players do. A JRWI episode's goes to the archiver every time,
     * and when it's paused or left also to its video. A TubeArchivist video's goes to Jellyfin and
     * TubeArchivist, and to the archive for one of its YouTube-only videos. Most of the way through
     * counts as watched.
     */
    private fun save(kind: Save): Job? {
        val player = exo ?: return null
        val episode = _state.value.episode ?: return null
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val finished = kind == Save.END || (duration != null && position >= duration * WATCHED_PART)
        val linked = linkedJrwi
        lastSaveMs = SystemClock.elapsedRealtime()
        return scope.launch {
            // Saved even when the player is closed meanwhile
            withContext(NonCancellable) {
                when (episode) {
                    is AudioEpisode.Jrwi ->
                        saveJrwi(episode.item, position, duration, finished, kind != Save.TICK)
                    is AudioEpisode.Ta ->
                        saveTa(episode, linked, position, duration, finished, kind)
                }
            }
        }
    }

    private suspend fun saveJrwi(
        item: JrwiItem,
        positionMs: Long,
        durationMs: Long?,
        finished: Boolean,
        handOff: Boolean,
    ) {
        try {
            jrwiProgress.audioPlayed(
                item,
                positionMs / 1000.0,
                finished,
                handOff,
                durationMs?.div(1000.0),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not save the JRWI progress of ${item.id}")
        }
    }

    private suspend fun saveTa(
        episode: AudioEpisode.Ta,
        linked: JrwiItem?,
        positionMs: Long,
        durationMs: Long?,
        finished: Boolean,
        kind: Save,
    ) {
        val seconds = positionMs / 1000.0
        try {
            val repository = repositories.get()
            val ticks = positionMs * TICKS_PER_MS
            when (kind) {
                Save.TICK,
                Save.PAUSE ->
                    repository.postPlaybackProgress(episode.episodeId, ticks, kind == Save.PAUSE)
                Save.LEAVE,
                Save.END -> {
                    val percent = durationMs?.let { (positionMs * 100 / it).toInt() } ?: 0
                    repository.postPlaybackStop(episode.episodeId, ticks, percent)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not save the progress in Jellyfin")
        }
        val videoId = episode.videoId
        if (videoId != null && taApi.isConfigured) {
            try {
                taApi.postProgress(videoId, seconds)
                taOfflineStore.clearPendingProgress(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sent when TubeArchivist can be reached again
                Timber.w(e, "Could not save progress in TubeArchivist")
                taOfflineStore.addPendingProgress(videoId, seconds)
            }
            // Pages showing the video update when it's left or paused, not every few seconds
            if (kind != Save.TICK) MediaChanges.watched(videoId = videoId)
        }
        if (linked != null) {
            try {
                jrwiProgress.videoPlayed(linked, seconds, finished, durationMs?.div(1000.0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not save the JRWI progress of ${linked.id}")
            }
        }
    }

    private fun savedSpeed(): Float = prefs.getFloat(SPEED, 1f)

    companion object {
        /** Set on the app's activity by the notification, to open the player. */
        const val EXTRA_OPEN_AUDIO_PLAYER = "openAudioPlayer"

        /** How far back and forward the buttons go, as podcast apps do. */
        const val BACK_SECONDS = 15
        const val FORWARD_SECONDS = 30

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        // Kept from when it only played JRWI audio, so the speed set then stays
        private const val PREFS = "jrwi_audio"
        private const val SPEED = "speed"
        private const val SESSION_ID = "audio"
        private const val SESSION_ACTIVITY_REQUEST = 7311
        private const val JRWI_ALBUM = "JRWI"
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
        // Jellyfin counts time in ticks of 100 nanoseconds
        private const val TICKS_PER_MS = 10_000L
        private const val SPONSORBLOCK_OFF = "off"
        private const val SPONSORBLOCK_AUTO = "auto"
    }
}
