package dev.jdtech.tubedroid.desktop.player

import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.loadPlaylist
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.WatchHistory
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinClient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType

/** What to play: a TubeArchivist video, or a movie or episode on Jellyfin. */
sealed interface PlayerRequest {
    /** Starts at the beginning even when it was watched part of the way. */
    val fromStart: Boolean

    /** A TubeArchivist video, and the playlist it's played from, if any. */
    data class Ta(
        val videoId: String,
        val playlistId: String? = null,
        override val fromStart: Boolean = false,
    ) : PlayerRequest

    /** A movie or episode on Jellyfin. Episodes go on to the next one. */
    data class Jellyfin(val itemId: String, override val fromStart: Boolean = false) : PlayerRequest
}

/** A subtitle file next to the video, which mpv adds as a track. */
internal data class ExternalSubtitle(val url: String, val title: String, val language: String?)

/** One thing to play, from either server, as the player needs it. */
internal data class PlayItem(
    val id: String,
    val title: String,
    /** The channel, or the show and episode. */
    val subtitle: String?,
    val url: String,
    /** The header that signs mpv's requests in. */
    val header: String?,
    val resumeSeconds: Double?,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    val segments: List<SkipSegment> = emptyList(),
    /** The audio track to start with, as mpv numbers them; null leaves it to mpv. */
    val audioTrack: Int? = null,
    /** The subtitle track to start with, as mpv numbers them; 0 is none, null leaves it to mpv. */
    val subtitleTrack: Int? = null,
    val trickplay: Trickplay? = null,
)

/**
 * Jellyfin's preview pictures for the seek bar: [count] pictures of [width] by [height], one every
 * [intervalMs], laid out [columns] by [rows] in each tile.
 */
internal data class Trickplay(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val intervalMs: Int,
    val tileUrl: (index: Int) -> String,
)

/** Where the player is and what it plays, as told to the server. */
internal data class PlaybackReport(
    val seconds: Double,
    val paused: Boolean,
    val muted: Boolean = false,
    /** From 0 to 100. */
    val volume: Int = 100,
    /** mpv's audio and subtitle tracks; null when there's none or it's off. */
    val audioId: Int? = null,
    val subtitleId: Int? = null,
)

/** Where the player's videos come from, and where it reports back to: TubeArchivist or Jellyfin. */
internal interface PlaybackSource {
    suspend fun first(): PlayItem

    /** What plays after [current], if anything, by its title; the whole item loads when it's up. */
    suspend fun nextTitle(current: PlayItem): String?

    suspend fun next(current: PlayItem): PlayItem?

    suspend fun started(item: PlayItem, report: PlaybackReport) {}

    suspend fun progress(item: PlayItem, report: PlaybackReport)

    suspend fun stopped(item: PlayItem, report: PlaybackReport) =
        progress(item, report.copy(paused = true))

    /** Played to the end: marked as watched. */
    suspend fun ended(item: PlayItem)

    /** Whether this plays a playlist, which can start over after its last video. */
    val isPlaylist: Boolean
        get() = false

    /** The playlist's name and its videos in order, for the playlist beside the video. */
    val playlistName: String
        get() = ""

    val playlistVideos: List<TaVideo>
        get() = emptyList()

    /** Loads the playlist again, with how far each video was watched. */
    suspend fun refreshPlaylist() {}

    /** One of the playlist's videos, picked from the playlist, or null if it isn't in it. */
    suspend fun playlistItem(videoId: String): PlayItem? = null

    /** Whether the playlist starts over after its last video. */
    var loop: Boolean
        get() = false
        set(_) {}
}

