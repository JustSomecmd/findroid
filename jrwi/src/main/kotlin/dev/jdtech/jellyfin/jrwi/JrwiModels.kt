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
    /** The show's own picture; null when it has none (both are the same picture since v0.12). */
    val poster: String? = null,
    val banner: String? = null,
    /** The picture to show for the show: its own, or the JRWI logo (v0.12). */
    val image: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    val latest: String? = null,
    /** Only in a single show: its tabs with something in them, in the site's order. */
    val tabs: List<JrwiTab> = emptyList(),
    /** Only in a single show: the seasons of its episodes (v0.13). */
    val seasons: List<JrwiSeason> = emptyList(),
)

@Serializable
data class JrwiTab(
    val id: String,
    val name: String = "",
    val count: Int = 0,
    /** The seasons in the episodes and Just Rolled With It tabs (v0.14). */
    val seasons: List<JrwiSeason> = emptyList(),
)

/** A season of a show's tab, with its name when all its episodes share one. */
@Serializable data class JrwiSeason(val season: Int, val name: String? = null, val count: Int = 0)

/** A show and tab an item is listed in (v0.13). */
@Serializable data class JrwiPlace(val show: String, val tab: String)

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
    /** Every tab the item is in within its own show (v0.12); [tab] is the main one. */
    val tabs: List<String> = emptyList(),
    /** Every show and tab the item is in, its own show first (v0.13). */
    val places: List<JrwiPlace> = emptyList(),
    /** episode, video, audio, youtube, art or post */
    val kind: String = "post",
    /**
     * The season of a numbered episode in its own show's episodes tab, or since v0.14 of its Just
     * Rolled With It; null means the first.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val pinned: Boolean = false,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val video: JrwiVideo? = null,
    val audio: JrwiAudio? = null,
    val sync: JrwiSync? = null,
    /** Where the episode was left, in its main post's timeline; null when it was never played. */
    val progress: JrwiProgress? = null,
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
    /** Every tab the item is in within its own show, also from archivers older than v0.12. */
    val ownTabs: List<String>
        get() = tabs.ifEmpty { listOf(tab) }

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

    /** How far through the episode [progress] is, from 0 to 1; null unless partly played. */
    val playedFraction: Float?
        get() {
            val saved = progress?.takeIf { !it.finished && it.position > 0 } ?: return null
            // In the timeline of the version it was saved in
            val versionLength =
                if (saved.postId == audio?.postId) audio?.duration else video?.duration
            val length = saved.duration ?: versionLength ?: duration ?: return null
            if (length <= 0) return null
            return (saved.position / length).toFloat().coerceIn(0f, 1f)
        }
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
    /** The post it was made on, for an item's comments from more than one version. */
    @SerialName("post_id") val postId: Long? = null,
    val author: JrwiAuthor = JrwiAuthor(),
    val date: String? = null,
    @SerialName("date_text") val dateText: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("body_text") val bodyText: String = "",
    @SerialName("like_count") val likeCount: Int? = null,
    /** Pinned on the site, which lists it first. */
    val pinned: Boolean = false,
    /** The comment's own address on the site. */
    val link: String? = null,
    val replies: List<JrwiComment> = emptyList(),
)

/** Who wrote a comment: their name, picture and the badges next to the name. */
@Serializable
data class JrwiAuthor(
    val name: String = "",
    /** Null without a profile picture, where the site shows the name's first letter. */
    val avatar: String? = null,
    /** In the site's order: the membership tier, months as a member, and Verified. */
    val badges: List<JrwiBadge> = emptyList(),
    /**
     * The CSS colour the site shows the name in, which follows the tier, such as "rgb(1, 2, 3)".
     */
    val color: String? = null,
)

/**
 * A badge next to a commenter's name: the tier as a picture with its name, or months as a member as
 * an emoji and text. Any of the three can be missing.
 */
@Serializable
data class JrwiBadge(val name: String? = null, val icon: String? = null, val image: String? = null)

/**
 * How far an episode was played, in seconds in the timeline of the post [postId]: its video or its
 * audio. [finished] is shared by both.
 */
@Serializable
data class JrwiProgress(
    @SerialName("post_id") val postId: Long? = null,
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
