package dev.jdtech.jellyfin.tubearchivist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What the TubeArchivist API sends back. Almost everything is optional, so a field TubeArchivist
// leaves out or adds in a later version doesn't break a whole page.

@Serializable
data class TaPage<T>(val data: List<T> = emptyList(), val paginate: TaPaginate? = null) {
    val hasMore: Boolean
        get() = paginate != null && paginate.currentPage < paginate.lastPage
}

@Serializable
data class TaPaginate(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
data class TaVideo(
    @SerialName("youtube_id") val youtubeId: String,
    val title: String = "",
    val description: String? = null,
    val published: String? = null,
    @SerialName("date_downloaded") val dateDownloaded: Long? = null,
    @SerialName("media_size") val mediaSize: Long? = null,
    @SerialName("vid_thumb_url") val thumbUrl: String? = null,
    @SerialName("vid_type") val type: String? = null,
    @SerialName("comment_count") val commentCount: Int? = null,
    val channel: TaChannel? = null,
    val player: TaPlayer? = null,
    val stats: TaStats? = null,
    val sponsorblock: TaSponsorBlock? = null,
    val tags: List<String> = emptyList(),
    val category: List<String> = emptyList(),
    /** Where the file is on the server, such as /media/UC.../abc.mp4. */
    @SerialName("media_url") val mediaUrl: String? = null,
    val streams: List<TaStream> = emptyList(),
    val subtitles: List<TaSubtitle> = emptyList(),
    @SerialName("vid_last_refresh") val lastRefresh: String? = null,
    /** False when the video is gone from YouTube. */
    val active: Boolean? = null,
    /** IDs of the playlists the video is in. */
    val playlist: List<String> = emptyList(),
)

/** A video or audio stream in the downloaded file. */
@Serializable
data class TaStream(
    val type: String = "",
    val codec: String = "",
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val index: Int? = null,
)

@Serializable
data class TaSubtitle(
    val lang: String = "",
    val name: String? = null,
    /** user for subtitles from the uploader, auto for automatic ones */
    val source: String? = null,
    val ext: String? = null,
)

@Serializable
data class TaPlayer(
    val watched: Boolean = false,
    val duration: Double = 0.0,
    @SerialName("duration_str") val durationStr: String? = null,
    val progress: Double? = null,
    val position: Double? = null,
)

@Serializable
data class TaStats(
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("like_count") val likeCount: Long? = null,
    @SerialName("dislike_count") val dislikeCount: Long? = null,
)

@Serializable
data class TaSponsorBlock(
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    val segments: List<TaSegment> = emptyList(),
)

@Serializable
data class TaSegment(
    val category: String = "",
    val segment: List<Double> = emptyList(),
    val actionType: String? = null,
)

@Serializable
data class TaChannel(
    @SerialName("channel_id") val id: String,
    @SerialName("channel_name") val name: String = "",
    @SerialName("channel_subs") val subs: Long? = null,
    @SerialName("channel_subscribed") val subscribed: Boolean = false,
    @SerialName("channel_description") val description: String? = null,
    @SerialName("channel_thumb_url") val thumbUrl: String? = null,
    @SerialName("channel_banner_url") val bannerUrl: String? = null,
    @SerialName("channel_tvart_url") val tvArtUrl: String? = null,
    @SerialName("channel_last_refresh") val lastRefresh: String? = null,
    /** False when the channel is gone from YouTube. */
    @SerialName("channel_active") val active: Boolean? = null,
    /**
     * Settings of this channel that replace the global ones, such as download_format. A missing key
     * means the global setting is used.
     */
    @SerialName("channel_overwrites") val overwrites: JsonObject? = null,
)

@Serializable
data class TaChannelNav(
    @SerialName("has_pending") val hasPending: Boolean = false,
    @SerialName("has_ignored") val hasIgnored: Boolean = false,
    @SerialName("has_playlists") val hasPlaylists: Boolean = false,
    @SerialName("has_videos") val hasVideos: Boolean = false,
    @SerialName("has_streams") val hasStreams: Boolean = false,
    @SerialName("has_shorts") val hasShorts: Boolean = false,
)

@Serializable
data class TaPlaylist(
    @SerialName("playlist_id") val id: String,
    @SerialName("playlist_name") val name: String = "",
    @SerialName("playlist_channel") val channelName: String? = null,
    @SerialName("playlist_channel_id") val channelId: String? = null,
    @SerialName("playlist_description") val description: String? = null,
    @SerialName("playlist_thumbnail") val thumbUrl: String? = null,
    @SerialName("playlist_subscribed") val subscribed: Boolean = false,
    @SerialName("playlist_type") val type: String? = null,
    @SerialName("playlist_entries") val entries: List<TaPlaylistEntry> = emptyList(),
    @SerialName("playlist_last_refresh") val lastRefresh: String? = null,
    @SerialName("playlist_active") val active: Boolean? = null,
    /** top or bottom: where new videos are added */
    @SerialName("playlist_sort_order") val sortOrder: String? = null,
)

@Serializable
data class TaPlaylistEntry(
    @SerialName("youtube_id") val youtubeId: String,
    val title: String = "",
    val downloaded: Boolean = false,
)

@Serializable
data class TaComment(
    @SerialName("comment_id") val id: String,
    @SerialName("comment_text") val text: String = "",
    @SerialName("comment_author") val author: String = "",
    @SerialName("comment_author_is_uploader") val authorIsUploader: Boolean = false,
    @SerialName("comment_author_thumbnail") val authorThumbnail: String? = null,
    @SerialName("comment_likecount") val likeCount: Long = 0,
    @SerialName("comment_time_text") val timeText: String? = null,
    @SerialName("comment_is_favorited") val isFavorited: Boolean = false,
    @SerialName("comment_replies") val replies: List<TaComment> = emptyList(),
)

@Serializable data class TaSearchResponse(val results: TaSearchResults = TaSearchResults())

@Serializable
data class TaSearchResults(
    @SerialName("video_results") val videos: List<TaVideo> = emptyList(),
    @SerialName("channel_results") val channels: List<TaChannel> = emptyList(),
    @SerialName("playlist_results") val playlists: List<TaPlaylist> = emptyList(),
)

@Serializable
data class TaDownloadItem(
    @SerialName("youtube_id") val youtubeId: String,
    val title: String = "",
    @SerialName("channel_name") val channelName: String = "",
    @SerialName("channel_id") val channelId: String? = null,
    val duration: String? = null,
    val published: String? = null,
    val status: String? = null,
    @SerialName("vid_thumb_url") val thumbUrl: String? = null,
    @SerialName("vid_type") val type: String? = null,
    val message: String? = null,
    @SerialName("auto_start") val autoStart: Boolean = false,
)

@Serializable
data class TaNotification(
    val id: String = "",
    val title: String = "",
    val group: String = "",
    val level: String = "info",
    val messages: List<String> = emptyList(),
    val progress: Double? = null,
)

/** One stored result of a TubeArchivist background task, kept by the server for a day. */
data class TaTaskResult(
    val status: String,
    val result: String?,
    val traceback: String?,
    val dateDone: String?,
    val name: String,
    val taskId: String,
)

// As the server sends it. Text for most versions, kept loose in case a server sends the raw error
// object or false for a task that hasn't finished
@Serializable
internal data class TaTaskResultJson(
    val status: String = "",
    val result: JsonElement? = null,
    val traceback: JsonElement? = null,
    @SerialName("date_done") val dateDone: JsonElement? = null,
    val name: String = "",
    @SerialName("task_id") val taskId: String = "",
) {
    fun toResult() =
        TaTaskResult(
            status = status,
            result = result.asText(),
            traceback = traceback.asText(),
            dateDone = dateDone.asText(),
            name = name,
            taskId = taskId,
        )

    private fun JsonElement?.asText(): String? =
        when (this) {
            null,
            JsonNull -> null
            is JsonPrimitive -> if (isString) content else toString()
            else -> toString()
        }
}

@Serializable data class TaPing(val response: String = "", val version: String? = null)

@Serializable
data class TaSchedule(
    val name: String = "",
    val schedule: String? = null,
    @SerialName("schedule_human") val scheduleHuman: String? = null,
)
