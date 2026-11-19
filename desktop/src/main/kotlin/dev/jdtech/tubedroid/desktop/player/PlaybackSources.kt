package dev.jdtech.tubedroid.desktop.player

import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.WatchHistory
import dev.jdtech.tubedroid.desktop.jellyfin.JellyfinClient
import dev.jdtech.tubedroid.desktop.screens.loadPlaylist
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

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
)

/** Where the player's videos come from, and where it reports back to: TubeArchivist or Jellyfin. */
internal interface PlaybackSource {
    suspend fun first(): PlayItem

    /** What plays after [current], if anything, by its title; the whole item loads when it's up. */
    suspend fun nextTitle(current: PlayItem): String?

    suspend fun next(current: PlayItem): PlayItem?

    suspend fun started(item: PlayItem, seconds: Double) {}

    suspend fun progress(item: PlayItem, seconds: Double, paused: Boolean)

    suspend fun stopped(item: PlayItem, seconds: Double) = progress(item, seconds, paused = true)

    /** Played to the end: marked as watched. */
    suspend fun ended(item: PlayItem)
}

internal class TaPlaybackSource(
    private val api: TaApi,
    private val settings: AppSettings,
    private val history: WatchHistory,
    private val request: PlayerRequest.Ta,
) : PlaybackSource {
    private var playlist: List<TaVideo> = emptyList()
    private var playlistName = ""

    override suspend fun first(): PlayItem {
        val video = api.getVideo(request.videoId)
        request.playlistId?.let { id ->
            runCatching { api.loadPlaylist(id) }
                .getOrNull()
                ?.let { (loaded, videos) ->
                    playlistName = loaded.name
                    playlist = videos
                }
        }
        return video.toPlayItem(fromStart = request.fromStart)
    }

    /** Notes the video as played, and the playlist as being at it, for Continue watching. */
    override suspend fun started(item: PlayItem, seconds: Double) {
        history.played(item.id)
        val playlistId = request.playlistId ?: return
        history.updatePlaylist(playlistId, playlistName, item.id)
    }

    private fun nextVideo(current: PlayItem): TaVideo? {
        val index = playlist.indexOfFirst { it.youtubeId == current.id }
        return if (index >= 0) playlist.getOrNull(index + 1) else null
    }

    override suspend fun nextTitle(current: PlayItem): String? = nextVideo(current)?.title

    override suspend fun next(current: PlayItem): PlayItem? {
        val next = nextVideo(current) ?: return null
        // The playlist's entries are short; the player needs the whole video
        return api.getVideo(next.youtubeId).toPlayItem(fromStart = false)
    }

    override suspend fun progress(item: PlayItem, seconds: Double, paused: Boolean) {
        api.postProgress(item.id, seconds)
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
            SkipSegment(start, end, segment.category)
        }
}

/**
 * Jellyfin's movies and episodes, played straight from the file on the server, as Jellyfin Media
 * Player does when it can. mpv picks the file's own audio and subtitle tracks.
 */
internal class JellyfinPlaybackSource(
    private val client: JellyfinClient,
    private val request: PlayerRequest.Jellyfin,
) : PlaybackSource {
    private val items = mutableMapOf<String, BaseItemDto>()

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

    override suspend fun started(item: PlayItem, seconds: Double) {
        client.reportStart(UUID.fromString(item.id), seconds)
    }

    override suspend fun progress(item: PlayItem, seconds: Double, paused: Boolean) {
        client.reportProgress(UUID.fromString(item.id), seconds, paused)
    }

    override suspend fun stopped(item: PlayItem, seconds: Double) {
        client.reportStop(UUID.fromString(item.id), seconds)
    }

    override suspend fun ended(item: PlayItem) {
        client.setPlayed(UUID.fromString(item.id), true)
    }

    private fun BaseItemDto.toPlayItem(fromStart: Boolean): PlayItem {
        items[id.toString()] = this
        val position = JellyfinClient.seconds(userData?.playbackPositionTicks)
        return PlayItem(
            id = id.toString(),
            title = if (type == BaseItemKind.EPISODE) episodeTitle(this) else name.orEmpty(),
            subtitle = seriesName ?: productionYear?.toString(),
            url = client.streamUrl(this),
            header = client.authHeader,
            resumeSeconds = position.takeIf { !fromStart && it > 5 },
        )
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