internal class TaPlaybackSource(
    private val api: TaApi,
    private val settings: AppSettings,
    private val history: WatchHistory,
    private val request: PlayerRequest.Ta,
) : PlaybackSource {
    private var playlist: List<TaVideo> = emptyList()
    private var name = ""

    override val playlistName: String
        get() = name

    override val isPlaylist: Boolean
        get() = playlist.isNotEmpty()

    override val playlistVideos: List<TaVideo>
        get() = playlist

    override suspend fun refreshPlaylist() {
        val playlistId = request.playlistId ?: return
        val (loaded, videos) = api.loadPlaylist(playlistId)
        name = loaded.name
        if (videos.isNotEmpty()) playlist = videos
    }

    override suspend fun playlistItem(videoId: String): PlayItem? {
        if (playlist.none { it.youtubeId == videoId }) return null
        return api.getVideo(videoId).toPlayItem(fromStart = false)
    }

    override var loop = false

    override suspend fun first(): PlayItem {
        val video = api.getVideo(request.videoId)
        request.playlistId?.let { id ->
            runCatching { api.loadPlaylist(id) }
                .getOrNull()
                ?.let { (loaded, videos) ->
                    name = loaded.name
                    playlist = videos
                }
        }
        return video.toPlayItem(fromStart = request.fromStart)
    }

    /** Notes the video as played, and the playlist as being at it, for Continue watching. */
    override suspend fun started(item: PlayItem, report: PlaybackReport) {
        history.played(item.id)
        val playlistId = request.playlistId ?: return
        history.updatePlaylist(playlistId, playlistName, item.id)
    }

    /** The playlist's video after [current], or its first after its last when it loops. */
    private fun nextVideo(current: PlayItem): TaVideo? {
        val index = playlist.indexOfFirst { it.youtubeId == current.id }
        if (index < 0) return null
        return playlist.getOrNull(index + 1) ?: playlist.firstOrNull()?.takeIf { loop }
    }

    override suspend fun nextTitle(current: PlayItem): String? = nextVideo(current)?.title

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextVideo(current) ?: return null
        // Starting over plays each video from its start again
        val startingOver = playlist.lastOrNull()?.youtubeId == current.id
        // The playlist's entries are short; the player needs the whole video
        return api.getVideo(next.youtubeId).toPlayItem(fromStart = startingOver)
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        api.postProgress(item.id, report.seconds)
    }

    override suspend fun ended(item: PlayItem) {
        api.setWatched(item.id, true)
        // The playlist carries on from the next video next time, and is done after its last
        val playlistId = request.playlistId ?: return
        if (playlist.isEmpty()) return
        val next = nextVideo(item)
        if (next != null) {
            history.updatePlaylist(playlistId, playlistName, next.youtubeId)
        } else {
            history.removePlaylist(playlistId)
        }
    }

    private fun TaVideo.toPlayItem(fromStart: Boolean): PlayItem {
        val url = api.mediaUrl(mediaUrl) ?: error("TubeArchivist has no file for this video")
        val resume =
            player?.position?.takeIf { position ->
                val length = player?.duration ?: 0.0
                !fromStart &&
                    player?.watched != true &&
                    position > 5 &&
                    (length <= 0 || position < length - 10)
            }
        return PlayItem(
            id = youtubeId,
            title = title,
            subtitle = channel?.name,
            url = url,
            header = api.authHeader?.let { "Authorization: $it" },
            resumeSeconds = resume,
            subtitles =
                subtitles.orEmpty().mapNotNull { subtitle ->
                    val subtitleUrl = api.mediaUrl(subtitle.mediaUrl) ?: return@mapNotNull null
                    ExternalSubtitle(
                        url = subtitleUrl,
                        title =
                            (subtitle.name ?: subtitle.lang) +
                                if (subtitle.source == "auto") " (automatic)" else "",
                        language = subtitle.lang,
                    )
                },
            segments =
                if (settings.sponsorBlock == AppSettings.SPONSORBLOCK_OFF) emptyList()
                else skipSegments(),
        )
    }

    private fun TaVideo.skipSegments(): List<SkipSegment> =
        sponsorblock?.segments.orEmpty().mapNotNull { segment ->
            val start = segment.segment.getOrNull(0) ?: return@mapNotNull null
            val end = segment.segment.getOrNull(1) ?: return@mapNotNull null
            // Segments that only mark a point, such as highlights, can't be skipped
            if (end - start < 1.0 || segment.actionType == "poi") return@mapNotNull null
            SkipSegment(
                start,
                end,
                segment.category,
                auto = settings.sponsorBlock == AppSettings.SPONSORBLOCK_AUTO,
            )
        }
}

