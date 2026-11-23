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
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
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
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.domain.TaQueueEntry
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
     * The sound of a video in Jellyfin, a movie or an episode ([kind]). [videoId] is its
     * TubeArchivist video when it's one, which keeps its place too. [playlistId] is the
     * TubeArchivist playlist it was played from, whose other videos follow it.
     */
    data class Video(
        val itemId: UUID,
        val kind: BaseItemKind,
        val videoId: String?,
        override val title: String,
        val playlistId: String?,
    ) : AudioEpisode
}

/**
 * A segment of a video to skip, in the player's milliseconds: a SponsorBlock one with its
 * [category], such as sponsor, or one of Jellyfin's media segments with its [type], such as INTRO.
 */
data class AudioSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String? = null,
    val type: String? = null,
)

/** What the audio player is playing, and how. */
data class AudioState(
    val episode: AudioEpisode? = null,
    /** Under the title: the JRWI show, or the video's channel or show. */
    val subtitle: String? = null,
    /** The episode's picture: an archiver path, a Jellyfin image, or the phone's copy. */
    val picture: String? = null,
    /** A video's sound, shown in the app's own colours rather than the archiver's. */
    val appLook: Boolean = false,
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
    /** A segment playing now, which a button skips. */
    val segment: AudioSegment? = null,
    /** The playlist has a video after this one. */
    val hasNext: Boolean = false,
    /** The archiver's item of a JRWI episode whose video's sound this is, whose page it has. */
    val linkedJrwiId: Long? = null,
    /**
     * The post whose transcript follows what plays, timed in its timeline: a JRWI episode's audio,
     * or the video of the JRWI episode whose sound this is. Null without a transcript (v0.23).
     */
    val transcriptPostId: Long? = null,
) {
    /** Something is loaded, playing or not, which the mini player shows. */
    val active: Boolean
        get() = episode != null || loading

    val jrwiItem: JrwiItem?
        get() = (episode as? AudioEpisode.Jrwi)?.item

    val video: AudioEpisode.Video?
        get() = episode as? AudioEpisode.Video
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
 * audio from the archiver or the phone, or the sound of a video from Jellyfin, in the background
 * with its notification.
 *
 * Its progress goes where the other players send it. A JRWI episode's goes to the archive every 15
 * seconds while it plays, and when it's paused or stopped also to the episode's video (decision 8).
 * A video's goes to Jellyfin, to TubeArchivist for one of its videos, and to the archive for a JRWI
 * episode's video, as the video player does. Most of the way through counts as watched.
 *
 * It has a sleep timer, a speed that's remembered, and goes back a few seconds when it's played
 * again after a long pause, as storii does. It skips back and forward as far as the video player
 * does. A video skips or offers to skip its SponsorBlock segments and Jellyfin's media segments as
 * the video player's settings say, and the next video of its playlist or show follows it.
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

    /** How far the back button goes: as far as the video player's, in Settings. */
    val backMs: Long
        get() = appPreferences.getValue(appPreferences.playerSeekBackInc)

    /** How far the forward button goes: as far as the video player's, in Settings. */
    val forwardMs: Long
        get() = appPreferences.getValue(appPreferences.playerSeekForwardInc)

    private var loadJob: Job? = null
    private var tickJob: Job? = null

    // Whether the player may hand the sound to the phone's audio chip, until that once fails
    private var offloading = false

    // Whether what plays is a file on the phone, and when a save last went out
    private var fromPhone = false
    private var lastSentMs = 0L
    private var sleepJob: Job? = null
    private var lastSaveMs = 0L
    private var pausedAtMs: Long? = null

    // A video's own: the playlist and episodes around it, whether Jellyfin was told it started,
    // the archiver's item when it's a JRWI episode's video, and its segments
    private var playlist: PlaylistManager? = null
    private var taPlaylistName: String? = null
    private var jellyfinStarted = false
    private var linkedJrwi: JrwiItem? = null
    private var segments: List<AudioSegment> = emptyList()
    private var segmentRules = SegmentRules()
    private var skippedSegment: AudioSegment? = null

    /** The video player's settings for skipping segments, read when a video starts. */
    private data class SegmentRules(
        val sponsorBlock: String = SPONSORBLOCK_OFF,
        val button: Boolean = false,
        val buttonTypes: Set<String> = emptySet(),
        val auto: Boolean = false,
        val autoTypes: Set<String> = emptySet(),
        val autoMode: String = Constants.PlayerMediaSegmentsAutoSkip.ALWAYS,
    ) {
        /** Jellyfin's media segments are only looked up when something is done with them. */
        val usesMediaSegments: Boolean
            get() = button || auto

        fun skipsOnItsOwn(segment: AudioSegment): Boolean =
            if (segment.category != null) {
                sponsorBlock == SPONSORBLOCK_AUTO
            } else {
                // Set to skip only in the small window, which this player hasn't, it isn't
                auto &&
                    segment.type?.let { it in autoTypes } == true &&
                    autoMode == Constants.PlayerMediaSegmentsAutoSkip.ALWAYS
            }

        fun showsButton(segment: AudioSegment): Boolean =
            if (segment.category != null) sponsorBlock != SPONSORBLOCK_OFF
            else button && segment.type?.let { it in buttonTypes } == true
    }

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
                    val jrwi = _state.value.jrwiItem
                    if (_state.value.video != null) {
                        playNext(fromEnd = true)
                    } else if (_state.value.sleep?.atEnd == true) {
                        cancelSleep()
                    } else if (
                        jrwi != null && appPreferences.getValue(appPreferences.playerAutoplay)
                    ) {
                        // The show's next episode follows, as a video's next one does
                        playNextJrwi(jrwi)
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
                val player = exo
                // A phone that said its audio chip could play this and then couldn't: once more
                // the usual way, from the same place
                if (player != null && offloading && error.errorCode in AUDIO_TRACK_ERRORS) {
                    Timber.w(error, "Audio offload failed, playing without it")
                    offloading = false
                    player.trackSelectionParameters =
                        player.trackSelectionParameters
                            .buildUpon()
                            .setAudioOffloadPreferences(AudioOffloadPreferences.DEFAULT)
                            .build()
                    player.prepare()
                    return
                }
                _state.update { it.copy(error = error.localizedMessage) }
            }
        }

    /**
     * Plays the JRWI audio whose post is [postId]: from [startMs] when given, or else where it was
     * left unless [fromStart]. The same episode only plays on, from the start or [startMs] when
     * asked, or from where it was played elsewhere meanwhile.
     */
    fun playJrwi(postId: Long, fromStart: Boolean = false, startMs: Long? = null) {
        val player = exo
        val current = _state.value.jrwiItem
        if (player != null && current != null && current.audio?.postId == postId) {
            when {
                fromStart -> player.seekTo(0)
                startMs != null -> player.seekTo(startMs)
            }
            if (fromStart || startMs != null || player.isPlaying) {
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
        loadJob = scope.launch { loadJrwi(postId, fromStart, startMs) }
    }

    private suspend fun loadJrwi(postId: Long, fromStart: Boolean, startAt: Long?) {
        // What was playing keeps its place, and stops while this loads
        if (_state.value.episode != null) save(Save.LEAVE)
        forgetVideo()
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
                        _state.update { it.copy(loading = false, error = e.localizedMessage ?: "") }
                        return
                    }
            }
        val audio = item.audio
        val file = download?.let { jrwiDownloads.audioFile(it.itemId) }?.takeIf { it.exists() }
        val url = file?.let { Uri.fromFile(it).toString() } ?: jrwiApi.fileUrlWithToken(audio?.url)
        if (audio == null || url == null) {
            _state.update { it.copy(loading = false, error = "") }
            return
        }
        val startMs =
            startAt
                ?: run {
                    val progress =
                        if (fromStart) null else jrwiProgress.progress(item, audio = true)
                    ((progress?.takeIf { !it.finished }?.position ?: 0.0) * 1000).toLong()
                }
        val showName = jrwiProgress.showNameOrNull(item.show) ?: download?.showName
        val localPicture =
            download?.let { jrwiDownloads.thumbnailFile(it.itemId) }?.takeIf { it.exists() }
        val picture = localPicture?.let { Uri.fromFile(it).toString() } ?: item.thumbnail
        // Without the token: the session shares this address with other apps' media controls, and
        // its picture loader adds the token in the app
        val artwork =
            localPicture?.let { Uri.fromFile(it) } ?: jrwiApi.fileUrl(item.thumbnail)?.toUri()
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
                transcriptPostId = item.transcriptPost(audio = true),
            )
        }
        start(mediaItem, startMs)
    }

    /**
     * Plays the audio of the episode after [item] in its show, once [item] has ended, unless
     * something else was started meanwhile. It stops at the show's end, and before an episode
     * without audio.
     */
    private fun playNextJrwi(item: JrwiItem) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val audio = nextJrwi(item)?.audio ?: return@launch
            val state = _state.value
            if (state.jrwiItem?.id != item.id || !state.ended) return@launch
            loadJrwi(audio.postId, fromStart = false, startAt = null)
        }
    }

    /**
     * The episode after [item] as its show's page lists it: in its own tab, oldest first, so in
     * Episodes by season and number. Without a connection, the next of the show's downloads.
     */
    private suspend fun nextJrwi(item: JrwiItem): JrwiItem? =
        try {
            nextOnArchiver(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not find the episode after ${item.id}, looking in the downloads")
            withContext(Dispatchers.IO) { nextDownloaded(item) }
        }

    /** The item after [item] in its show's tab, a page at a time until it's found. */
    private suspend fun nextOnArchiver(item: JrwiItem): JrwiItem? {
        var found = false
        for (page in 1..NEXT_MAX_PAGES) {
            val result =
                jrwiApi.getShowItems(
                    showId = item.show,
                    tab = item.tab,
                    page = page,
                    order = "asc",
                    pageSize = NEXT_PAGE_SIZE,
                )
            for (entry in result.items) {
                if (found) return entry
                if (entry.id == item.id) found = true
            }
            if (!result.hasMore) break
        }
        return null
    }

    /** The episode after [item] among the show's downloaded audio, in the show's order. */
    private fun nextDownloaded(item: JrwiItem): JrwiItem? {
        val order =
            if (item.tab == "episodes") {
                compareBy<JrwiItem>(
                    { it.season ?: 1 },
                    { it.episode ?: Int.MAX_VALUE },
                    { it.date.orEmpty() },
                    { it.id },
                )
            } else {
                compareBy<JrwiItem>({ it.date.orEmpty() }, { it.id })
            }
        return jrwiDownloads.downloads.value
            .filter { it.done }
            .mapNotNull { jrwiDownloads.savedItem(it.itemId) }
            .filter { it.show == item.show && it.tab == item.tab && order.compare(it, item) > 0 }
            .minWithOrNull(order)
    }

    /**
     * Goes on from a JRWI episode's video, the archiver's item [itemId], at [videoPositionMs]: in
     * the episode's audio at the same moment, from the phone when it's downloaded. When it isn't,
     * and the video is on the phone, the downloaded video's own sound plays rather than the audio
     * being streamed, as it does for an episode without audio: from its Jellyfin episode
     * [episodeId].
     */
    fun playJrwiFromVideo(itemId: Long, episodeId: UUID, videoPositionMs: Long) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val item =
                try {
                    jrwiApi.getItem(itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without a connection, what was kept with a download of either version
                    Timber.w(e, "Could not load JRWI item $itemId, trying the phone's copy")
                    jrwiDownloads.savedItem(itemId)
                        ?: jrwiDownloads.savedVideoForEpisode(episodeId.toString())?.item
                }
            val audio = item?.audio
            val audioOnPhone = audio != null && jrwiDownloads.forAudio(audio.postId) != null
            if (item == null || audio == null || (!audioOnPhone && videoOnPhone(episodeId))) {
                loadVideo(
                    itemId = episodeId,
                    kind = BaseItemKind.EPISODE,
                    knownVideoId = null,
                    positionMs = videoPositionMs,
                    playlistId = null,
                    fromStart = false,
                )
                return@launch
            }
            val audioMs = (item.audioSeconds(videoPositionMs / 1000.0) * 1000).toLong()
            val player = exo
            if (player != null && _state.value.jrwiItem?.audio?.postId == audio.postId) {
                player.seekTo(audioMs)
                player.play()
            } else {
                loadJrwi(audio.postId, fromStart = false, startAt = audioMs)
            }
        }
    }

    /** Whether the Jellyfin episode [episodeId] is downloaded to the phone. */
    private suspend fun videoOnPhone(episodeId: UUID): Boolean =
        try {
            repositories.get().getEpisode(episodeId).isDownloaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Could not look up the episode $episodeId")
            false
        }

    /**
     * Plays the sound of the Jellyfin video [itemId], a movie or an episode ([kind]): from
     * [positionMs], or else where Jellyfin has it unless [fromStart]. A TubeArchivist video played
     * from the playlist [playlistId] has the playlist's next videos follow it. [videoId], its
     * TubeArchivist ID, is looked up when it's null.
     */
    fun playVideoSound(
        itemId: UUID,
        kind: BaseItemKind,
        videoId: String?,
        positionMs: Long?,
        playlistId: String?,
        fromStart: Boolean = false,
    ) {
        val player = exo
        val current = _state.value.video
        if (player != null && current != null && current.itemId == itemId) {
            when {
                fromStart -> player.seekTo(0)
                positionMs != null -> player.seekTo(positionMs)
            }
            player.play()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            loadVideo(itemId, kind, videoId, positionMs, playlistId, fromStart)
        }
    }

    private suspend fun loadVideo(
        itemId: UUID,
        kind: BaseItemKind,
        knownVideoId: String?,
        positionMs: Long?,
        playlistId: String?,
        fromStart: Boolean,
    ) {
        if (_state.value.episode != null) save(Save.LEAVE)
        forgetVideo()
        _state.update {
            AudioState(loading = true, appLook = true, speed = it.speed, sleep = it.sleep)
        }
        exo?.pause()
        // Only the sound, converted by Jellyfin; a download plays from the phone
        val manager = playlistManagers.get().apply { quality = StreamQuality.AUDIO_ONLY }
        if (playlistId != null) loadPlaylist(manager, playlistId)
        val item =
            try {
                manager.getInitialItem(
                    itemId = itemId,
                    itemKind = kind,
                    startFromBeginning = fromStart,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load the video $itemId")
                _state.update { it.copy(loading = false, error = e.localizedMessage ?: "") }
                return
            }
        if (item == null) {
            _state.update { it.copy(loading = false, error = "") }
            return
        }
        playlist = manager
        val startMs = if (fromStart) 0L else positionMs ?: item.playbackPosition
        startVideo(item, kind, knownVideoId ?: manager.currentTaVideoId, startMs, playlistId)
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

    /** Starts one of the videos: the first, or one after it in the playlist or show. */
    private suspend fun startVideo(
        item: PlayerItem,
        kind: BaseItemKind,
        knownVideoId: String?,
        startMs: Long,
        playlistId: String?,
    ) {
        val details =
            try {
                val repository = repositories.get()
                if (kind == BaseItemKind.MOVIE) repository.getMovie(item.itemId)
                else repository.getEpisode(item.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load ${item.itemId}")
                null
            }
        val videoId = knownVideoId ?: findVideoId(item.itemId)
        val picture = details?.images?.let { it.primary ?: it.backdrop }?.toString()
        val subtitle = item.subtitle ?: (details as? FindroidEpisode)?.seriesName
        jellyfinStarted = false
        linkedJrwi = null
        segments = emptyList()
        skippedSegment = null
        segmentRules =
            SegmentRules(
                sponsorBlock = appPreferences.getValue(appPreferences.tubeArchivistSponsorBlock),
                button = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton),
                buttonTypes =
                    appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType),
                auto = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip),
                autoTypes = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType),
                autoMode = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode),
            )
        val mediaItem =
            MediaItem.Builder()
                .setMediaId("video-audio-${item.itemId}")
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
                episode = AudioEpisode.Video(item.itemId, kind, videoId, item.name, playlistId),
                subtitle = subtitle,
                picture = picture,
                appLook = true,
                loading = false,
                fromPhone = item.isLocal,
                error = null,
                ended = false,
                segment = null,
                hasNext = playlist?.nextTaVideoId != null,
                linkedJrwiId = null,
                transcriptPostId = null,
            )
        }
        start(mediaItem, startMs)
        // Where the playlist is, for carrying on with it from Home
        if (playlistId != null && videoId != null) {
            taPlaylistSessions.update(playlistId, taPlaylistName.orEmpty(), videoId)
        }
        val itemId = item.itemId
        scope.launch { loadVideoExtras(itemId, videoId) }
    }

    /** The video's TubeArchivist ID: kept with its download, or looked up in Jellyfin. */
    private suspend fun findVideoId(itemId: UUID): String? =
        taOfflineStore.videoIdForItem(itemId)
            ?: try {
                taJellyfinLookup.videoIdForItem(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not find the TubeArchivist video of $itemId")
                null
            }

    /**
     * The archiver's item when the video is a JRWI episode's, as the video player looks it up, and
     * the segments to skip: Jellyfin's media segments, and a TubeArchivist video's SponsorBlock
     * ones, as far as the video player's settings ask for them.
     */
    private suspend fun loadVideoExtras(itemId: UUID, videoId: String?) {
        val isCurrent = { _state.value.video?.itemId == itemId }
        val rules = segmentRules
        try {
            val item =
                jrwiProgress.itemForEpisode(itemId)
                    ?: jrwiDownloads.savedVideoForEpisode(itemId.toString())?.item?.takeIf {
                        jrwiApi.isConfigured
                    }
                    ?: videoId?.let { jrwiProgress.itemForYoutube(it) }
            if (isCurrent()) {
                linkedJrwi = item
                _state.update {
                    it.copy(
                        linkedJrwiId = item?.id,
                        // The video's own sound, so the lines timed for the video
                        transcriptPostId = item?.transcriptPost(audio = false),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not look $itemId up in the JRWI archive")
        }
        val media =
            if (!rules.usesMediaSegments) {
                emptyList()
            } else {
                try {
                    repositories.get().getSegments(itemId).map {
                        AudioSegment(it.startTicks, it.endTicks, type = it.type.toString())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not load the media segments of $itemId")
                    emptyList()
                }
            }
        val sponsor =
            if (videoId == null || rules.sponsorBlock == SPONSORBLOCK_OFF) {
                emptyList()
            } else {
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
                video?.sponsorSegments().orEmpty()
            }
        if (isCurrent()) {
            segments = media + sponsor
            if (segments.isNotEmpty()) restartTicking()
        }
    }

    /** Skippable SponsorBlock segments, as the video player has them. */
    private fun TaVideo.sponsorSegments(): List<AudioSegment> =
        sponsorblock?.segments.orEmpty().mapNotNull { segment ->
            val start = segment.segment.getOrNull(0) ?: return@mapNotNull null
            val end = segment.segment.getOrNull(1) ?: return@mapNotNull null
            // Segments that only mark a point (such as highlights) can't be skipped
            if (end - start < 1.0 || segment.actionType == "poi") return@mapNotNull null
            AudioSegment(
                (start * 1000).toLong(),
                (end * 1000).toLong(),
                category = segment.category,
            )
        }

    /** Skips the segment that's playing. */
    fun skipSegment() {
        val segment = _state.value.segment ?: return
        exo?.seekTo(segment.endMs)
        _state.update { it.copy(segment = null) }
    }

    /** Goes on to the playlist's next video now. */
    fun skipToNext() {
        if (_state.value.video == null) return
        save(Save.LEAVE)
        playNext(fromEnd = false)
    }

    /**
     * The playlist's next video, or the show's, as the video player plays them: on its own when one
     * ends, unless that's turned off or the sleep timer stops there.
     */
    private fun playNext(fromEnd: Boolean) {
        val manager = playlist ?: return
        val current = _state.value.video ?: return
        if (fromEnd) {
            if (_state.value.sleep?.atEnd == true) {
                cancelSleep()
                return
            }
            if (!appPreferences.getValue(appPreferences.playerAutoplay)) return
        }
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
            startVideo(
                next,
                current.kind,
                manager.currentTaVideoId,
                next.playbackPosition,
                current.playlistId,
            )
        }
    }

    private fun forgetVideo() {
        playlist = null
        taPlaylistName = null
        linkedJrwi = null
        segments = emptyList()
        skippedSegment = null
        jellyfinStarted = false
    }

    private fun start(mediaItem: MediaItem, startMs: Long) {
        val player = ensurePlayer()
        // Wi-Fi kept awake only while a stream plays; a file on the phone needs just the processor
        val uri = mediaItem.localConfiguration?.uri
        val onPhone =
            uri != null && uri.scheme.let { it == null || it == "file" || it == "content" }
        player.setWakeMode(if (onPhone) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        fromPhone = onPhone
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

    /** Plays on from [positionMs], as a tap on a line of the transcript does. */
    fun playFrom(positionMs: Long) {
        val player = exo ?: return
        val duration = player.duration
        player.seekTo(if (duration > 0) positionMs.coerceIn(0L, duration) else max(0L, positionMs))
        player.play()
        publish()
    }

    /** Where it plays now, for following the transcript closer than the state's updates. */
    val positionNowMs: Long
        get() = exo?.currentPosition ?: _state.value.positionMs

    /** Back as far as the video player goes back. */
    fun skipBack() = skipBy(-backMs)

    /** Forward as far as the video player goes forward. */
    fun skipForward() = skipBy(forwardMs)

    private fun skipBy(ms: Long) {
        val player = exo ?: return
        seekTo(player.currentPosition + ms)
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
        forgetVideo()
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
                // Keeps the phone and Wi-Fi awake while it streams with the screen off; set for
                // each episode, as one on the phone needs no Wi-Fi
                .setWakeMode(C.WAKE_MODE_NETWORK)
                // For the notification and Bluetooth, as far as the buttons
                .setSeekBackIncrementMs(backMs)
                .setSeekForwardIncrementMs(forwardMs)
                .build()
        // Only the sound of a video file on the phone, without decoding its picture
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setAudioOffloadPreferences(OFFLOAD)
                .build()
        offloading = true
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
                // The archiver's pictures with its token, which the session doesn't share
                .setBitmapLoader(ArchiverBitmapLoader(context, jrwiApi))
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

    /** Jellyfin hears that a video started, once it plays. */
    private fun tellJellyfinStarted() {
        val itemId = _state.value.video?.itemId ?: return
        if (jellyfinStarted) return
        jellyfinStarted = true
        scope.launch {
            try {
                repositories.get().postPlaybackStart(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not tell Jellyfin playback started")
            }
        }
    }

    /**
     * Reads the player's position often while it plays, saves it every 15 seconds, and skips or
     * offers to skip segments. With no page showing the player and no segments to skip, such as
     * with the screen off, it only wakes to save, so the phone can sleep in between, and goes back
     * to reading often as soon as a page shows it again.
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
                if (segments.isNotEmpty() || _state.subscriptionCount.value > 0) {
                    delay(TICK_MS)
                } else {
                    withTimeoutOrNull(SAVE_EVERY_MS) { _state.subscriptionCount.first { it > 0 } }
                }
            }
        }
    }

    /** Reads often again from now, such as once a video's segments are known. */
    private fun restartTicking() {
        if (tickJob?.isActive != true) return
        tickJob?.cancel()
        tickJob = null
        startTicking()
    }

    /**
     * Skips the segment playing now, or shows the button for it, as the video player's settings
     * say. The button stays while the segment plays, as it does in the video player while its
     * controls are showing, which they always are here.
     */
    private fun checkSegment(positionMs: Long) {
        // A little before the end, as the video player has it, so the button doesn't stay after
        val segment = segments.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs - 100
        }
        val rules = segmentRules
        when {
            segment == null -> {
                skippedSegment = null
                hideSegment()
            }
            rules.skipsOnItsOwn(segment) -> {
                // Once, as the seek takes a moment
                if (skippedSegment != segment) {
                    skippedSegment = segment
                    exo?.seekTo(segment.endMs)
                }
            }
            rules.showsButton(segment) -> {
                if (_state.value.segment != segment) _state.update { it.copy(segment = segment) }
            }
            else -> hideSegment()
        }
    }

    private fun hideSegment() {
        if (_state.value.segment != null) _state.update { it.copy(segment = null) }
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
     * and when it's paused or left also to its video. A video's goes to Jellyfin, to TubeArchivist
     * for one of its videos, and to the archive for a JRWI episode's video. Most of the way through
     * counts as watched.
     */
    private fun save(kind: Save): Job? {
        val player = exo ?: return null
        val episode = _state.value.episode ?: return null
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val finished = kind == Save.END || (duration != null && position >= duration * WATCHED_PART)
        val linked = linkedJrwi
        val now = SystemClock.elapsedRealtime()
        lastSaveMs = now
        // A JRWI episode on the phone with nothing showing it: the regular saves are kept on the
        // phone, and one goes to the archiver every couple of minutes, so the radio can rest
        if (
            episode is AudioEpisode.Jrwi &&
                kind == Save.TICK &&
                !finished &&
                fromPhone &&
                _state.subscriptionCount.value == 0 &&
                now - lastSentMs < SEND_EVERY_MS
        ) {
            jrwiProgress.audioKept(episode.item, position / 1000.0, duration?.div(1000.0))
            return null
        }
        lastSentMs = now
        return scope.launch {
            // Saved even when the player is closed meanwhile
            withContext(NonCancellable) {
                when (episode) {
                    is AudioEpisode.Jrwi ->
                        saveJrwi(episode.item, position, duration, finished, kind != Save.TICK)
                    is AudioEpisode.Video ->
                        saveVideo(episode, linked, position, duration, finished, kind)
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

    private suspend fun saveVideo(
        episode: AudioEpisode.Video,
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
                    repository.postPlaybackProgress(episode.itemId, ticks, kind == Save.PAUSE)
                Save.LEAVE,
                Save.END -> {
                    val percent = durationMs?.let { (positionMs * 100 / it).toInt() } ?: 0
                    repository.postPlaybackStop(episode.itemId, ticks, percent)
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
            // Pages showing the video update when it's left or paused, not every few seconds; for
            // Jellyfin, saving it tells them
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

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        // Kept from when it only played JRWI audio, so the speed set then stays
        private const val PREFS = "jrwi_audio"
        private const val SPEED = "speed"
        private const val SESSION_ID = "audio"
        private const val SESSION_ACTIVITY_REQUEST = 7311
        private const val JRWI_ALBUM = "JRWI"
        private const val SAVE_EVERY_MS = 15_000L
        // How often a save goes to the archiver while an episode on the phone plays unseen
        private const val SEND_EVERY_MS = 120_000L
        private const val TICK_MS = 250L

        /**
         * The phone's audio chip plays the sound where it can while the processor sleeps, for long
         * episodes with the screen off, only where it can change the speed too.
         */
        private val OFFLOAD =
            AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsSpeedChangeSupportRequired(true)
                .build()

        private val AUDIO_TRACK_ERRORS =
            setOf(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            )
        private const val WATCHED_PART = 0.9
        private const val REWIND_AFTER_PAUSE_MS = 60_000L
        private const val REWIND_MS = 5_000L
        private const val SLEEP_FADE_MS = 10_000L
        private const val MIN_SLEEP_MS = 60_000L
        // A saved place further than this from the player's is taken over
        private const val RESYNC_MS = 3_000L
        // Looking for the next episode: pages of a show's tab, oldest first
        private const val NEXT_PAGE_SIZE = 200
        private const val NEXT_MAX_PAGES = 10
        private const val FADE_STEP_MS = 100L
        // Jellyfin counts time in ticks of 100 nanoseconds
        private const val TICKS_PER_MS = 10_000L
        private const val SPONSORBLOCK_OFF = "off"
        private const val SPONSORBLOCK_AUTO = "auto"
    }
}
