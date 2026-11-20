package dev.jdtech.jellyfin.jrwi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The JRWI archiver's API objects, as described in the project doc "JRWI archiver + Tubedroid
// integration". Fields the archiver can leave out or set to null have defaults here.

@Serializable data class JrwiPing(val name: String? = null, val version: String? = null)

/** One page of a list. */
@Serializable
data class JrwiPage<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 50,
    val total: Int = 0,
) {
    val hasMore: Boolean
        get() = page * pageSize < total
}

/** A show from the site's Shows menu, or `other` for posts that belong to none. */
@Serializable
data class JrwiShow(
    val id: String,
    val name: String = "",
    val description: String = "",
    val poster: String? = null,
    val banner: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    val latest: String? = null,
    /** Only in a single show: its tabs with something in them, in the site's order. */
    val tabs: List<JrwiTab> = emptyList(),
)

@Serializable data class JrwiTab(val id: String, val name: String = "", val count: Int = 0)

/**
 * The video version of an item: an archived file that Jellyfin has, or a YouTube video that only
 * TubeArchivist can have.
 */
@Serializable
data class JrwiVideo(
    @SerialName("post_id") val postId: Long,
    @SerialName("jellyfin_path") val jellyfinPath: String? = null,
    val url: String? = null,
    val height: Int? = null,
    val size: Long? = null,
    val duration: Double? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
    @SerialName("youtube_url") val youtubeUrl: String? = null,
) {
    /** On YouTube only, so played through TubeArchivist. */
    val isYoutube: Boolean
        get() = youtubeId != null && jellyfinPath == null
}

@Serializable
data class JrwiAudio(
    @SerialName("post_id") val postId: Long,
    val url: String,
    val mime: String? = null,
    val size: Long? = null,
    val duration: Double? = null,
)

/**
 * How the video and audio of an item line up: `audio position = video position + offset`. [checked]
 * is false when the archiver couldn't tell, and assumed no gap.
 */
@Serializable data class JrwiSync(val offset: Double = 0.0, val checked: Boolean = false)

/** A post, with its other versions folded in. The fields after [archived] are only in one item. */
@Serializable
data class JrwiItem(
    val id: Long,
    val title: String = "",
    val date: String? = null,
    val show: String = "other",
    val tab: String = "posts",
    /** episode, video, audio, youtube, art or post */
    val kind: String = "post",
    val episode: Int? = null,
    val pinned: Boolean = false,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val video: JrwiVideo? = null,
    val audio: JrwiAudio? = null,
    val sync: JrwiSync? = null,
    @SerialName("comment_count") val commentCount: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    val locked: Boolean = false,
    val archived: Boolean = false,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("body_text") val bodyText: String? = null,
    @SerialName("original_url") val originalUrl: String? = null,
    val tags: List<String> = emptyList(),
    val access: String? = null,
    val images: List<JrwiImage> = emptyList(),
    val attachments: List<JrwiFile> = emptyList(),
    val links: List<JrwiLink> = emptyList(),
    val documents: List<JrwiFile> = emptyList(),
    val embeds: List<JrwiEmbed> = emptyList(),
    val versions: List<JrwiVersion> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    /** Seconds between the two versions, or none when the archiver doesn't say. */
    val offset: Double
        get() = sync?.offset ?: 0.0

    /** Both a video and an audio version, which share one position and watched state. */
    val hasBoth: Boolean
        get() = video != null && audio != null

    /** The moment in the audio that matches [videoSeconds] in the video. */
    fun audioSeconds(videoSeconds: Double): Double =
        (videoSeconds + offset).coerceIn(0.0, audio?.duration ?: Double.MAX_VALUE)

    /** The moment in the video that matches [audioSeconds] in the audio. */
    fun videoSeconds(audioSeconds: Double): Double =
        (audioSeconds - offset).coerceIn(0.0, video?.duration ?: Double.MAX_VALUE)
}

@Serializable data class JrwiImage(val url: String)

@Serializable data class JrwiFile(val name: String = "", val url: String, val size: Long? = null)

@Serializable data class JrwiLink(val text: String = "", val url: String)

@Serializable data class JrwiEmbed(val kind: String = "", val url: String)

@Serializable
data class JrwiVersion(
    @SerialName("post_id") val postId: Long,
    val title: String = "",
    val url: String? = null,
)

@Serializable
data class JrwiComment(
    val id: String,
    val author: JrwiAuthor = JrwiAuthor(),
    val date: String? = null,
    @SerialName("date_text") val dateText: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("body_text") val bodyText: String = "",
    @SerialName("like_count") val likeCount: Int? = null,
    val replies: List<JrwiComment> = emptyList(),
)

@Serializable data class JrwiAuthor(val name: String = "", val avatar: String? = null)

/** How far the audio of an item was listened to, in seconds. */
@Serializable
data class JrwiProgress(
    val position: Double = 0.0,
    val duration: Double? = null,
    val finished: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class JrwiStatus(
    val running: Boolean = false,
    val phase: String? = null,
    val detail: String? = null,
    val progress: JrwiCheckProgress? = null,
    @SerialName("last_check_started") val lastCheckStarted: String? = null,
    @SerialName("last_check_finished") val lastCheckFinished: String? = null,
    @SerialName("next_check") val nextCheck: String? = null,
    /** ok, login_failed, blocked, error or stopped */
    @SerialName("last_result") val lastResult: String? = null,
    val message: String? = null,
    @SerialName("login_ok") val loginOk: Boolean? = null,
    val counts: JrwiCounts = JrwiCounts(),
    val disk: JrwiDisk = JrwiDisk(),
)

@Serializable data class JrwiCheckProgress(val done: Int? = null, val total: Int? = null)

@Serializable
data class JrwiCounts(
    val items: Int = 0,
    val archived: Int = 0,
    val locked: Int = 0,
    val problems: Int = 0,
)

@Serializable
data class JrwiDisk(
    @SerialName("archive_bytes") val archiveBytes: Long? = null,
    @SerialName("free_bytes") val freeBytes: Long? = null,
)

/** Something that happened on the archiver: new_item, check_failed or login_failed. */
@Serializable
data class JrwiEvent(
    val type: String,
    val at: String,
    @SerialName("post_id") val postId: Long? = null,
    val title: String? = null,
    val message: String? = null,
)

/** A post that couldn't be fully saved, and why. */
@Serializable
data class JrwiProblem(
    @SerialName("post_id") val postId: Long,
    val title: String = "",
    val status: String? = null,
    val problems: List<String> = emptyList(),
)
