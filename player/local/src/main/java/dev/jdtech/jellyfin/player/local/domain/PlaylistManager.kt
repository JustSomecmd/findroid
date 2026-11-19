package dev.jdtech.jellyfin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class PlaylistManager
@Inject
internal constructor(
    private val repository: JellyfinRepository,
    appPreferences: AppPreferences,
    private val taApi: TaApi,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val taOfflineStore: TaOfflineStore,
) {
    private var startItem: FindroidItem? = null
    private var items: List<FindroidItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    var currentItemIndex: Int = 0

    // True when playing a Jellyfin playlist, which can mix movies and episodes
    private var isPlaylist = false

    // A TubeArchivist playlist being played: its downloaded videos in order, the one playing, and
    // the Jellyfin episodes found for them so far
    private var taQueue: List<TaQueueEntry>? = null
    private var taIndex = -1
    private val taEpisodeIds = mutableMapOf<String, UUID>()

    /**
     * Plays the next and previous videos from a TubeArchivist playlist instead of the show's other
     * episodes. Set before [getInitialItem]; null plays the show as usual.
     */
    fun setTubeArchivistQueue(entries: List<TaQueueEntry>?) {
        taQueue = entries?.takeIf { it.isNotEmpty() }
        taIndex = -1
        taEpisodeIds.clear()
    }

    /** The TubeArchivist video playing from the playlist, if one is. */
    val currentTaVideoId: String?
        get() = taQueue?.getOrNull(taIndex)?.videoId

    /** The playlist's video after the one playing, if there is one. */
    val nextTaVideoId: String?
        get() = taQueue?.getOrNull(taIndex + 1)?.videoId

    /** The title of the playlist's first video, which a looping playlist goes back to. */
    val firstTaTitle: String?
        get() = taQueue?.firstOrNull()?.title

    /**
     * Goes back to the playlist's first video that Jellyfin has, from its start, for a playlist
     * that loops. What was queued before is forgotten, as the player starts a new queue with it.
     */
    suspend fun restartTaQueue(): PlayerItem? = taQueue?.let {
        startTaQueue(it.indices, fromStart = true)
    }

    /**
     * Moves to one of the playlist's videos, picked from the playlist in the player, from where it
     * was left off. Null if it isn't in the playlist or Jellyfin doesn't have it.
     */
    suspend fun startTaQueueAt(videoId: String): PlayerItem? {
        val index = taQueue?.indexOfFirst { it.videoId == videoId } ?: return null
        if (index < 0) return null
        return startTaQueue(index..index, fromStart = false)
    }

    /** Starts a new queue at the first video in [indices] that Jellyfin has. */
    private suspend fun startTaQueue(indices: IntRange, fromStart: Boolean): PlayerItem? {
        val queue = taQueue ?: return null
        for (index in indices) {
            val id = resolve(queue[index]) ?: continue
            val episode = repository.getEpisode(id)
            val position = if (fromStart) 0L else episode.playbackPositionTicks.div(10000)
            val item = episode.toPlayerItem(null, position)
            playerItems.clear()
            playerItems.add(item)
            taIndex = index
            return item
        }
        return null
    }

    /** The Jellyfin episode of a playlist video, found by its title or else through its channel. */
    private suspend fun resolve(entry: TaQueueEntry): UUID? {
        taEpisodeIds[entry.videoId]?.let {
            return it
        }
        val id =
            try {
                // A video on the phone already knows its episode, also without a connection
                withContext(Dispatchers.IO) { taOfflineStore.savedItemId(entry.videoId) }
                    ?: taJellyfinLookup.findEpisodeId(entry.videoId, entry.title, null, null)
                    ?: taApi.getVideo(entry.videoId).let { video ->
                        taJellyfinLookup.findEpisodeId(
                            videoId = entry.videoId,
                            title = video.title,
                            channelId = video.channel?.id,
                            channelName = video.channel?.name,
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not find playlist video ${entry.videoId} in Jellyfin")
                null
            }
        if (id != null) taEpisodeIds[entry.videoId] = id
        return id
    }

    /**
     * The player item of the first playlist video in [indices] that Jellyfin has, or null if there
     * is none or it's already queued.
     */
    private suspend fun taPlayerItem(indices: IntProgression): PlayerItem? {
        val queue = taQueue ?: return null
        for (index in indices) {
            val entry = queue.getOrNull(index) ?: continue
            val id = resolve(entry) ?: continue
            if (playerItems.any { it.itemId == id }) return null
            return try {
                repository.getEpisode(id).toPlayerItem(null, 0L).also { playerItems.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to retrieve playlist video ${entry.videoId}")
                null
            }
        }
        return null
    }

    /** Quality used for streamed items. Changing it applies to items loaded from then on. */
    var quality: StreamQuality =
        StreamQuality.fromKey(appPreferences.getValue(appPreferences.playerStreamQuality))

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = itemId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    items = episodes
                    episode
                }
                BaseItemKind.PLAYLIST -> {
                    val playlistItems = repository.getPlaylistItems(itemId)

                    if (playlistItems.isEmpty()) {
                        return null
                    }

                    isPlaylist = true
                    items = playlistItems
                    playlistItems.first()
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        // Where the video is in the TubeArchivist playlist; one that isn't in it plays as usual
        taQueue?.let { queue ->
            val videoId =
                try {
                    taOfflineStore.videoIdForItem(initialItem.id)
                        ?: taJellyfinLookup.videoIdForItem(initialItem.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not find the TubeArchivist video of ${initialItem.id}")
                    null
                }
            taIndex = queue.indexOfFirst { it.videoId == videoId }
            if (taIndex < 0 || videoId == null) {
                taQueue = null
            } else {
                taEpisodeIds[videoId] = initialItem.id
            }
        }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks.div(10000) else 0
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, playbackPosition)
        playerItems.add(playerItem)

        return playerItem
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {
        Timber.d("Retrieving previous player item")
        if (taQueue != null) return taPlayerItem(taIndex - 1 downTo 0)

        val itemIndex = currentItemIndex - 1
        val playerItem =
            when {
                isPlaylist || startItem is FindroidEpisode -> {
                    if (currentItemIndex <= 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")
        taQueue?.let { queue ->
            return taPlayerItem(taIndex + 1..queue.lastIndex)
        }

        val itemIndex = currentItemIndex + 1
        val playerItem =
            when {
                isPlaylist || startItem is FindroidEpisode -> {
                    if (currentItemIndex < 0 || currentItemIndex >= items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemIndex = items.indexOfFirst { it.id == itemId }
        taQueue?.let { queue ->
            val index = queue.indexOfFirst { taEpisodeIds[it.videoId] == itemId }
            if (index >= 0) taIndex = index
        }
    }

    /** Loads a queued item again with the current [quality]. */
    suspend fun reloadPlayerItem(playerItem: PlayerItem): PlayerItem? {
        val item =
            items.firstOrNull { it.id == playerItem.itemId }
                ?: playerItem.takeIf { taQueue != null }?.let { repository.getEpisode(it.itemId) }
                ?: return null
        val reloaded = item.toPlayerItem(null, playerItem.playbackPosition)
        val index = playerItems.indexOfFirst { it.itemId == playerItem.itemId }
        if (index != -1) playerItems[index] = reloaded
        return reloaded
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        val mediaSources = repository.getMediaSources(id, true)
        val mediaSource =
            if (mediaSourceIndex == null) {
                mediaSources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: mediaSources[0]
            } else {
                mediaSources[mediaSourceIndex]
            }
        val isLocal = mediaSource.type == FindroidSourceType.LOCAL
        val transcodeStream =
            if (!isLocal && quality != StreamQuality.ORIGINAL) {
                try {
                    if (quality == StreamQuality.AUDIO_ONLY) {
                        repository.getAudioOnlyStream(
                            itemId = id,
                            mediaSourceId = mediaSource.id,
                            bitrate = quality.maxBitrate,
                        )
                    } else {
                        repository.getTranscodeStream(
                            itemId = id,
                            mediaSourceId = mediaSource.id,
                            maxWidth = quality.maxWidth,
                            maxHeight = quality.maxHeight,
                            maxBitrate = quality.maxBitrate,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Could not get a converted stream, playing the original")
                    null
                }
            } else {
                null
            }
        val transcodeSubtitles =
            transcodeStream?.subtitles?.mapNotNull { mediaStream ->
                val path = mediaStream.path ?: return@mapNotNull null
                ExternalSubtitle(
                    mediaStream.title,
                    mediaStream.language,
                    path.toUri(),
                    // The server may convert the subtitle, so the file type decides
                    when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass",
                        "ssa" -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    },
                )
            }
        val externalSubtitles =
            transcodeSubtitles
                ?: mediaSource.mediaStreams
                    .filter { mediaStream ->
                        mediaStream.isExternal &&
                            mediaStream.type == MediaStreamType.SUBTITLE &&
                            !mediaStream.path.isNullOrBlank()
                    }
                    .map { mediaStream ->
                        ExternalSubtitle(
                            mediaStream.title,
                            mediaStream.language,
                            mediaStream.path!!.toUri(),
                            when (mediaStream.codec) {
                                "subrip" -> MimeTypes.APPLICATION_SUBRIP
                                "webvtt" -> MimeTypes.APPLICATION_SUBRIP
                                "ass" -> MimeTypes.TEXT_SSA
                                else -> MimeTypes.TEXT_UNKNOWN
                            },
                        )
                    }
        val trickplayInfo =
            when (this) {
                is FindroidSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = transcodeStream?.url ?: mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is FindroidEpisode) parentIndexNumber else null,
            indexNumber = if (this is FindroidEpisode) indexNumber else null,
            indexNumberEnd = if (this is FindroidEpisode) indexNumberEnd else null,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            isLocal = isLocal,
            isTranscoded = transcodeStream != null,
            isAudioOnly = transcodeStream != null && quality == StreamQuality.AUDIO_ONLY,
        )
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }
}

/** A video of a TubeArchivist playlist, to be played after or before the others. */
data class TaQueueEntry(val videoId: String, val title: String)
