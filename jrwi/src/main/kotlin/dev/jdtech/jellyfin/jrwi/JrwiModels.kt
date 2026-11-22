package dev.jdtech.jellyfin.jrwi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The JRWI archiver's API objects, as described in the project doc "JRWI archiver + Tubedroid
// integration". Fields the archiver can leave out or set to null have defaults here.

@Serializable
data class JrwiPing(
    val name: String? = null,
    val version: String? = null,
    /** The version of the contract the archiver follows (v0.17). */
    val contract: String? = null,
    /** Its optional parts (v0.17), as [JrwiFeature] names; null before 0.9.7. */
    val features: List<String>? = null,
)

/** The names of the archiver's optional parts in its ping (v0.17). */
object JrwiFeature {
    /** `GET /api/continue` */
    const val CONTINUE = "continue"
    /** `GET /api/feed` */
    const val FEED = "feed"
    /** `GET /api/tags`, and `tag` on posts and items */
    const val TAGS = "tags"
    /** `GET /api/stats` */
    const val STATS = "stats"
    /** `progress` on single posts */
    const val POST_PROGRESS = "post_progress"
    /** `POST /api/stop` */
    const val STOP = "stop"
    /** `POST /api/reparse`, asked of the archiver for the app's Downloads server side */
    const val REPARSE = "reparse"
    /** `GET /api/podcast` and `POST /api/podcast/read`, asked of the archiver likewise */
    const val PODCAST = "podcast"
}

/** A post in the feed (v0.17): the whole post, its first comments as the web feed picks them. */
data class JrwiFeedEntry(
    val post: JrwiItem,
    val topComments: List<JrwiComment> = emptyList(),
    /** It has more comments than [topComments]. */
    val moreComments: Boolean = false,
)

@Serializable
internal data class JrwiFeedExtras(
    @SerialName("top_comments") val topComments: List<JrwiComment> = emptyList(),
    @SerialName("more_comments") val moreComments: Boolean = false,
)

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
    /**
     * Set when the item's archived video was deleted because TubeArchivist has the same video from
     * YouTube (v0.15). The video is then the YouTube one, and its [postId] the YouTube post.
     */
    @SerialName("handed_over") val handedOver: JrwiHandOver? = null,
) {
    /** On YouTube only, so played through TubeArchivist. */
    val isYoutube: Boolean
        get() = youtubeId != null && jellyfinPath == null
}

/**
 * A video handed over to TubeArchivist (v0.15). On an item's video, [postId] is the members-only
 * post the deleted file belonged to; on that post, it's the post with the YouTube version.
 */
@Serializable
data class JrwiHandOver(
    val at: String? = null,
    @SerialName("post_id") val postId: Long? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
    @SerialName("youtube_url") val youtubeUrl: String? = null,
)

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
    /** On a single post: which version it is, video, audio or youtube, or null (v0.7). */
    val version: String? = null,
    /** On a single post: its episode has other versions (v0.7). */
    val paired: Boolean = false,
    /** On a single post: the item it's grouped into, the same as [id] for a main post (v0.7). */
    @SerialName("item_id") val itemId: Long? = null,
    /** On a single post: when the archiver noticed the site no longer has it (v0.15). */
    @SerialName("removed_from_site") val removedFromSite: String? = null,
    /** On a single post whose video was handed over to TubeArchivist (v0.15). */
    @SerialName("handed_over") val handedOver: JrwiHandOver? = null,
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
    val version: String? = null,
    /** When the archiver noticed the site no longer has this post (v0.15). */
    @SerialName("removed_from_site") val removedFromSite: String? = null,
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
    /**
     * The member's ID on the site (v0.18), the same on all their comments even after a change of
     * name; null on the user's own comments and before 0.9.8.
     */
    val id: String? = null,
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
    /**
     * What the check is doing, as a stable name (v0.16): starting, login, feed, pages, posts,
     * comments, tubearchivist, line_up, finishing, reparse or idle.
     */
    val step: String? = null,
    /** What the check is doing, and on which post or page, worded for people. */
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

/**
 * [done] of [total] in the current step; the one being worked on is number done + 1. [current] is
 * how far the file downloading now has got, from 0 to 1 (v0.6), and [unit] what's counted: post,
 * episode or page (v0.16).
 */
@Serializable
data class JrwiCheckProgress(
    val done: Int? = null,
    val total: Int? = null,
    val current: Double? = null,
    val unit: String? = null,
)

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

/**
 * The site's private podcast feed as the archiver reads it, as its admin page shows it: whether
 * it's set up, when it was last read and what it had, and the last check a new episode in it
 * started.
 */
@Serializable
data class JrwiPodcast(
    @SerialName("set_up") val setUp: Boolean = false,
    @SerialName("last_read") val lastRead: String? = null,
    val ok: Boolean = true,
    val error: String? = null,
    val title: String? = null,
    /** Episodes in the feed. */
    val count: Int = 0,
    /** Of those, the ones that are posts in the archive. */
    val matched: Int = 0,
    /** Of those, the ones that say the time of day they came out. */
    @SerialName("with_time") val withTime: Int = 0,
    /** How often it's read between checks, or null when reading it is off. */
    @SerialName("every_minutes") val everyMinutes: Int? = null,
    @SerialName("last_trigger") val lastTrigger: JrwiPodcastTrigger? = null,
    /** The newest episodes in it. */
    val latest: List<JrwiPodcastEpisode> = emptyList(),
)

