package dev.jdtech.jellyfin.jrwi

import java.io.IOException
import java.util.concurrent.TimeUnit
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
     * Full address of a path with the token in it, for players, downloads and other apps that can't
     * send the Authorization header.
     */
    fun fileUrlWithToken(path: String?): String? {
        val url = fileUrl(path)?.toHttpUrlOrNull() ?: return null
        val token = token ?: return url.toString()
        return url.newBuilder().setQueryParameter("token", token).build().toString()
    }

    // Connection

    /** Checks an address and token before they're saved. Returns the archiver's version. */
    suspend fun check(url: String, token: String): String? {
        val base = url.trim().trimEnd('/')
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
    ): JrwiPage<JrwiItem> =
        decode(
            get("api/items") {
                query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                show?.let { addQueryParameter("show", it) }
                tab?.let { addQueryParameter("tab", it) }
                kind?.let { addQueryParameter("kind", it) }
                youtubeId?.let { addQueryParameter("youtube_id", it) }
                addQueryParameter("page", page.toString())
                addQueryParameter("page_size", pageSize.toString())
            }
        )

    /** The newest items across every show. */
    suspend fun getLatest(limit: Int = 20): List<JrwiItem> =
        json.decodeFromString(
            ListSerializer(JrwiItem.serializer()),
            get("api/latest") { addQueryParameter("limit", limit.toString()) },
        )

    /** A whole item, by either post ID of its versions. */
    suspend fun getItem(postId: Long): JrwiItem = decode(get("api/items/$postId"))

    suspend fun getComments(postId: Long, page: Int = 1): JrwiPage<JrwiComment> =
        decode(get("api/items/$postId/comments") { addQueryParameter("page", page.toString()) })

    /** One version of an episode on its own: only its own text, likes, files and media. */
    suspend fun getPost(postId: Long): JrwiItem = decode(get("api/posts/$postId"))

    /** Only the comments made on this one post, not on the episode's other versions. */
    suspend fun getPostComments(postId: Long, page: Int = 1): JrwiPage<JrwiComment> =
        decode(get("api/posts/$postId/comments") { addQueryParameter("page", page.toString()) })

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
                    authHeader?.let { header("Authorization", it) }
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
        private val JSON_TYPE = "application/json".toMediaType()

        // Archived videos are named "<Show> - S01E136 - <Title> [jrwi-<post ID>].mp4"
        private val POST_ID_REGEX = Regex("""\[jrwi-(\d+)\]""")

        /** The post ID in an archived video's file name or path, if it has one. */
        fun postIdInPath(path: String?): Long? =
            path?.let { POST_ID_REGEX.find(it) }?.groupValues?.get(1)?.toLongOrNull()
    }
}
