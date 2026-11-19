package dev.jdtech.jellyfin.tubearchivist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The numbers on TubeArchivist's dashboard, from /api/stats/. Sizes are in bytes and durations in
// seconds

/** How many videos, how much space they take and how long they play for. */
@Serializable
data class TaVideoTotals(
    @SerialName("doc_count") val count: Long = 0,
    @SerialName("media_size") val mediaSize: Long = 0,
    val duration: Long = 0,
)

/** All videos, by type, and whether they can still be found on YouTube. */
@Serializable
data class TaVideoStats(
    @SerialName("doc_count") val count: Long = 0,
    @SerialName("media_size") val mediaSize: Long = 0,
    val duration: Long = 0,
    @SerialName("type_videos") val regular: TaVideoTotals? = null,
    @SerialName("type_shorts") val shorts: TaVideoTotals? = null,
    @SerialName("type_streams") val streams: TaVideoTotals? = null,
    @SerialName("active_true") val active: TaVideoTotals? = null,
    @SerialName("active_false") val inactive: TaVideoTotals? = null,
)

/** Channels or playlists: subscribed to, still on YouTube, and in all. */
@Serializable
data class TaCountStats(
    @SerialName("doc_count") val total: Long = 0,
    @SerialName("active_true") val active: Long = 0,
    @SerialName("subscribed_true") val subscribed: Long = 0,
)

/** The download queue. */
@Serializable
data class TaDownloadStats(
    val pending: Long = 0,
    val ignore: Long = 0,
    @SerialName("pending_videos") val pendingVideos: Long = 0,
    @SerialName("pending_shorts") val pendingShorts: Long = 0,
    @SerialName("pending_streams") val pendingStreams: Long = 0,
)

/** Watched or unwatched videos, and how far through the archive that is. */
@Serializable
data class TaWatchPart(
    val items: Long = 0,
    val duration: Long = 0,
    /** From 0 to 1. */
    val progress: Double = 0.0,
)

@Serializable
data class TaWatchStats(
    val watched: TaWatchPart? = null,
    val unwatched: TaWatchPart? = null,
)

/** The videos downloaded on one day, in the server's time zone. */
@Serializable
data class TaDownloadDay(
    /** As 2026-11-19. */
    val date: String = "",
    val count: Long = 0,
    @SerialName("media_size") val mediaSize: Long = 0,
)

/** A channel among the biggest in the archive. */
@Serializable
data class TaBiggestChannel(
    val id: String = "",
    val name: String = "",
    @SerialName("doc_count") val count: Long = 0,
    val duration: Long = 0,
    @SerialName("media_size") val mediaSize: Long = 0,
)

/** What the biggest channels are ordered by, as TubeArchivist names it. */
enum class TaBiggestOrder(val key: String) {
    VIDEOS("doc_count"),
    DURATION("duration"),
    SIZE("media_size"),
}

@Serializable internal data class TaAggValue(val value: Double = 0.0)

/** A channel's videos on the server, as at the top of its page on the web. */
@Serializable
internal data class TaChannelAggsJson(
    @SerialName("total_items") val items: TaAggValue = TaAggValue(),
    @SerialName("total_size") val size: TaAggValue = TaAggValue(),
    @SerialName("total_duration") val duration: TaAggValue = TaAggValue(),
)

data class TaChannelTotals(val count: Long, val mediaSize: Long, val duration: Long)

@Serializable
internal data class TaUserConfig(@SerialName("file_size_unit") val fileSizeUnit: String = "binary")
