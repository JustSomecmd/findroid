package dev.jdtech.jellyfin.tubearchivist

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

/** A request to TubeArchivist that came back with an error status. */
class TaApiException(val code: Int, message: String) : IOException(message)

/**
 * Talks to a TubeArchivist server with the address and API token from the settings. Everything
 * apart from playing the videos themselves comes from here.
 */
class TaApi(private val appPreferences: AppPreferences) {
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
        get() =
            appPreferences.getValue(appPreferences.tubeArchivistUrl)?.trim()?.trimEnd('/')?.takeIf {
                it.isNotEmpty()
            }

    val token: String?
        get() =
            appPreferences.getValue(appPreferences.tubeArchivistToken)?.trim()?.takeIf {
                it.isNotEmpty()
            }

    val isConfigured: Boolean
        get() = baseUrl != null && token != null

    /** The value of the Authorization header TubeArchivist expects. */
    val authHeader: String?
        get() = token?.let { "Token $it" }

    /** Full address of an image or file path the API returns, such as /media/thumb/... */
    fun mediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl ?: return null
        return base + (if (path.startsWith("/")) path else "/$path")
    }

    // Connection

    /** Checks an address and token before they are saved. Returns the server's version. */
    suspend fun ping(url: String, token: String): String? {
        val base = url.trim().trimEnd('/')
        val httpUrl =
            "$base/api/ping/".toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid address")
        val request =
            Request.Builder().url(httpUrl).header("Authorization", "Token ${token.trim()}").build()
        val body = execute(request)
        return json.decodeFromString(TaPing.serializer(), body).version
    }

    // Videos

    suspend fun getVideos(
        page: Int = 1,
        channel: String? = null,
        playlist: String? = null,
        watch: String? = null,
        sort: String? = null,
        order: String? = null,
        type: String? = null,
    ): TaPage<TaVideo> {
        val body =
            get("api/video/") {
                addQueryParameter("page", page.toString())
                channel?.let { addQueryParameter("channel", it) }
                playlist?.let { addQueryParameter("playlist", it) }
                watch?.let { addQueryParameter("watch", it) }
                sort?.let { addQueryParameter("sort", it) }
                order?.let { addQueryParameter("order", it) }
                type?.let { addQueryParameter("type", it) }
            }
        // An empty lookup comes back as a plain empty list
        if (body.trimStart().startsWith("[")) return TaPage()
        return decode(body)
    }

    suspend fun getVideo(videoId: String): TaVideo = decode(get("api/video/$videoId/"))

    suspend fun getComments(videoId: String): List<TaComment> =
        json.decodeFromString(
            ListSerializer(TaComment.serializer()),
            get("api/video/$videoId/comment/"),
        )

    suspend fun getSimilar(videoId: String): List<TaVideo> =
        json.decodeFromString(
            ListSerializer(TaVideo.serializer()),
            get("api/video/$videoId/similar/"),
        )

    /**
     * Saves how far a video was watched, in seconds. TubeArchivist marks it watched near the end.
     */
    suspend fun postProgress(videoId: String, positionSeconds: Double) {
        post("api/video/$videoId/progress/", buildJsonObject { put("position", positionSeconds) })
    }

    suspend fun setWatched(id: String, watched: Boolean) {
        post(
            "api/watched/",
            buildJsonObject {
                put("id", id)
                put("is_watched", watched)
            },
        )
    }

    // Channels

    suspend fun getChannels(page: Int = 1, subscribedOnly: Boolean = false): TaPage<TaChannel> =
        decode(
            get("api/channel/") {
                addQueryParameter("page", page.toString())
                if (subscribedOnly) addQueryParameter("filter", "subscribed")
            }
        )

    suspend fun getChannel(channelId: String): TaChannel = decode(get("api/channel/$channelId/"))

    suspend fun getChannelNav(channelId: String): TaChannelNav =
        decode(get("api/channel/$channelId/nav/"))

    suspend fun setChannelSubscribed(channelId: String, subscribed: Boolean) {
        post("api/channel/$channelId/", buildJsonObject { put("channel_subscribed", subscribed) })
    }

    /**
     * Changes one of a channel's own settings, such as download_format. Null goes back to the
     * global setting.
     */
    suspend fun setChannelOverwrite(channelId: String, key: String, value: JsonElement) {
        post(
            "api/channel/$channelId/",
            buildJsonObject { put("channel_overwrites", buildJsonObject { put(key, value) }) },
        )
    }

    /**
     * Subscribes to channels by URL, handle or ID. TubeArchivist looks them up in the background.
     */
    suspend fun subscribeToChannels(urls: List<String>) {
        post(
            "api/channel/",
            buildJsonObject {
                put(
                    "data",
                    buildJsonArray {
                        urls.forEach { url ->
                            add(
                                buildJsonObject {
                                    put("channel_id", url)
                                    put("channel_subscribed", true)
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    /** Subscribes to playlists, given as URLs or IDs. TubeArchivist adds them in a task. */
    suspend fun subscribeToPlaylists(urls: List<String>) {
        post(
            "api/playlist/",
            buildJsonObject {
                put(
                    "data",
                    buildJsonArray {
                        urls.forEach { url ->
                            add(
                                buildJsonObject {
                                    put("playlist_id", url)
                                    put("playlist_subscribed", true)
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    // Playlists

    suspend fun setPlaylistSubscribed(playlistId: String, subscribed: Boolean) {
        post(
            "api/playlist/$playlistId/",
            buildJsonObject { put("playlist_subscribed", subscribed) },
        )
    }

    /** top or bottom */
    suspend fun setPlaylistSortOrder(playlistId: String, sortOrder: String) {
        post("api/playlist/$playlistId/", buildJsonObject { put("playlist_sort_order", sortOrder) })
    }

    /**
     * Playlists, of one [type] if given: regular (from YouTube) or custom (made in TubeArchivist).
     */
    suspend fun getPlaylists(
        page: Int = 1,
        channel: String? = null,
        type: String? = null,
    ): TaPage<TaPlaylist> =
        decode(
            get("api/playlist/") {
                addQueryParameter("page", page.toString())
                channel?.let { addQueryParameter("channel", it) }
                type?.let { addQueryParameter("type", it) }
            }
        )

    /** Makes a new playlist of TubeArchivist's own, to put videos in. */
    suspend fun createCustomPlaylist(name: String): TaPlaylist =
        decode(post("api/playlist/custom/", buildJsonObject { put("playlist_name", name.trim()) }))

    /** Adds a video to one of TubeArchivist's own playlists, at the end. */
    suspend fun addToCustomPlaylist(playlistId: String, videoId: String): TaPlaylist =
        updateCustomPlaylist(playlistId, "create", videoId)

    /** Takes a video out of one of TubeArchivist's own playlists. */
    suspend fun removeFromCustomPlaylist(playlistId: String, videoId: String): TaPlaylist =
        updateCustomPlaylist(playlistId, "remove", videoId)

    private suspend fun updateCustomPlaylist(
        playlistId: String,
        action: String,
        videoId: String,
    ): TaPlaylist =
        decode(
            post(
                "api/playlist/custom/$playlistId/",
                buildJsonObject {
                    put("action", action)
                    put("video_id", videoId)
                },
            )
        )

    suspend fun getPlaylist(playlistId: String): TaPlaylist =
        decode(get("api/playlist/$playlistId/"))

    // Search

    suspend fun search(query: String): TaSearchResults =
        decode<TaSearchResponse>(get("api/search/") { addQueryParameter("query", query) }).results

    // Download queue

    suspend fun getDownloads(page: Int = 1, filter: String = "pending"): TaPage<TaDownloadItem> =
        decode(
            get("api/download/") {
                addQueryParameter("page", page.toString())
                addQueryParameter("filter", filter)
            }
        )

    /**
     * Adds videos, playlists or channels by URL or ID to the download queue. [flat] adds them
     * without looking up all their details first, [force] downloads them again if they already are.
     */
    suspend fun addToQueue(
        urls: List<String>,
        autostart: Boolean,
        flat: Boolean = false,
        force: Boolean = false,
    ) {
        post(
            "api/download/",
            buildJsonObject {
                put(
                    "data",
                    buildJsonArray {
                        urls.forEach { url ->
                            add(
                                buildJsonObject {
                                    put("youtube_id", url)
                                    put("status", "pending")
                                }
                            )
                        }
                    },
                )
            },
        ) {
            if (autostart) addQueryParameter("autostart", "true")
            if (flat) addQueryParameter("flat", "true")
            if (force) addQueryParameter("force", "true")
        }
    }

    /** Removes a video and its file from the server. */
    suspend fun deleteVideo(videoId: String) {
        send("DELETE", url("api/video/$videoId/") {}, null)
    }

    /** Status is pending, ignore or priority (download it right away). */
    suspend fun setDownloadStatus(videoId: String, status: String) {
        post("api/download/$videoId/", buildJsonObject { put("status", status) })
    }

    suspend fun deleteDownload(videoId: String) {
        send("DELETE", url("api/download/$videoId/") {}, null)
    }

    /** Removes everything with this status (pending or ignore) from the queue. */
    suspend fun clearQueue(filter: String) {
        send("DELETE", url("api/download/") { addQueryParameter("filter", filter) }, null)
    }

    // Tasks

    /**
     * Asks TubeArchivist to look a video, channel or playlist up on YouTube again. [type] is video,
     * channel or playlist; [withVideos] also refreshes the videos of a channel or playlist.
     */
    suspend fun refresh(type: String, id: String, withVideos: Boolean = false) =
        refresh(type, listOf(id), withVideos)

    /** Asks TubeArchivist to look several videos, channels or playlists of one [type] up again. */
    suspend fun refresh(type: String, ids: List<String>, withVideos: Boolean = false) {
        post(
            "api/refresh/",
            buildJsonObject {
                put(type, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            },
        ) {
            if (withVideos) addQueryParameter("extract_videos", "true")
        }
    }

    /** Stops a running task, such as the downloads. */
    suspend fun stopTask(taskId: String) {
        post("api/task/by-id/$taskId/", buildJsonObject { put("command", "stop") })
    }

    /** Starts a task by name, such as update_subscribed or download_pending. */
    suspend fun startTask(name: String) {
        post("api/task/by-name/$name/", JsonObject(emptyMap()))
    }

    suspend fun getNotifications(): List<TaNotification> =
        json.decodeFromString(
            ListSerializer(TaNotification.serializer()),
            get("api/notification/"),
        )

    suspend fun getSchedules(): List<TaSchedule> =
        json.decodeFromString(ListSerializer(TaSchedule.serializer()), get("api/task/schedule/"))

    suspend fun setSchedule(taskName: String, schedule: String) {
        post("api/task/schedule/$taskName/", buildJsonObject { put("schedule", schedule) })
    }

    suspend fun deleteSchedule(taskName: String) {
        send("DELETE", url("api/task/schedule/$taskName/") {}, null)
    }

    // Settings

    suspend fun getConfig(): JsonObject =
        json.parseToJsonElement(get("api/appsettings/config/")) as JsonObject

    /** Changes one TubeArchivist setting, for example section downloads, key comment_max. */
    suspend fun setConfigValue(section: String, key: String, value: JsonElement): JsonObject {
        val body =
            post(
                "api/appsettings/config/",
                buildJsonObject { put(section, buildJsonObject { put(key, value) }) },
            )
        return json.parseToJsonElement(body) as JsonObject
    }

    // Requests

    private inline fun <reified T> decode(body: String): T =
        json.decodeFromString(serializer<T>(), body)

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl {
        val base = baseUrl ?: throw IOException("TubeArchivist is not set up")
        val builder =
            "$base/$path".toHttpUrlOrNull()?.newBuilder()
                ?: throw IOException("Invalid TubeArchivist address")
        builder.query()
        return builder.build()
    }

    private suspend fun get(path: String, query: HttpUrl.Builder.() -> Unit = {}): String =
        send("GET", url(path, query), null)

    private suspend fun post(
        path: String,
        body: JsonElement,
        query: HttpUrl.Builder.() -> Unit = {},
    ): String =
        send(
            "POST",
            url(path, query),
            body.toString().toRequestBody("application/json".toMediaType()),
        )

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
                    throw TaApiException(response.code, errorMessage(response.code, text))
                }
                text
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val detail =
            try {
                val obj = json.parseToJsonElement(body) as? JsonObject
                (obj?.get("error") ?: obj?.get("message") ?: obj?.get("detail"))?.let {
                    (it as? JsonPrimitive)?.content
                }
            } catch (_: Exception) {
                null
            }
        return when {
            detail != null -> detail
            code == 401 || code == 403 -> "TubeArchivist refused the API token"
            code == 404 -> "Not found on TubeArchivist"
            else -> "TubeArchivist answered with error $code"
        }
    }
}