/** The last check a new episode in the podcast feed started, and for which episodes. */
@Serializable data class JrwiPodcastTrigger(val at: String, val titles: List<String> = emptyList())

@Serializable
data class JrwiPodcastEpisode(
    val title: String,
    val published: String? = null,
    @SerialName("post_id") val postId: Long? = null,
    val duration: Double? = null,
)

/** A post that couldn't be fully saved, and why. */
@Serializable
data class JrwiProblem(
    @SerialName("post_id") val postId: Long,
    val title: String = "",
    val status: String? = null,
    val problems: List<String> = emptyList(),
)

/** The files of a post or of all an item's versions (v0.15). */
@Serializable data class JrwiPostFiles(val files: List<JrwiPostFile> = emptyList())

/**
 * One file a post carries, for the file inspector (v0.15). [type] says how to show it: `image`,
 * `audio`, `video`, `pdf`, `table`, `document`, `text`, `archive`, `youtube`, `link` or `other`.
 */
@Serializable
data class JrwiPostFile(
    val name: String = "",
    /** `video`, `audio`, `document`, `attachment`, `image`, `thumbnail` or `link`. */
    val role: String = "",
    val type: String = "other",
    val mime: String? = null,
    val size: Long? = null,
    /** Where the file is, or null for what isn't saved (`youtube`, `link`). */
    val url: String? = null,
    /** The file's path under /files/, or null for the video and for what isn't saved. */
    val path: String? = null,
    @SerialName("post_id") val postId: Long? = null,
    /** Where it was saved from, such as a Google Docs link, or a `link` entry's address. */
    val source: String? = null,
    val saved: Boolean = true,
    /** The Preview address, for `pdf`, `table`, `document`, `text` and `archive`. */
    val preview: String? = null,
    val duration: Double? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
)

/**
 * What's inside a file, from the archiver's preview (v0.15); which fields are set goes by [type].
 */
@Serializable
data class JrwiPreview(
    val name: String = "",
    val type: String = "other",
    val mime: String? = null,
    val size: Long? = null,
    val url: String? = null,
    /** For a PDF: how many pages, and the address of one as a picture, with {n} for its number. */
    val pages: Int? = null,
    @SerialName("page_url") val pageUrl: String? = null,
    val sheets: List<JrwiSheet> = emptyList(),
    val blocks: List<JrwiBlock> = emptyList(),
    val text: String? = null,
    /** For text: `plain`, `markdown`, `subtitles`, `json` or `html`, all shown as text. */
    val format: String? = null,
    val entries: List<JrwiArchiveEntry> = emptyList(),
    val truncated: Boolean = false,
    /** Why a table, document or zip can't be shown. */
    val note: String? = null,
)

@Serializable
data class JrwiSheet(
    val name: String = "",
    val rows: List<List<String>> = emptyList(),
    val truncated: Boolean = false,
)

/** A piece of a Word or PowerPoint file: `heading`, `paragraph`, `item` (of a list) or `row`. */
@Serializable
data class JrwiBlock(
    val kind: String = "paragraph",
    val level: Int? = null,
    val text: String? = null,
    val cells: List<String> = emptyList(),
)

@Serializable
data class JrwiArchiveEntry(
    val name: String = "",
    val size: Long? = null,
    val folder: Boolean = false,
)

/**
 * An episode to continue: the item, and its progress in the timeline of the version played last,
 * whose post ID says which version that was.
 */
@Serializable data class JrwiContinue(val item: JrwiItem, val progress: JrwiProgress)

@Serializable data class JrwiContinueList(val items: List<JrwiContinue> = emptyList())

/** One of the site's tags, with how many of the archive's posts carry it. Suggested. */
@Serializable data class JrwiTagCount(val name: String, val count: Int = 0)

/**
 * What the archive holds beyond its lists, for the archiver page's numbers. Suggested to the
 * archiver as `GET /api/stats`; every part can be missing.
 */
@Serializable
data class JrwiStats(
    /** The last checks, newest first. */
    val checks: List<JrwiCheckRun> = emptyList(),
    /** The archive's files by type and extension, as its Admin > Files page counts them. */
    val files: List<JrwiFileCount> = emptyList(),
    val comments: JrwiCommentStats? = null,
)

@Serializable
data class JrwiCheckRun(
    val started: String? = null,
    val finished: String? = null,
    /** ok, login_failed, blocked, error or stopped */
    val result: String? = null,
    val message: String? = null,
    /** Posts that were new to the archive. */
    @SerialName("new_items") val newItems: Int = 0,
    /** Posts saved, new or changed. */
    val archived: Int = 0,
)

@Serializable
data class JrwiFileCount(
    val type: String = "other",
    val ext: String = "",
    val count: Int = 0,
    val bytes: Long = 0,
)

@Serializable
data class JrwiCommentStats(
    /** Every comment and reply. */
    val total: Int = 0,
    val replies: Int = 0,
    /** How many members commented. */
    val commenters: Int = 0,
    /** Those who commented most, most first. */
    val top: List<JrwiCommenter> = emptyList(),
)

@Serializable
data class JrwiCommenter(
    /** The member's ID on the site (v0.18), or null. */
    val id: String? = null,
    val name: String = "",
    val avatar: String? = null,
    val color: String? = null,
    val badges: List<JrwiBadge> = emptyList(),
    val count: Int = 0,
    /** Likes on all their comments. */
    val likes: Int = 0,
)
