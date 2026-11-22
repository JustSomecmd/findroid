package dev.jdtech.jellyfin.jrwi

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A request to the archiver that came back with an error status. */
class JrwiApiException(val code: Int, message: String) : IOException(message)

/**
 * Where the JRWI archiver is and the token to reach it with, read each time they're needed so a
 * change in settings applies right away.
 */
interface JrwiConfig {
    val url: String?
    val token: String?
}

/**
 * Talks to the JRWI archiver, which keeps a copy of the members' area of jrwishow.com: its shows,
 * posts, comments, art and audio. The videos play through Jellyfin.
 */
class JrwiApi(private val config: JrwiConfig) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    /** The server address without a trailing slash, or null when it isn't set up. */
    val baseUrl: String?
        get() = config.url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    val token: String?
        get() = config.token?.trim()?.takeIf { it.isNotEmpty() }

    val isConfigured: Boolean
        get() = baseUrl != null && token != null

    /** The value of the Authorization header the archiver expects. */
    val authHeader: String?
        get() = token?.let { "Token $it" }

    /** Full address of a path the API returns, such as /files/posts/300/thumbnail.webp. */
    fun fileUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl ?: return null
        return base + (if (path.startsWith("/")) path else "/$path")
    }

    /**
     * Full address of a path with the token in it, for the app's own players, which can't send the
     * Authorization header. The token only goes with an address on the archiver itself, never to
     * another server a post's content points to.
     */
    fun fileUrlWithToken(path: String?): String? {
        val url = fileUrl(path)?.toHttpUrlOrNull() ?: return null
        val token = token?.takeIf { isArchiverUrl(url) } ?: return url.toString()
        return url.newBuilder().setQueryParameter("token", token).build().toString()
    }

    /**
     * [url] with the token in it when it's an address on the archiver, for the app's own picture
     * loading; any other address as it is.
     */
    fun withToken(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val token = token?.takeIf { isArchiverUrl(parsed) } ?: return url
        return parsed.newBuilder().setQueryParameter("token", token).build().toString()
    }

    /** Whether [url] is on the archiver itself, so its token may go with it. */
    fun isArchiverUrl(url: HttpUrl): Boolean {
        val base = baseUrl?.toHttpUrlOrNull() ?: return false
        return url.scheme == base.scheme && url.host == base.host && url.port == base.port
    }

    // Connection

    /** Checks an address and token before they're saved. Returns the archiver's version. */
    suspend fun check(url: String, token: String): String? {
        val base = url.trim().trimEnd('/')
        // Read again on the next request, as the archiver may have been updated
        featureCache = null
        // The ping needs no token, so the status checks the token too
        val pingUrl =
            "$base/api/ping".toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid address")
        val ping = decode<JrwiPing>(execute(Request.Builder().url(pingUrl).build()))
        val statusUrl = "$base/api/status".toHttpUrlOrNull()!!
        execute(
            Request.Builder()
                .url(statusUrl)
                .header("Authorization", "Token ${token.trim()}")
                .build()
        )
        return ping.version
    }

    // Shows and items

    suspend fun getShows(): List<JrwiShow> =
        json.decodeFromString(ListSerializer(JrwiShow.serializer()), get("api/shows"))

    /** One show, with its tabs. */
    suspend fun getShow(showId: String): JrwiShow = decode(get("api/shows/$showId"))

    /** One tab of a show, or one season of it with [season] (archiver 0.7.0 and newer). */
    suspend fun getShowItems(
        showId: String,
        tab: String,
        season: Int? = null,
        page: Int = 1,
        order: String = "desc",
        pageSize: Int = PAGE_SIZE,
    ): JrwiPage<JrwiItem> =
        decode(
            get("api/shows/$showId/items") {
                addQueryParameter("tab", tab)
                if (season != null) addQueryParameter("season", season.toString())
                addQueryParameter("order", order)
                addQueryParameter("page", page.toString())
                addQueryParameter("page_size", pageSize.toString())
            }
        )

    /**
     * Searches everything, or one show, tab or kind (video, audio or post). [youtubeId] gives the
     * items that embed that YouTube video, on archiver 0.4.1 or newer; older ones ignore it.
     */
    suspend fun search(
        query: String? = null,
        show: String? = null,
        tab: String? = null,
        kind: String? = null,
        youtubeId: String? = null,
        page: Int = 1,
        pageSize: Int = PAGE_SIZE,
        order: String = "desc",
    ): JrwiPage<JrwiItem> =
        decode(
            get("api/items") {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                show?.let { addQueryParameter("show", it) }
                tab?.let { addQueryParameter("tab", it) }
                kind?.let { addQueryParameter("kind", it) }
                youtubeId?.let { addQueryParameter("youtube_id", it) }
                addQueryParameter("order", order)
                addQueryParameter("page", page.toString())
                addQueryParameter("page_size", pageSize.toString())
            }
        )

    /**
     * Every post on its own, as the archiver's web feed lists them (v0.7): newest first, or oldest
     * with [order] "asc", leaving out those above the user's tier. [query] finds posts with it in
     * their title or text, and [show] and [tab] keep the posts the site's tags put there. [tag]
     * keeps the posts with that tag, on archivers that list their tags ([getTags]); others ignore
     * it, so it's only sent to those.
     */
    suspend fun getPosts(
        page: Int = 1,
        pageSize: Int = PAGE_SIZE,
        order: String = "desc",
        query: String? = null,
        show: String? = null,
        tab: String? = null,
        tag: String? = null,
    ): JrwiPage<JrwiItem> =
        decode(
            get("api/posts") {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                show?.let { addQueryParameter("show", it) }
                tab?.let { addQueryParameter("tab", it) }
                tag?.let { addQueryParameter("tag", it) }
                addQueryParameter("order", order)
                addQueryParameter("page", page.toString())
                addQueryParameter("page_size", pageSize.toString())
            }
        )

    /**
     * The site's tags that archived posts carry, with how many posts each, as the web feed's tag
     * picker lists them. Suggested to the archiver; archivers without it answer 404.
     */
    suspend fun getTags(): List<JrwiTagCount> =
        json.decodeFromString(ListSerializer(JrwiTagCount.serializer()), get("api/tags"))

    /**
     * What the archive holds beyond its lists: its last checks, its files by type, and who comments
     * most. Suggested to the archiver; archivers without it answer 404.
     */
    suspend fun getStats(): JrwiStats = decode(get("api/stats"))

    /** The archiver's name and version, and since v0.17 its contract and features. */
    suspend fun getPing(): JrwiPing {
        val ping = decode<JrwiPing>(get("api/ping"))
        // Kept for features(), so a page that reads the version doesn't ask twice
        baseUrl?.let {
            featureCache = Triple(it, ping.features?.toSet(), System.currentTimeMillis())
        }
        return ping
    }

    // What the archiver has: its address, its features and when they were read
    @Volatile private var featureCache: Triple<String, Set<String>?, Long>? = null

    /**
     * The optional parts the archiver has (v0.17), from its ping, as [JrwiFeature] names: read
     * once, and again after a while or for another address. Null when it doesn't say, as before
     * 0.9.7, or couldn't be reached; then callers ask anyway and take a 404 to mean no.
     */
    suspend fun features(): Set<String>? {
        val base = baseUrl ?: return null
        val now = System.currentTimeMillis()
        featureCache?.let { (url, features, at) ->
            if (url == base && now - at < FEATURES_KEPT_MS) return features
        }
        return try {
            getPing().features?.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not kept, so it's asked again next time
            null
        }
    }

    /**
     * Whether the archiver has [feature]: true or false when it lists its features, null when it
     * doesn't say.
     */
    suspend fun has(feature: String): Boolean? = features()?.contains(feature)

    /**
     * A page of the archive's feed as the web app's Home shows it (v0.17): every post whole, with
     * its progress, its first two comments and whether it has more. [query] and [tag] as on
     * [getPosts]. Up to 50 a page.
     */
    suspend fun getFeed(
        page: Int = 1,
        pageSize: Int = 20,
        order: String = "desc",
        query: String? = null,
        tag: String? = null,
    ): JrwiPage<JrwiFeedEntry> {
        val raw =
            decode<JrwiPage<JsonObject>>(
                get("api/feed") {
                    query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                    tag?.let { addQueryParameter("tag", it) }
                    addQueryParameter("order", order)
                    addQueryParameter("page", page.toString())
                    addQueryParameter("page_size", pageSize.toString())
                }
            )
        return JrwiPage(
            items =
                raw.items.map { entry ->
                    val extras = json.decodeFromJsonElement(JrwiFeedExtras.serializer(), entry)
                    JrwiFeedEntry(
                        post = json.decodeFromJsonElement(JrwiItem.serializer(), entry),
                        topComments = extras.topComments,
                        moreComments = extras.moreComments,
                    )
                },
            page = raw.page,
            pageSize = raw.pageSize,
            total = raw.total,
        )
    }

    /** The newest items across every show. */
    suspend fun getLatest(limit: Int = 20): List<JrwiItem> =
        json.decodeFromString(
            ListSerializer(JrwiItem.serializer()),
            get("api/latest") { addQueryParameter("limit", limit.toString()) },
        )

    /**
     * Episodes started and not finished, the one played most recently first, each with its progress
     * in the timeline of the version played last: the web app's Continue watching row. Suggested to
     * the archiver; archivers without it answer 404.
     */
    suspend fun getContinue(limit: Int = 12): List<JrwiContinue> =
        decode<JrwiContinueList>(
                get("api/continue") { addQueryParameter("limit", limit.toString()) }
            )
            .items

    /** A whole item, by either post ID of its versions. */
    suspend fun getItem(postId: Long): JrwiItem = decode(get("api/items/$postId"))

    suspend fun getComments(postId: Long, page: Int = 1): JrwiPage<JrwiComment> =
        decode(get("api/items/$postId/comments") { addQueryParameter("page", page.toString()) })

    /** One version of an episode on its own: only its own text, likes, files and media. */
    suspend fun getPost(postId: Long): JrwiItem = decode(get("api/posts/$postId"))

    /** Only the comments made on this one post, not on the episode's other versions. */
    suspend fun getPostComments(postId: Long, page: Int = 1): JrwiPage<JrwiComment> =
        decode(get("api/posts/$postId/comments") { addQueryParameter("page", page.toString()) })

    /** Every file of all an item's versions, for the file inspector (archiver 0.9.0 and newer). */
    suspend fun getItemFiles(postId: Long): List<JrwiPostFile> =
        decode<JrwiPostFiles>(get("api/items/$postId/files")).files

    /** Only this post's files (archiver 0.9.0 and newer). */
    suspend fun getPostFiles(postId: Long): List<JrwiPostFile> =
        decode<JrwiPostFiles>(get("api/posts/$postId/files")).files

    /** What's inside a file, from its `preview` address (archiver 0.9.0 and newer). */
    suspend fun getPreview(address: String): JrwiPreview {
        val url =
            fileUrl(address)?.toHttpUrlOrNull()
                ?: throw IOException("The JRWI archiver is not set up")
        return decode(send("GET", url, null))
    }

    // Watching and listening progress, one record per episode for its video and audio

    /**
     * How far the episode was played, in the timeline of the video or audio post with this ID.
     * Needs archiver 0.4.0 or newer for video posts.
     */
    suspend fun getProgress(postId: Long): JrwiProgress = decode(get("api/progress/$postId"))

    /**
     * Saves how far the episode was played, in seconds in the timeline of the video or audio post
     * with this ID. The archiver moves it to the other version's timeline itself. [updatedAt] is
     * when progress sent later was made, such as without a connection: the archiver then keeps
     * whichever of its own and this is newer (archiver 0.5.0 or newer).
     */
    suspend fun setProgress(
        postId: Long,
        position: Double,
        duration: Double?,
        finished: Boolean,
        updatedAt: String? = null,
    ): JrwiProgress =
        decode(
            send(
                "PUT",
                url("api/progress/$postId") {},
                buildJsonObject {
                        put("position", position)
                        duration?.let { put("duration", it) }
                        put("finished", finished)
                        updatedAt?.let { put("updated_at", it) }
                    }
                    .toString()
                    .toRequestBody(JSON_TYPE),
            )
        )

    /**
     * Opens an archived file, such as an episode's audio, to download it: from byte [from] on when
     * that's more than 0, to go on with a download where it stopped. Blocks; the caller closes the
     * response.
     */
    fun openFile(path: String, from: Long = 0): Response {
        val url =
            fileUrl(path)?.toHttpUrlOrNull() ?: throw IOException("The JRWI archiver is not set up")
        val request =
            Request.Builder()
                .url(url)
                .apply {
                    // The token only to the archiver, not to another server a post points to
                    if (isArchiverUrl(url)) authHeader?.let { header("Authorization", it) }
                    if (from > 0) header("Range", "bytes=$from-")
                }
                .build()
        return client.newCall(request).execute()
    }

    // The archiver itself

    suspend fun getStatus(): JrwiStatus = decode(get("api/status"))

    /** Starts a check for new posts, of the whole feed with [full]. False when one is running. */
    suspend fun startCheck(full: Boolean = false): Boolean =
        try {
            send(
                "POST",
                url("api/check") { if (full) addQueryParameter("full", "true") },
                "".toRequestBody(JSON_TYPE),
            )
            true
        } catch (e: JrwiApiException) {
            if (e.code == 409) false else throw e
        }

    /**
     * Stops the check, or the re-read of saved pages, that's running (v0.17), as the admin page's
     * Stop does. False when nothing is running.
     */
    suspend fun stopCheck(): Boolean =
        try {
            send("POST", url("api/stop") {}, "".toRequestBody(JSON_TYPE))
            true
        } catch (e: JrwiApiException) {
            if (e.code == 409) false else throw e
        }

    /**
     * Re-reads the saved pages with the archiver's current rules, as its admin page's Re-read saved
     * pages does (feature `reparse`): false when a check or a re-read is running already.
     */
    suspend fun startReparse(): Boolean =
        try {
            send("POST", url("api/reparse") {}, "".toRequestBody(JSON_TYPE))
            true
        } catch (e: JrwiApiException) {
            if (e.code == 409) false else throw e
        }

    /** The site's private podcast feed, as the archiver last read it (feature `podcast`). */
    suspend fun getPodcast(): JrwiPodcast = decode(get("api/podcast"))

    /** Has the archiver read the podcast feed now, as its admin page's Read it now does. */
    suspend fun readPodcast() {
        send("POST", url("api/podcast/read") {}, "".toRequestBody(JSON_TYPE))
    }

    suspend fun getEvents(since: String? = null): List<JrwiEvent> =
        json.decodeFromString(
            ListSerializer(JrwiEvent.serializer()),
            get("api/events") { since?.let { addQueryParameter("since", it) } },
        )

    suspend fun getProblems(): List<JrwiProblem> =
        json.decodeFromString(ListSerializer(JrwiProblem.serializer()), get("api/problems"))

    private inline fun <reified T> decode(body: String): T =
        json.decodeFromString(serializer<T>(), body)

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl {
        val base = baseUrl ?: throw IOException("The JRWI archiver is not set up")
        val builder =
            "$base/$path".toHttpUrlOrNull()?.newBuilder()
                ?: throw IOException("Invalid JRWI archiver address")
        builder.query()
        return builder.build()
    }

    private suspend fun get(path: String, query: HttpUrl.Builder.() -> Unit = {}): String =
        send("GET", url(path, query), null)

    private suspend fun send(method: String, url: HttpUrl, body: RequestBody?): String {
        val request =
            Request.Builder()
                .url(url)
                .method(method, body)
                .apply { authHeader?.let { header("Authorization", it) } }
                .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw JrwiApiException(response.code, errorMessage(response.code, text))
                }
                text
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val detail =
            try {
                ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)
                    ?.content
            } catch (_: Exception) {
                null
            }
        return when {
            detail != null -> detail
            code == 401 || code == 403 -> "The archiver didn't accept the API token"
            code == 404 -> "The archiver doesn't have this"
            else -> "The archiver answered $code"
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        // How long the archiver's features are kept before its ping is read again
        private const val FEATURES_KEPT_MS = 15 * 60_000L
        private val JSON_TYPE = "application/json".toMediaType()

        // Archived videos are named "<Show> - S01E136 - <Title> [jrwi-<post ID>].mp4"
        private val POST_ID_REGEX = Regex("""\[jrwi-(\d+)\]""")

        /** The post ID in an archived video's file name or path, if it has one. */
        fun postIdInPath(path: String?): Long? =
            path?.let { POST_ID_REGEX.find(it) }?.groupValues?.get(1)?.toLongOrNull()
    }
}
