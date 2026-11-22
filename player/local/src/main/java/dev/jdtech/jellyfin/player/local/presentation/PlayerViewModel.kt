package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.domain.AvSyncGuard
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.domain.SYNC_WATCH_MS
import dev.jdtech.jellyfin.player.local.domain.TaQueueEntry
import dev.jdtech.jellyfin.player.local.domain.TrickplaySheets
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaNowPlaying
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistCache
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessions
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.loadPlaylist
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
    private val taApi: TaApi,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val taOfflineStore: TaOfflineStore,
    private val taPlaylistSessions: TaPlaylistSessions,
    private val taPlaylistCache: TaPlaylistCache,
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
) : ViewModel(), Player.Listener {
    val player: Player

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemTitle = "",
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                fileLoaded = false,
                currentQuality = StreamQuality.ORIGINAL,
                canChangeQuality = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    private val eventsChannel = Channel<PlayerEvents>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    data class UiState(
        val currentItemTitle: String,
        /** The channel of the video, or the show of the episode, under the title. */
        val currentItemSubtitle: String? = null,
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        val currentQuality: StreamQuality,
        /** False for downloaded items, which play from the device. */
        val canChangeQuality: Boolean,
        /** Whether the current item has subtitles to choose from. */
        val hasSubtitles: Boolean = false,
        /** Whether a subtitle track is showing. */
        val subtitlesOn: Boolean = false,
        /** Whether only the sound of the current item is streamed. */
        val isAudioOnly: Boolean = false,
        /** Whether the next video plays on its own when this one ends. */
        val autoplay: Boolean = true,
        /** The video that plays next, shown while the current one is about to end. */
        val upNext: UpNext? = null,
        /** The sleep timer, when one is set. */
        val sleepTimer: SleepTimer? = null,
        /** The TubeArchivist playlist playing, and its name. */
        val taPlaylistId: String? = null,
        val taPlaylistName: String? = null,
        /** Whether the playlist starts over after its last video. */
        val loopPlaylist: Boolean = false,
        /** The playlist's videos in order, for the playlist in the player. */
        val taPlaylistVideos: List<TaVideo> = emptyList(),
        /** Which of them is playing. */
        val taVideoId: String? = null,
        /** Whether it's a JRWI episode, archived or on YouTube, for the player's JRWI look. */
        val isJrwi: Boolean = false,
        /**
         * The JRWI post whose transcript follows what plays, timed in its timeline: the episode's
         * video, or its audio while that plays here. Null without a transcript (v0.23).
         */
        val transcriptPostId: Long? = null,
    )

    /**
     * The video that's about to play, in how many seconds, and a picture of it to load: a
     * downloaded copy or one on the server.
     */
    data class UpNext(val title: String, val secondsLeft: Int, val image: String? = null)

    /** When the sleep timer stops playback. */
    sealed interface SleepTimer {
        /** Pauses at this moment, in [SystemClock.elapsedRealtime] milliseconds. */
        data class At(val endsAt: Long) : SleepTimer {
            val millisLeft: Long
                get() = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }

        /** Stops when the video ends, without the next one playing. */
        data object EndOfVideo : SleepTimer
    }

    private var items: MutableList<PlayerItem> = mutableListOf()

    // The item that was already put back in step, so it is only done once per item
    private var resyncedMediaId: String? = null
    private var resyncJob: Job? = null

    // Watches sound and picture staying in step while a video plays, and what happened to them
    private val syncGuard = AvSyncGuard(appPreferences.getValue(appPreferences.playerSyncGuard))
    private var syncWatchJob: Job? = null

    // What fetches and reads the scrubbing thumbnails of the video that's playing, and which video
    // they're of
    private var trickplayJob: Job? = null
    private var trickplayMediaId: String? = null

    // Whether each video is put back in step once it has played for a moment, needed or not
    private val resyncAfterStart = appPreferences.getValue(appPreferences.playerResyncAfterStart)

    // Whether playback was tunneled when the picture was last turned off, as it will be again
    // when it comes back
    private var tunneledBeforeHidden = false

    // Counts down to pausing, for the sleep timer
    private var sleepJob: Job? = null

    // Whether the next video plays when one ends, and the video for which that was called off
    private var autoplay = appPreferences.getValue(appPreferences.playerAutoplay)
    private var upNextCancelledFor: String? = null

    // Whether the playlist starts over after its last video, as on YouTube. Only for this time
    // the player is open
    private var loopPlaylist = false

    // The TubeArchivist playlist being played through, if the video was started from one
    // Also told to the menu, which offers to open the playlist
    private var taPlaylistId: String? = null
        set(value) {
            field = value
            _uiState.update {
                it.copy(taPlaylistId = value, taPlaylistName = taPlaylistName.ifBlank { null })
            }
        }

    private var taPlaylistName: String = ""

    // Switching to sound only while the player is in the background
    private var backgroundAudioOnlyJob: Job? = null
    private var qualityBeforeBackground: StreamQuality? = null

    private val trackSelector = DefaultTrackSelector(application)

    // The names of the decoders in use, which only ExoPlayer's events tell. Before the player is
    // made, which listens with it
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private val decoderNames =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoderName = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoderName = decoderName
            }
        }

    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<FindroidSegment> = emptyList()

    // TubeArchivist: the video ID of the current item, its SponsorBlock segments and when its
    // progress was last saved there
    // Also told to the app's pages, which mark the video while it's open here
    private var taVideoId: String? = null
        set(value) {
            field = value
            TaNowPlaying.set(value, isPlayingNow)
        }

    private var isPlayingNow = false
    private var taSegments: List<FindroidSegment> = emptyList()
    private var taLastReport = 0L

    // JRWI: the archiver's item of what's playing, whose video and audio share one position, and
    // the media ID of its audio while the audio is what's playing
    private var jrwiItem: JrwiItem? = null
        set(value) {
            field = value
            _uiState.update {
                it.copy(isJrwi = value != null, transcriptPostId = value?.let(::transcriptPost))
            }
        }

    /** The post whose transcript lines are timed for what plays of [item]: its audio or video. */
    private fun transcriptPost(item: JrwiItem): Long? {
        val audio = item.audio
        val playsAudio =
            audio != null && jrwiAudioMediaId == jrwiAudioMediaId(audio.postId).toString()
        return item.transcriptPost(audio = playsAudio)
    }

    private var jrwiAudioMediaId: String? = null
    private var jrwiLastReport = 0L
    private val sponsorBlockMode = appPreferences.getValue(appPreferences.tubeArchivistSponsorBlock)

    /** Whether the player needs to check for skippable segments while playing. */
    val checksSegments: Boolean
        get() =
            segmentsSkipButton ||
                segmentsAutoSkip ||
                (taApi.isConfigured && sponsorBlockMode != SPONSORBLOCK_OFF)

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    init {
        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(appPreferences.getValue(appPreferences.playerTunneling))
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )

        val renderersFactory =
            syncGuard
                .renderersFactory(application)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val seekBackMs = appPreferences.getValue(appPreferences.playerSeekBackInc)
        // What was just played stays loaded for a few seconds, back to a frame the picture can
        // start from. A skip back, the picture coming back after the background, and a resync then
        // play from what's loaded, rather than loading the stream again. Kept short, as it comes
        // out of the same memory as what's loaded ahead
        val loadControl =
            DefaultLoadControl.Builder()
                .setBackBuffer(
                    seekBackMs.coerceIn(0L, BACK_BUFFER_MAX_MS).toInt(),
                    /* retainBackBufferFromKeyframe= */ true,
                )
                .build()
        player =
            ExoPlayer.Builder(application, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(seekBackMs)
                .setSeekForwardIncrementMs(
                    appPreferences.getValue(appPreferences.playerSeekForwardInc)
                )
                .setPauseAtEndOfMediaItems(true)
                .build()
                .also {
                    it.addAnalyticsListener(decoderNames)
                    syncGuard.attach(it)
                }
    }

    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        startPositionMs: Long? = null,
        taPlaylistId: String? = null,
        jrwiAudioPostId: Long? = null,
    ) {
        player.addListener(this)
        _uiState.update { it.copy(autoplay = autoplay) }
        jrwiItem = null
        jrwiAudioMediaId = null
        // The audio of a JRWI episode, from the archiver rather than Jellyfin
        if (jrwiAudioPostId != null) {
            // Nothing of a TubeArchivist video that played before it in this player
            taVideoId = null
            taSegments = emptyList()
            startJrwiAudio(jrwiAudioPostId, startFromBeginning)
            return
        }
        // A position from TubeArchivist, which may be newer than the one in Jellyfin
        if (!startFromBeginning && startPositionMs != null && startPositionMs > 0) {
            playbackPosition = startPositionMs
        }

        viewModelScope.launch {
            // Started from a TubeArchivist playlist: its other videos play before and after
            this@PlayerViewModel.taPlaylistId = null
            playlistManager.setTubeArchivistQueue(null)
            if (taPlaylistId != null) {
                try {
                    // The copy saved on the phone when TubeArchivist can't be reached
                    val playlist =
                        try {
                            taApi.getPlaylist(taPlaylistId).also {
                                taPlaylistCache.updatePlaylist(it)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            taPlaylistCache.loadPlaylist(taPlaylistId)?.playlist ?: throw e
                        }
                    playlistManager.setTubeArchivistQueue(
                        playlist.entries
                            .filter { it.downloaded }
                            .map { TaQueueEntry(videoId = it.youtubeId, title = it.title) }
                    )
                    taPlaylistName = playlist.name
                    this@PlayerViewModel.taPlaylistId = taPlaylistId
                    showTaPlaylistVideos(playlist)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not load playlist $taPlaylistId, playing the video alone")
                }
            }

            val startItem =
                try {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.fromName(itemKind),
                        mediaSourceIndex = null,
                        startFromBeginning = startFromBeginning,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            player.play()
        }
    }

    /**
     * Plays the audio of a JRWI episode from the archiver, where it left off. Its progress goes to
     * the archiver, and to the episode's video when it's paused or left.
     */
    private fun startJrwiAudio(postId: Long, startFromBeginning: Boolean) {
        viewModelScope.launch {
            // Nothing of a playlist played before it
            taPlaylistId = null
            playlistManager.setTubeArchivistQueue(null)
            // On the phone, it plays from there, and without a connection from what was kept
            val download = jrwiDownloads.forAudio(postId)
            val item =
                try {
                    jrwiApi.getItem(postId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val saved = download?.let { jrwiDownloads.savedItem(it.itemId) }
                    if (saved == null) {
                        Timber.e(e)
                        Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                        eventsChannel.trySend(PlayerEvents.NavigateBack)
                        return@launch
                    }
                    saved
                }
            val audio = item.audio
            val file = download?.let { jrwiDownloads.audioFile(it.itemId) }?.takeIf { it.exists() }
            val url =
                file?.let { Uri.fromFile(it).toString() } ?: jrwiApi.fileUrlWithToken(audio?.url)
            if (audio == null || url == null) {
                eventsChannel.trySend(PlayerEvents.NavigateBack)
                return@launch
            }
            val progress =
                if (startFromBeginning) null else jrwiProgress.progress(item, audio = true)
            val seconds = progress?.takeIf { !it.finished }?.position ?: 0.0
            val mediaId = jrwiAudioMediaId(audio.postId)
            // Before the item, which reads it for the transcript's timeline
            jrwiAudioMediaId = mediaId.toString()
            jrwiItem = item
            jrwiLastReport = 0L
            val playerItem =
                PlayerItem(
                    name = item.title,
                    itemId = mediaId,
                    mediaSourceId = "jrwi-${audio.postId}",
                    playbackPosition = (seconds * 1000).toLong(),
                    mediaSourceUri = url,
                    // Played as it is, so there's no other quality to pick
                    isLocal = true,
                    isAudioOnly = true,
                    subtitle = jrwiProgress.showNameOrNull(item.show) ?: download?.showName,
                )
            items = mutableListOf(playerItem)
            currentMediaItemIndex = 0
            player.setMediaItems(listOf(playerItem.toMediaItem()), 0, playerItem.playbackPosition)
            player.prepare()
            player.play()
        }
    }

    /** The page of what's playing, which tapping its title opens. */
    sealed interface ItemPage {
        data class Jrwi(val postId: Long) : ItemPage

        data class TubeArchivist(val videoId: String, val playlistId: String?) : ItemPage

        data class Jellyfin(val itemId: UUID, val isEpisode: Boolean) : ItemPage
    }

    /**
     * The page of what's playing: its JRWI post, its TubeArchivist video, or else its Jellyfin
     * episode or movie.
     */
    fun currentItemPage(): ItemPage? {
        // A JRWI video that's only on YouTube plays as a TubeArchivist video, so that page it is
        val jrwi = jrwiItem
        if (jrwi != null && taVideoId == null) return ItemPage.Jrwi(jrwi.id)
        taVideoId?.let {
            return ItemPage.TubeArchivist(it, _uiState.value.taPlaylistId)
        }
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        val item = items.firstOrNull { it.itemId.toString() == mediaId } ?: return null
        return ItemPage.Jellyfin(item.itemId, isEpisode = item.indexNumber != null)
    }

    /** Whether [mediaId] is the audio of a JRWI episode, played from the archiver. */
    private fun isJrwiAudio(mediaId: String?): Boolean =
        mediaId != null && mediaId == jrwiAudioMediaId

    /** Looks up whether a Jellyfin video is a JRWI episode, whose audio shares its progress. */
    private suspend fun loadJrwiInfo(itemId: UUID) {
        jrwiItem = null
        jrwiLastReport = 0L
        if (!jrwiApi.isConfigured) return
        // A video downloaded from its JRWI page kept its episode, for playing without a connection
        val item =
            jrwiProgress.itemForEpisode(itemId)
                ?: jrwiDownloads.savedVideoForEpisode(itemId.toString())?.item
                ?: return
        // Kept without audio too, for the title to open the post
        if (player.currentMediaItem?.mediaId == itemId.toString()) jrwiItem = item
    }

    /**
     * Looks up whether a TubeArchivist video is one of the archiver's YouTube-only videos, so its
     * progress goes to the archive as well.
     */
    private suspend fun loadJrwiYoutubeInfo(itemId: UUID, videoId: String) {
        val item = jrwiProgress.itemForYoutube(videoId) ?: return
        if (player.currentMediaItem?.mediaId == itemId.toString() && jrwiItem == null) {
            jrwiItem = item
        }
    }

    /**
     * Saves the position of a JRWI episode in the archive, which keeps one record for its video and
     * audio. Playing the audio also hands the position to the video, when [force]d.
     */
    private fun reportJrwiProgress(force: Boolean, finished: Boolean = false) {
        val item = jrwiItem ?: return
        val now = System.currentTimeMillis()
        if (!finished) {
            if (!force && now - jrwiLastReport < TA_PROGRESS_INTERVAL_MS) return
            // Paused and already saved
            if (force && jrwiLastReport < 0) return
        }
        jrwiLastReport = if (force || finished) -1L else now
        val seconds = player.currentPosition / 1000.0
        val length = player.duration.takeIf { it > 0 }?.div(1000.0)
        val audio = isJrwiAudio(player.currentMediaItem?.mediaId)
        viewModelScope.launch {
            saveJrwiProgress(item, audio, seconds, length, finished, handOff = force)
        }
    }

    private suspend fun saveJrwiProgress(
        item: JrwiItem,
        audio: Boolean,
        seconds: Double,
        lengthSeconds: Double?,
        finished: Boolean,
        handOff: Boolean,
    ) {
        try {
            if (audio) jrwiProgress.audioPlayed(item, seconds, finished, handOff, lengthSeconds)
            else jrwiProgress.videoPlayed(item, seconds, finished, lengthSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not save the JRWI progress of ${item.id}")
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles = externalSubtitles.map { externalSubtitle ->
            MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                .setLabel(
                    externalSubtitle.title.ifBlank { application.getString(R.string.external) }
                )
                .setMimeType(externalSubtitle.mimeType)
                .setLanguage(externalSubtitle.language)
                .build()
        }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                // Converted streams are HLS playlists
                .setMimeType(if (isTranscoded) MimeTypes.APPLICATION_M3U8 else null)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                .setSubtitleConfigurations(mediaSubtitles)
                .build()

        return mediaItem
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun releasePlayer() {
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration
        val finalTaVideoId = taVideoId
        taVideoId = null
        val finalJrwiItem = jrwiItem
        val jrwiAudio = isJrwiAudio(mediaId)
        jrwiItem = null
        GlobalScope.launch {
            delay(200L)
            if (finalJrwiItem != null) {
                // Most of the way through counts as watched, as in Jellyfin
                val finished = duration > 0 && position >= duration * WATCHED_PART
                saveJrwiProgress(
                    finalJrwiItem,
                    jrwiAudio,
                    position / 1000.0,
                    duration.takeIf { it > 0 }?.div(1000.0),
                    finished,
                    handOff = true,
                )
            }
            // The archiver's audio isn't in Jellyfin
            if (jrwiAudio) return@launch
            if (finalTaVideoId != null) {
                try {
                    taApi.postProgress(finalTaVideoId, position / 1000.0)
                    taOfflineStore.clearPendingProgress(finalTaVideoId)
                } catch (e: Exception) {
                    Timber.w(e, "Could not save progress in TubeArchivist")
                    taOfflineStore.addPendingProgress(finalTaVideoId, position / 1000.0)
                }
                MediaChanges.watched(videoId = finalTaVideoId)
            }
            try {
                if (mediaId != null && duration != C.TIME_UNSET) {
                    Timber.d("Sending playback stop")
                    repository.postPlaybackStop(
                        UUID.fromString(mediaId),
                        position.times(10000),
                        position.div(duration.toFloat()).times(100).toInt(),
                    )
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        clearTrickplay()
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        player.release()
    }

    fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        viewModelScope.launch(Dispatchers.Main) {
            savedStateHandle["position"] = player.currentPosition
            if (isJrwiAudio(player.currentMediaItem?.mediaId)) {
                reportJrwiProgress(force = !player.isPlaying)
            } else if (
                player.currentMediaItem != null && player.currentMediaItem!!.mediaId.isNotEmpty()
            ) {
                val itemId = UUID.fromString(player.currentMediaItem!!.mediaId)
                reportJrwiProgress(force = !player.isPlaying)
                try {
                    repository.postPlaybackProgress(
                        itemId,
                        player.currentPosition.times(10000),
                        !player.isPlaying,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
                reportTaProgress(force = !player.isPlaying)
            }
        }
    }

    /** Saves the position in TubeArchivist as well, every so often and whenever paused. */
    private fun reportTaProgress(force: Boolean) {
        val videoId = taVideoId ?: return
        val now = System.currentTimeMillis()
        if (!force && now - taLastReport < TA_PROGRESS_INTERVAL_MS) return
        // Paused and already saved there
        if (force && taLastReport < 0) return
        taLastReport = if (force) -1L else now
        val seconds = player.currentPosition / 1000.0
        viewModelScope.launch {
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
            if (force) MediaChanges.watched(videoId = videoId)
        }
    }

    /** Looks the item up in TubeArchivist, for its SponsorBlock segments and saving progress. */
    private suspend fun loadTubeArchivistInfo(itemId: UUID) {
        taVideoId = null
        taSegments = emptyList()
        taLastReport = 0L
        if (!taApi.isConfigured) return
        val isCurrent = { player.currentMediaItem?.mediaId == itemId.toString() }

        // Saved with the download, so this works without a connection
        val savedVideoId = taOfflineStore.videoIdForItem(itemId)
        if (savedVideoId != null) {
            val saved = taOfflineStore.load(savedVideoId)
            if (!isCurrent()) return
            taVideoId = savedVideoId
            if (sponsorBlockMode != SPONSORBLOCK_OFF && saved != null) {
                taSegments = saved.video.sponsorBlockSegments()
            }
        }

        try {
            val videoId = savedVideoId ?: taJellyfinLookup.videoIdForItem(itemId) ?: return
            if (!isCurrent()) return
            taVideoId = videoId
            // Apart from the segments, so a slow archiver doesn't hold them up
            viewModelScope.launch { loadJrwiYoutubeInfo(itemId, videoId) }
            if (sponsorBlockMode == SPONSORBLOCK_OFF) return
            val segments = taApi.getVideo(videoId).sponsorBlockSegments()
            if (isCurrent()) taSegments = segments
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load TubeArchivist info")
        }
    }

    /** Skippable SponsorBlock segments, in the player's milliseconds. */
    private fun TaVideo.sponsorBlockSegments(): List<FindroidSegment> =
        sponsorblock?.segments.orEmpty().mapNotNull { segment ->
            val start = segment.segment.getOrNull(0) ?: return@mapNotNull null
            val end = segment.segment.getOrNull(1) ?: return@mapNotNull null
            // Segments that only mark a point (such as highlights) can't be skipped
            if (end - start < 1.0 || segment.actionType == "poi") return@mapNotNull null
            FindroidSegment(
                type = FindroidSegmentType.UNKNOWN,
                startTicks = (start * 1000).toLong(),
                endTicks = (end * 1000).toLong(),
                sponsorBlockCategory = segment.category,
            )
        }

    fun updateCurrentSegment() {
        Timber.d("Updating current segment")
        viewModelScope.launch(Dispatchers.Main) {
            val segments = currentMediaItemSegments + taSegments
            if (segments.isEmpty()) {
                return@launch
            }

            val milliSeconds = player.currentPosition

            // Get current segment, - 100 milliseconds to avoid showing button after segment ends
            val currentSegment = segments.find { segment ->
                milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
            }

            if (currentSegment == null) {
                // Remove button if not pressed and there is no current segment
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null) }
                }
                return@launch
            }

            Timber.tag("SegmentInfo").d("currentSegment: %s", currentSegment)

            // SponsorBlock follows its own setting
            if (currentSegment.sponsorBlockCategory != null) {
                if (sponsorBlockMode == SPONSORBLOCK_AUTO) {
                    skipSegment(currentSegment)
                } else if (_uiState.value.currentSegment != currentSegment) {
                    _uiState.update {
                        it.copy(
                            currentSegment = currentSegment,
                            currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                        )
                    }
                }
                return@launch
            }

            if (
                segmentsAutoSkip &&
                    segmentsAutoSkipTypes.contains(currentSegment.type.toString()) &&
                    (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.ALWAYS ||
                        (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.PIP &&
                            isInPictureInPictureMode))
            ) {
                // Auto Skip segment
                skipSegment(currentSegment)
            } else if (segmentsSkipButtonTypes.contains(currentSegment.type.toString())) {
                // Skip Button segment
                _uiState.update {
                    it.copy(
                        currentSegment = currentSegment,
                        currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                    )
                }
            } else {
                _uiState.update { it.copy(currentSegment = null) }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        // The same video loaded again, as in another quality, has the same thumbnails
        if (mediaItem?.mediaId != trickplayMediaId) clearTrickplay()
        // A video that plays straight on from the last one doesn't pause in between
        resyncJob?.cancel()
        if (player.isPlaying) resyncOnce()
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex
        viewModelScope.launch {
            try {
                items
                    .first { it.itemId.toString() == player.currentMediaItem?.mediaId }
                    .let { item ->
                        val hideNumbers = appPreferences.getValue(appPreferences.hideEpisodeNumbers)
                        val itemTitle =
                            if (
                                !hideNumbers &&
                                    item.parentIndexNumber != null &&
                                    item.indexNumber != null
                            ) {
                                if (item.indexNumberEnd == null) {
                                    "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
                                } else {
                                    "S${item.parentIndexNumber}:E${item.indexNumber}-${item.indexNumberEnd} - ${item.name}"
                                }
                            } else {
                                item.name
                            }
                        _uiState.update {
                            it.copy(
                                currentItemTitle = itemTitle,
                                currentItemSubtitle = item.subtitle,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                fileLoaded = false,
                                currentQuality = playlistManager.quality,
                                canChangeQuality = !item.isLocal,
                                isAudioOnly = item.isAudioOnly,
                            )
                        }

                        // The archiver's audio has nothing more to look up in Jellyfin
                        if (isJrwiAudio(item.itemId.toString())) return@let

                        launch { loadJrwiInfo(item.itemId) }

                        repository.postPlaybackStart(item.itemId)

                        if (segmentsSkipButton || segmentsAutoSkip) {
                            getSegments(item.itemId)
                        }

                        launch { loadTubeArchivistInfo(item.itemId) }

                        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                            getTrickplay(item)
                        }

                        playlistManager.setCurrentMediaItemIndex(item.itemId)
                        _uiState.update { it.copy(taVideoId = playlistManager.currentTaVideoId) }
                        rememberPlaylistPosition()

                        val previousItem = playlistManager.getPreviousPlayerItem()
                        if (previousItem != null) {
                            items.add(player.currentMediaItemIndex, previousItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex,
                                previousItem.toMediaItem(),
                            )
                        }

                        val nextItem = playlistManager.getNextPlayerItem()
                        if (nextItem != null) {
                            items.add(player.currentMediaItemIndex + 1, nextItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex + 1,
                                nextItem.toMediaItem(),
                            )
                        }

                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Report playback stopped for current item and transition to the next one
        if (
            !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                player.playbackState == ExoPlayer.STATE_READY
        ) {
            val endedVideoId = taVideoId
            reportJrwiProgress(force = true, finished = true)
            viewModelScope.launch {
                val mediaId = player.currentMediaItem?.mediaId
                val position = player.currentPosition
                val duration = player.duration
                try {
                    if (!isJrwiAudio(mediaId)) {
                        repository.postPlaybackStop(
                            UUID.fromString(mediaId),
                            position.times(10000),
                            position.div(duration.toFloat()).times(100).toInt(),
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
                endedVideoId?.let { MediaChanges.watched(videoId = it) }
                val cancelled = upNextCancelledFor == mediaId
                // The sleep timer was set to stop here
                val sleep = _uiState.value.sleepTimer == SleepTimer.EndOfVideo
                val startOver = loopsAfter(mediaId)
                _uiState.update {
                    it.copy(upNext = null, sleepTimer = if (sleep) null else it.sleepTimer)
                }
                if (autoplay && !cancelled && !sleep && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                } else if (startOver) {
                    restartTaPlaylist()
                } else {
                    // The playlist carries on from the next video next time
                    taPlaylistId?.let { playlistId ->
                        val next = playlistManager.nextTaVideoId
                        if (next != null) {
                            taPlaylistSessions.update(playlistId, taPlaylistName, next)
                        } else {
                            taPlaylistSessions.remove(playlistId)
                        }
                    }
                    eventsChannel.trySend(PlayerEvents.NavigateBack)
                }
            }
        }
    }

    /**
     * Whether the playlist starts over once [mediaId], its last video, ends: it loops, and the next
     * video would have played on its own.
     */
    private fun loopsAfter(mediaId: String?): Boolean =
        loopPlaylist &&
            autoplay &&
            taPlaylistId != null &&
            playlistManager.firstTaTitle != null &&
            !player.hasNextMediaItem() &&
            mediaId != upNextCancelledFor &&
            _uiState.value.sleepTimer != SleepTimer.EndOfVideo

    /** Turns starting the playlist over after its last video on or off. */
    fun setLoopPlaylist(enabled: Boolean) {
        loopPlaylist = enabled
        _uiState.update { it.copy(loopPlaylist = enabled) }
        updateUpNext()
    }

    /** Starts the playlist over from its first video, once its last one has ended. */
    private fun restartTaPlaylist() {
        startTaPlaylistQueue(onFailure = { eventsChannel.trySend(PlayerEvents.NavigateBack) }) {
            playlistManager.restartTaQueue()
        }
    }

    /** Plays one of the playlist's videos, picked from the playlist in the player. */
    fun playTaPlaylistVideo(videoId: String) {
        if (videoId == playlistManager.currentTaVideoId) return
        _uiState.update { it.copy(upNext = null) }
        reportLeaving()
        startTaPlaylistQueue(
            onFailure = {
                Toast.makeText(
                        application,
                        R.string.player_playlist_video_missing,
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        ) {
            playlistManager.startTaQueueAt(videoId)
        }
    }

    /** Swaps what's queued in the player for a new queue that starts with what [start] gives. */
    private fun startTaPlaylistQueue(onFailure: () -> Unit, start: suspend () -> PlayerItem?) {
        viewModelScope.launch {
            val first =
                try {
                    start()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Could not move in the playlist")
                    null
                }
            if (first == null) {
                onFailure()
                return@launch
            }
            items = mutableListOf(first)
            currentMediaItemIndex = 0
            player.setMediaItems(listOf(first.toMediaItem()), 0, first.playbackPosition)
            player.prepare()
            player.play()
        }
    }

    /**
     * Shows the playlist's videos in the player's playlist: the copy on the phone, or just their
     * titles, until TubeArchivist answers with them in full.
     */
    private suspend fun showTaPlaylistVideos(playlist: TaPlaylist) {
        val saved = taPlaylistCache.loadPlaylist(playlist.id)?.videos.orEmpty()
        val videos = saved.ifEmpty {
            playlist.entries
                .filter { it.downloaded }
                .map { TaVideo(youtubeId = it.youtubeId, title = it.title) }
        }
        _uiState.update { it.copy(taPlaylistVideos = videos) }
        refreshTaPlaylistVideos()
    }

    /** Loads the playlist's videos again, with how far each was watched, as the playlist opens. */
    fun refreshTaPlaylistVideos() {
        val playlistId = taPlaylistId ?: return
        viewModelScope.launch {
            try {
                val videos = taApi.loadPlaylist(playlistId).second
                if (taPlaylistId == playlistId)
                    _uiState.update { it.copy(taPlaylistVideos = videos) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load the videos of playlist $playlistId")
            }
        }
    }

    /** The address of TubeArchivist, for the thumbnails in the player's playlist. */
    val taBaseUrl: String?
        get() = taApi.baseUrl

    /** Notes where the TubeArchivist playlist is, so Home can carry on from there. */
    private fun rememberPlaylistPosition() {
        val playlistId = taPlaylistId ?: return
        val videoId = playlistManager.currentTaVideoId ?: return
        taPlaylistSessions.update(playlistId, taPlaylistName, videoId)
    }

    /** Turns playing the next video on its own on or off, for this and later videos. */
    fun setAutoplay(enabled: Boolean) {
        autoplay = enabled
        appPreferences.setValue(appPreferences.playerAutoplay, enabled)
        if (enabled) upNextCancelledFor = null
        _uiState.update { it.copy(autoplay = enabled, upNext = if (enabled) it.upNext else null) }
    }

    /**
     * Shows the next video during the last seconds of this one, when it's going to play on its own.
     */
    fun updateUpNext() {
        val mediaId = player.currentMediaItem?.mediaId
        val duration = player.duration
        val remaining = duration - player.currentPosition
        // A looping playlist's first video comes after its last
        val startOver = loopsAfter(mediaId)
        val upNext =
            if (
                autoplay &&
                    _uiState.value.sleepTimer != SleepTimer.EndOfVideo &&
                    mediaId != null &&
                    mediaId != upNextCancelledFor &&
                    (player.hasNextMediaItem() || startOver) &&
                    duration > 0 &&
                    remaining in 0..UP_NEXT_MS
            ) {
                if (player.hasNextMediaItem()) {
                    val next = player.getMediaItemAt(player.nextMediaItemIndex)
                    UpNext(
                        title = next.mediaMetadata.title?.toString().orEmpty(),
                        secondsLeft = ceil(remaining / 1000.0).toInt(),
                        image = upNextImage(next.mediaId),
                    )
                } else {
                    UpNext(
                        title = playlistManager.firstTaTitle.orEmpty(),
                        secondsLeft = ceil(remaining / 1000.0).toInt(),
                    )
                }
            } else {
                null
            }
        if (upNext != _uiState.value.upNext) _uiState.update { it.copy(upNext = upNext) }
    }

    // The picture found for the next video, kept so the files aren't looked at on every update
    private var upNextImageFor: Pair<String, String?>? = null

    /**
     * The next video's picture: the copy saved with its download, or else the one on the Jellyfin
     * server, which TubeArchivist's plugin sets to the video's thumbnail.
     */
    private fun upNextImage(itemId: String): String? {
        upNextImageFor?.let { (id, image) -> if (id == itemId) return image }
        val image =
            try {
                val saved = File(application.filesDir, "images/$itemId/primary")
                if (saved.exists()) {
                    Uri.fromFile(saved).toString()
                } else {
                    repository.getBaseUrl().trimEnd('/') +
                        "/items/$itemId/Images/Primary?fillWidth=$UP_NEXT_IMAGE_WIDTH&quality=90"
                }
            } catch (e: Exception) {
                Timber.w(e, "No picture for the next video")
                null
            }
        upNextImageFor = itemId to image
        return image
    }

    /** Tells Jellyfin and TubeArchivist where the video was left, as something else starts. */
    private fun reportLeaving() {
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration
        reportTaProgress(force = true)
        reportJrwiProgress(
            force = true,
            finished = duration > 0 && position >= duration * WATCHED_PART,
        )
        if (mediaId != null && duration > 0 && !isJrwiAudio(mediaId)) {
            viewModelScope.launch {
                try {
                    repository.postPlaybackStop(
                        UUID.fromString(mediaId),
                        position.times(10000),
                        position.div(duration.toFloat()).times(100).toInt(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    /** Plays the next video right away, saving where this one was left first. */
    fun playNextNow() {
        _uiState.update { it.copy(upNext = null) }
        val mediaId = player.currentMediaItem?.mediaId
        val startOver = loopsAfter(mediaId)
        if (!player.hasNextMediaItem() && !startOver) return
        reportLeaving()
        if (startOver) {
            restartTaPlaylist()
        } else {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    /**
     * Pauses in [minutes], turning the sound down over the last seconds so it doesn't stop
     * abruptly. Replaces a timer already set.
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _uiState.update { it.copy(sleepTimer = SleepTimer.At(endsAt), upNext = null) }
        sleepJob = viewModelScope.launch {
            // Only players that let their volume be set fade out
            val canFade = player.isCommandAvailable(Player.COMMAND_SET_VOLUME)
            var volumeBefore: Float? = null
            try {
                while (true) {
                    val left = endsAt - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    if (canFade && left <= SLEEP_FADE_MS) {
                        val before = volumeBefore ?: player.volume.also { volumeBefore = it }
                        player.volume = before * (left.toFloat() / SLEEP_FADE_MS)
                    }
                    delay(
                        if (left <= SLEEP_FADE_MS) SLEEP_FADE_STEP_MS
                        else minOf(left - SLEEP_FADE_MS, 1000L)
                    )
                }
                player.pause()
                reportTaProgress(force = true)
                _uiState.update { it.copy(sleepTimer = null) }
                eventsChannel.trySend(PlayerEvents.SleepTimerPaused)
            } finally {
                // Back to how loud it was, for when playback is started again
                volumeBefore?.let { player.volume = it }
            }
        }
    }

    /** Stops when this video ends, rather than playing the next one. */
    fun setSleepTimerAtEnd() {
        sleepJob?.cancel()
        sleepJob = null
        _uiState.update { it.copy(sleepTimer = SleepTimer.EndOfVideo, upNext = null) }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _uiState.update { it.copy(sleepTimer = null) }
    }

    /** Lets this video end without the next one playing after it. */
    fun cancelUpNext() {
        upNextCancelledFor = player.currentMediaItem?.mediaId
        _uiState.update { it.copy(upNext = null) }
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                _uiState.update { it.copy(fileLoaded = true) }
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                if (loopsAfter(player.currentMediaItem?.mediaId)) {
                    // A looping playlist starts over instead
                    restartTaPlaylist()
                } else {
                    // Watched to the end of the playlist
                    taPlaylistId?.let { playlistId ->
                        if (playlistManager.nextTaVideoId == null) {
                            taPlaylistSessions.remove(playlistId)
                        }
                    }
                    eventsChannel.trySend(PlayerEvents.NavigateBack)
                }
            }
        }
        Timber.d("Changed player state to $stateString")
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        releasePlayer()
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            player.currentTracks.groups
                                .filter { it.type == trackType && it.isSupported }[index]
                                .mediaTrackGroup,
                            0,
                        )
                    )
                    .setTrackTypeDisabled(trackType, false)
                    .build()
        }
    }

    // The subtitle track turned off last, to turn back on
    private var lastSubtitleTrack: String? = null

    /**
     * Turns subtitles off, or back on with the track that was on before, or else the first one.
     * Returns whether they're on now.
     */
    fun toggleSubtitles(): Boolean {
        val groups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (groups.isEmpty()) return false
        val selected = groups.indexOfFirst { it.isSelected }
        if (selected >= 0) {
            lastSubtitleTrack = groups[selected].mediaTrackGroup.id
            switchToTrack(C.TRACK_TYPE_TEXT, -1)
            return false
        }
        val previous = groups.indexOfFirst { it.mediaTrackGroup.id == lastSubtitleTrack }
        switchToTrack(C.TRACK_TYPE_TEXT, previous.coerceAtLeast(0))
        return true
    }

    /**
     * Streams every item that isn't downloaded in [quality] from now on, carrying on from the same
     * spot. [showErrors] tells the user when the server couldn't convert the video.
     */
    /**
     * Plays the picture as well when Settings stream only the sound, as asked for from the audio
     * player. Called before [initializePlayer].
     */
    fun preferVideo() {
        if (playlistManager.quality == StreamQuality.AUDIO_ONLY) {
            playlistManager.quality = StreamQuality.ORIGINAL
        }
    }

    fun selectQuality(quality: StreamQuality, showErrors: Boolean = true) {
        if (quality == playlistManager.quality) return
        playlistManager.quality = quality
        _uiState.update { it.copy(currentQuality = quality) }

        viewModelScope.launch {
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val playWhenReady = player.playWhenReady

            // All at once: the server is asked for each queued video's stream, and the one
            // playing waits for them all
            val reloaded = coroutineScope {
                items
                    .map { item ->
                        async {
                            if (item.isLocal) {
                                item
                            } else {
                                try {
                                    playlistManager.reloadPlayerItem(item) ?: item
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.e(e)
                                    item
                                }
                            }
                        }
                    }
                    .awaitAll()
            }

            val current = reloaded.getOrNull(index)
            if (
                showErrors &&
                    quality != StreamQuality.ORIGINAL &&
                    current != null &&
                    !current.isTranscoded
            ) {
                Toast.makeText(application, R.string.quality_failed, Toast.LENGTH_LONG).show()
            }
            _uiState.update { it.copy(isAudioOnly = current?.isAudioOnly == true) }

            // It played on while the streams were asked for, so it carries on from where it got to
            val resumeAt =
                if (player.currentMediaItemIndex == index) player.currentPosition else position
            items = reloaded.toMutableList()
            player.setMediaItems(items.map { it.toMediaItem() }, index, resumeAt)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
    }

    /**
     * Called when the player screen goes away while playback carries on. If the setting is on, it
     * switches to streaming only the sound after a short wait, so quickly checking another app
     * doesn't interrupt playback.
     */
    fun onPlayerHidden() {
        backgroundAudioOnlyJob?.cancel()
        if (!appPreferences.getValue(appPreferences.playerBackgroundAudioOnly)) return
        backgroundAudioOnlyJob = viewModelScope.launch {
            delay(BACKGROUND_AUDIO_ONLY_DELAY_MS)
            val current = items.getOrNull(player.currentMediaItemIndex) ?: return@launch
            if (current.isLocal || playlistManager.quality == StreamQuality.AUDIO_ONLY) {
                return@launch
            }
            qualityBeforeBackground = playlistManager.quality
            selectQuality(StreamQuality.AUDIO_ONLY, showErrors = false)
        }
    }

    /** Called when the player screen is shown again; brings the picture back if it was dropped. */
    fun onPlayerShown() {
        backgroundAudioOnlyJob?.cancel()
        backgroundAudioOnlyJob = null
        val previous = qualityBeforeBackground ?: return
        qualityBeforeBackground = null
        selectQuality(previous)
    }

    /**
     * Turns video decoding off while playing in the background and back on when the player is shown
     * again. The picture coming back joins the sound as it plays on and catches up by itself, which
     * the guard watches, and resyncs if it stays behind. Tunneled, where the phone times the
     * picture and nothing can be measured, or without the guard, it's put back in step with a
     * resync as before.
     */
    fun setVideoEnabled(enabled: Boolean) {
        val exo = player as? ExoPlayer ?: return
        val parameters = player.trackSelectionParameters
        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == !enabled) return
        try {
            if (!enabled) tunneledBeforeHidden = exo.isTunnelingEnabled
            player.trackSelectionParameters =
                parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
            if (enabled && (!syncGuard.enabled || tunneledBeforeHidden)) {
                resync("picture back after the background")
            }
            // The guard has nothing to look at without the picture, so it rests until it's back
            watchSync(player.isPlaying)
        } catch (e: IllegalStateException) {
            Timber.e(e)
        }
    }

    /** Whether tunneled playback is asked for, which the menu and settings turn on and off. */
    val tunnelingOn: Boolean
        get() = trackSelector.parameters.tunnelingEnabled

    /**
     * Turns tunneled playback on or off, for this video and from now on. ExoPlayer restarts the
     * decoders for it, so sound and picture are put back in step after.
     */
    fun setTunneling(enabled: Boolean) {
        appPreferences.setValue(appPreferences.playerTunneling, enabled)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setTunnelingEnabled(enabled)
        )
        resync(if (enabled) "tunneled playback turned on" else "tunneled playback turned off")
    }

    /**
     * What's playing and how, for the player's Playback info: the formats, the decoders, whether
     * playback is tunneled, and the frames shown and dropped. For finding out why sound and picture
     * drift apart.
     */
    fun playbackInfo(): List<Pair<String, String>> {
        val exo = player as? ExoPlayer ?: return emptyList()
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val counters = exo.videoDecoderCounters
        val uri = exo.currentMediaItem?.localConfiguration?.uri
        val local = uri?.scheme == "file" || uri?.scheme == "content"
        return buildList {
            add(
                "Source" to
                    if (local) "Downloaded"
                    else "Streamed, ${playlistManager.quality.name.lowercase()}"
            )
            video?.let { format ->
                add(
                    "Video" to
                        listOfNotNull(
                                format.codecs ?: format.sampleMimeType,
                                "${format.width}×${format.height}",
                                format.frameRate.takeIf { it > 0 }?.let { "%.2f fps".format(it) },
                                format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                            )
                            .joinToString(", ")
                )
            }
            add("Video decoder" to decoderDescription(videoDecoderName))
            audio?.let { format ->
                add(
                    "Audio" to
                        listOfNotNull(
                                format.codecs ?: format.sampleMimeType,
                                format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
                                format.channelCount.takeIf { it > 0 }?.let { "$it channels" },
                            )
                            .joinToString(", ")
                )
            }
            add("Audio decoder" to decoderDescription(audioDecoderName))
            add(
                "Tunneled playback" to
                    when {
                        !tunnelingOn -> "Off"
                        exo.isTunnelingEnabled -> "On, in use"
                        else -> "On, but not possible for this video on this phone"
                    }
            )
            counters?.let {
                it.ensureUpdated()
                add(
                    "Frames" to
                        "${it.renderedOutputBufferCount} shown, ${it.droppedBufferCount} dropped, " +
                            "${it.skippedOutputBufferCount} skipped"
                )
                add("Most dropped in a row" to it.maxConsecutiveDroppedBufferCount.toString())
            }
            add("Speed" to "${exo.playbackParameters.speed}x")
            add(
                "Buffered ahead" to
                    "%.1f s".format((exo.bufferedPosition - exo.currentPosition) / 1000.0)
            )
            addAll(syncGuard.info())
        }
    }

    /**
     * What happened to sound and picture lately, oldest first, each with its time of day:
     * rebuffers, the sound running dry, resyncs and why. Shown under the Playback info.
     */
    fun syncRecord(): List<String> = syncGuard.record()

    private fun decoderDescription(name: String?): String =
        when {
            name == null -> "Not known yet"
            // Android's own decoders run on the processor rather than the video hardware
            name.startsWith("c2.android.") || name.startsWith("OMX.google.") -> "$name (software)"
            name.contains("ffmpeg", ignoreCase = true) -> "$name (software)"
            else -> name
        }

    fun selectSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    private suspend fun getSegments(itemId: UUID) {
        try {
            currentMediaItemSegments = repository.getSegments(itemId)
        } catch (e: Exception) {
            currentMediaItemSegments = emptyList()
            Timber.e(e)
        }
    }

    /**
     * Sets up the scrubbing thumbnails of the video that's now playing. Their sheets are fetched in
     * the background and read when the scrubber gets to them, so this doesn't wait for any of it.
     */
    private fun getTrickplay(item: PlayerItem) {
        // Another video may have started while this one's details were being fetched
        val mediaId = item.itemId.toString()
        if (player.currentMediaItem?.mediaId != mediaId) return
        if (mediaId == trickplayMediaId && trickplayJob?.isActive == true) return
        clearTrickplay()
        val trickplayInfo = item.trickplayInfo ?: return
        Timber.d("Trickplay Resolution: ${trickplayInfo.width}")

        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val sheets =
            TrickplaySheets(trickplayInfo, CoroutineScope(viewModelScope.coroutineContext + job)) {
                sheet ->
                repository.getTrickplayData(item.itemId, trickplayInfo.width, sheet)
            }
        trickplayJob = job
        trickplayMediaId = mediaId
        _uiState.update { it.copy(currentTrickplay = sheets) }
        sheets.fetchAll()
    }

    /** Drops the scrubbing thumbnails, which are those of the video that played until now. */
    private fun clearTrickplay() {
        trickplayJob?.cancel()
        trickplayJob = null
        trickplayMediaId = null
        _uiState.update { it.copy(currentTrickplay = null) }
    }

    fun skipSegment(segment: FindroidSegment) {
        if (shouldSkipToNextEpisode(segment)) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(segment.endTicks)
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: FindroidSegment): Boolean {
        return if (segment.type == FindroidSegmentType.OUTRO && player.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = player.duration
            val thresholdMillis =
                playerDurationMillis -
                    appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold)

            segmentEndTimeMillis > thresholdMillis
        } else {
            false
        }
    }

    private fun getSkipButtonTextStringId(segment: FindroidSegment): Int {
        segment.sponsorBlockCategory?.let { category ->
            return when (category) {
                "sponsor" -> R.string.player_controls_skip_sponsor
                "selfpromo" -> R.string.player_controls_skip_selfpromo
                "interaction" -> R.string.player_controls_skip_interaction
                "intro" -> R.string.player_controls_skip_intro
                "outro" -> R.string.player_controls_skip_outro
                "preview" -> R.string.player_controls_skip_preview
                "music_offtopic" -> R.string.player_controls_skip_music_offtopic
                "filler" -> R.string.player_controls_skip_filler
                else -> R.string.player_controls_skip_unknown
            }
        }
        return when (shouldSkipToNextEpisode(segment)) {
            true -> R.string.player_controls_next_episode
            false ->
                when (segment.type) {
                    FindroidSegmentType.INTRO -> R.string.player_controls_skip_intro
                    FindroidSegmentType.OUTRO -> R.string.player_controls_skip_outro
                    FindroidSegmentType.RECAP -> R.string.player_controls_skip_recap
                    FindroidSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                    FindroidSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                    else -> R.string.player_controls_skip_unknown
                }
        }
    }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    /** The chapter that is playing, for marking it in the chapter list. */
    fun currentChapterIndex(): Int? {
        val chapters = getChapters()
        if (chapters.isEmpty()) return null
        return chapters
            .indexOfLast { it.startPosition <= player.currentPosition }
            .takeIf { it >= 0 }
    }

    /** Jumps to a chapter picked from the chapter list. */
    fun seekToChapterIndex(chapterIndex: Int) {
        seekToChapter(chapterIndex)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    private fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            player.seekTo(chapter.startPosition)
        }
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
        isPlayingNow = isPlaying
        TaNowPlaying.setPlaying(isPlaying)
        if (isPlaying) resyncOnce()
        watchSync(isPlaying)
    }

    /**
     * Videos, streamed and downloaded alike, can start with sound and picture out of step, which a
     * seek puts right. With this turned on in settings, each video gets that seek once it has
     * played for a moment, to where it already is, whether it needs it or not. Waits until it's
     * actually playing, so a video that starts paused is still done. Off, as it is unless turned
     * on, a video is only resynced when the guard measures that it needs it.
     */
    private fun resyncOnce() {
        if (!resyncAfterStart) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId == resyncedMediaId || resyncJob?.isActive == true) return
        resync("a moment after starting") { resyncedMediaId = mediaId }
    }

    /**
     * Puts sound and picture back in step with a seek to where it already is, once it has played
     * for a moment: from now if it's playing, or from when it next is, such as after the stream has
     * loaded again. Also for when the picture comes back after playing in the background, and after
     * tunneling is turned on or off. [reason] says why, for the Playback info.
     */
    private fun resync(reason: String, onDone: () -> Unit = {}) {
        if (player !is ExoPlayer) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            var waitedMs = 0L
            while (!player.isPlaying && waitedMs < RESYNC_WAIT_MS) {
                delay(RESYNC_POLL_MS)
                waitedMs += RESYNC_POLL_MS
            }
            delay(RESYNC_DELAY_MS)
            if (player.isPlaying && player.currentMediaItem?.mediaId == mediaId) {
                onDone()
                resyncNow(reason)
            }
        }
    }

    /**
     * Puts sound and picture back in step right now, with a seek that starts both afresh from
     * what's already loaded. It goes a millisecond back from where it is: a seek to the very
     * millisecond the player is at is one the player takes as done already and drops, which is what
     * became of most of these before.
     */
    private fun resyncNow(reason: String) {
        syncGuard.resynced(reason)
        player.seekTo((player.currentPosition - RESYNC_BACK_MS).coerceAtLeast(0L))
    }

    /**
     * Has the guard look at sound and picture twice a second while it plays, and resyncs when it
     * finds they've come apart and stayed apart, which is rare. Not while the picture is off, as in
     * the background, where there's nothing to measure and the phone can sleep in between instead.
     */
    private fun watchSync(isPlaying: Boolean) {
        syncWatchJob?.cancel()
        syncWatchJob = null
        val pictureOff =
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        if (!isPlaying || !syncGuard.enabled || pictureOff) return
        syncWatchJob = viewModelScope.launch {
            while (true) {
                delay(SYNC_WATCH_MS)
                val reason = syncGuard.watch() ?: continue
                // Not on top of a resync that's already on its way
                if (resyncJob?.isActive != true) resyncNow(reason)
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        // The same subtitles the subtitle picker lists
        val subtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        _uiState.update {
            it.copy(
                hasSubtitles = subtitles.isNotEmpty(),
                subtitlesOn = subtitles.any { group -> group.isSelected },
            )
        }
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents

    /** The sleep timer ran out and paused playback. */
    data object SleepTimerPaused : PlayerEvents
}

// How long the player waits in the background before dropping the picture
private const val BACKGROUND_AUDIO_ONLY_DELAY_MS = 10_000L

// How long a video plays before sound and picture are put back in step, and how long that waits
// for it to be playing, looking this often
private const val RESYNC_DELAY_MS = 1_000L
private const val RESYNC_WAIT_MS = 15_000L
private const val RESYNC_POLL_MS = 250L

// How far back from where it is a resync seeks, so the player doesn't take it as already there
private const val RESYNC_BACK_MS = 1L

// How much of what was just played stays loaded at most: as far as the back button goes
private const val BACK_BUFFER_MAX_MS = 5_000L

// How long before the end the next video is shown
private const val UP_NEXT_MS = 10_000L
// The sleep timer turns the sound down over this long before pausing, a little at a time
private const val SLEEP_FADE_MS = 15_000L
private const val SLEEP_FADE_STEP_MS = 200L
// Pixels, about twice the thumbnail's size on screen
private const val UP_NEXT_IMAGE_WIDTH = 320

// What happens at a SponsorBlock segment
private const val SPONSORBLOCK_OFF = "off"
private const val SPONSORBLOCK_AUTO = "auto"

// How often the position is saved in TubeArchivist while playing
private const val TA_PROGRESS_INTERVAL_MS = 15_000L

// How much of a video or audio counts as watched, as Jellyfin counts it
private const val WATCHED_PART = 0.9

/** The media ID the audio of a JRWI post plays under, as it isn't a Jellyfin item. */
fun jrwiAudioMediaId(postId: Long): UUID =
    UUID.nameUUIDFromBytes("jrwi-audio-$postId".toByteArray())