/**
 * Jellyfin's movies and episodes, played straight from the file on the server, as Jellyfin's own
 * desktop app does: Jellyfin is asked how to play each one first, which gives the file, the tracks
 * the user's settings pick and a play session, and the tracks picked are told back to it.
 */
internal class JellyfinPlaybackSource(
    private val client: JellyfinClient,
    private val request: PlayerRequest.Jellyfin,
) : PlaybackSource {
    private val items = mutableMapOf<String, BaseItemDto>()

    /** How each item plays, from what Jellyfin said: its file, play session and tracks. */
    private class Playing(
        val mediaSourceId: String,
        val playSessionId: String?,
        /** Jellyfin's number for each audio track, in mpv's order. */
        val audioIndexes: List<Int>,
        /** Jellyfin's number for each subtitle track, in mpv's order: the file's, then the rest. */
        val subtitleIndexes: List<Int>,
    )

    private val playing = mutableMapOf<String, Playing>()

    override suspend fun first(): PlayItem {
        val item = client.item(UUID.fromString(request.itemId))
        return item.toPlayItem(fromStart = request.fromStart)
    }

    /** The episode after this one in the show, across seasons. */
    private suspend fun nextEpisode(current: PlayItem): BaseItemDto? {
        val item = items[current.id] ?: return null
        if (item.type != BaseItemKind.EPISODE) return null
        val seriesId = item.seriesId ?: return null
        val episodes = client.episodes(seriesId)
        val index = episodes.indexOfFirst { it.id == item.id }
        return if (index >= 0) episodes.getOrNull(index + 1) else null
    }

    override suspend fun nextTitle(current: PlayItem): String? =
        nextEpisode(current)?.let { episodeTitle(it) }

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextEpisode(current) ?: return null
        // The list of episodes leaves out the files; the whole item has them
        return client.item(next.id).toPlayItem(fromStart = true)
    }

    override suspend fun started(item: PlayItem, report: PlaybackReport) {
        client.reportStart(state(item, report))
    }

    override suspend fun progress(item: PlayItem, report: PlaybackReport) {
        client.reportProgress(state(item, report))
    }

    override suspend fun stopped(item: PlayItem, report: PlaybackReport) {
        client.reportStop(state(item, report))
    }

    override suspend fun ended(item: PlayItem) {
        client.setPlayed(UUID.fromString(item.id), true)
    }

    /** What's playing, with mpv's tracks put back into Jellyfin's numbers. */
    private fun state(item: PlayItem, report: PlaybackReport): JellyfinClient.PlayState {
        val info = playing[item.id]
        return JellyfinClient.PlayState(
            itemId = UUID.fromString(item.id),
            seconds = report.seconds,
            paused = report.paused,
            muted = report.muted,
            volume = report.volume,
            mediaSourceId = info?.mediaSourceId,
            playSessionId = info?.playSessionId,
            audioStreamIndex = report.audioId?.let { info?.audioIndexes?.getOrNull(it - 1) },
            subtitleStreamIndex =
                if (report.subtitleId == null) -1
                else info?.subtitleIndexes?.getOrNull(report.subtitleId - 1),
        )
    }

    private suspend fun BaseItemDto.toPlayItem(fromStart: Boolean): PlayItem {
        items[id.toString()] = this
        val position = JellyfinClient.seconds(userData?.playbackPositionTicks)
        val resume = position.takeIf { !fromStart && it > 5 }
        val info = client.playbackInfo(id, resume)
        val source = info.mediaSources.firstOrNull() ?: error("Jellyfin has no file for this")
        val sourceId = source.id ?: id.toString().replace("-", "")

        // mpv numbers each kind of track from 1 in the file's order, and the separate subtitle
        // files after the file's own, in the order they're added
        val streams = source.mediaStreams.orEmpty().sortedBy { it.index }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }
        val inFile = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
        val separate = streams.filter {
            it.type == MediaStreamType.SUBTITLE && it.isExternal && !it.deliveryUrl.isNullOrBlank()
        }
        val audioIndexes = audio.map { it.index }
        val subtitleIndexes = (inFile + separate).map { it.index }
        playing[id.toString()] =
            Playing(sourceId, info.playSessionId, audioIndexes, subtitleIndexes)

        return PlayItem(
            id = id.toString(),
            title = if (type == BaseItemKind.EPISODE) episodeTitle(this) else name.orEmpty(),
            subtitle = seriesName ?: productionYear?.toString(),
            url = client.streamUrl(id, sourceId, info.playSessionId),
            header = client.authHeader,
            resumeSeconds = resume,
            subtitles =
                separate.map { stream ->
                    ExternalSubtitle(
                        url = client.serverUrl(stream.deliveryUrl.orEmpty()),
                        title =
                            stream.displayTitle ?: stream.title ?: stream.language ?: "Subtitles",
                        language = stream.language,
                    )
                },
            segments = skipSegments(),
            // Jellyfin picks the tracks by the user's language and subtitle settings
            audioTrack =
                source.defaultAudioStreamIndex
                    ?.let { audioIndexes.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?.plus(1),
            subtitleTrack =
                when (val index = source.defaultSubtitleStreamIndex) {
                    null -> null
                    -1 -> 0
                    else -> subtitleIndexes.indexOf(index).takeIf { it >= 0 }?.plus(1)
                },
            trickplay = trickplay(sourceId),
        )
    }

    /** Intros, credits and the like that Jellyfin knows of, to skip with a button. */
    private suspend fun BaseItemDto.skipSegments(): List<SkipSegment> =
        try {
            client.segments(id).mapNotNull { segment ->
                val category =
                    when (segment.type) {
                        MediaSegmentType.INTRO -> "intro"
                        MediaSegmentType.OUTRO -> "outro"
                        MediaSegmentType.RECAP -> "recap"
                        MediaSegmentType.PREVIEW -> "preview"
                        MediaSegmentType.COMMERCIAL -> "commercial"
                        else -> return@mapNotNull null
                    }
                val start = JellyfinClient.seconds(segment.startTicks)
                val end = JellyfinClient.seconds(segment.endTicks)
                if (end - start < 1.0) null else SkipSegment(start, end, category)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Older servers have none
            emptyList()
        }

    /** The smallest of the preview picture sizes, which is plenty for the seek bar. */
    private fun BaseItemDto.trickplay(sourceId: String): Trickplay? {
        val sizes = trickplay?.get(sourceId) ?: trickplay?.values?.firstOrNull() ?: return null
        val info = sizes.values.minByOrNull { it.width } ?: return null
        if (info.thumbnailCount <= 0 || info.interval <= 0) return null
        val itemId = id
        return Trickplay(
            width = info.width,
            height = info.height,
            columns = info.tileWidth,
            rows = info.tileHeight,
            count = info.thumbnailCount,
            intervalMs = info.interval,
        ) { index ->
            client.trickplayTileUrl(itemId, sourceId, info.width, index)
        }
    }
}

/** Such as "S2:E5 · The Long Night". */
internal fun episodeTitle(item: BaseItemDto): String {
    val number =
        listOfNotNull(
                item.parentIndexNumber?.let { "S$it" },
                item.indexNumber?.let { "E$it" },
            )
            .joinToString(":")
    return listOf(number, item.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}
