package dev.jdtech.tubedroid.desktop.jellyfin

import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionSync
import dev.jdtech.tubedroid.desktop.AppSettings
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder

/**
 * Jellyfin, through its own Kotlin library, as the phone app uses it. Signing in keeps the server,
 * the user and an access token in the settings; the password isn't kept.
 */
class JellyfinClient(private val settings: AppSettings) {
    private val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Winchivist", version = appVersion())
        deviceInfo = DeviceInfo(id = settings.deviceId, name = computerName())
    }

    private val api =
        jellyfin.createApi(baseUrl = settings.jellyfinUrl, accessToken = settings.jellyfinToken)

    val isSignedIn: Boolean
        get() =
            !settings.jellyfinUrl.isNullOrBlank() &&
                !settings.jellyfinToken.isNullOrBlank() &&
                !settings.jellyfinUserId.isNullOrBlank()

    val userName: String?
        get() = settings.jellyfinUserName

    val serverUrl: String?
        get() = settings.jellyfinUrl

    private val userId: UUID
        get() = UUID.fromString(settings.jellyfinUserId ?: error("Not signed in to Jellyfin"))

    /**
     * Where the playlists being watched are kept for the phone to see too: the signed-in user's
     * display settings. Null when not signed in.
     */
    val playlistSessionStore: TaPlaylistSessionStore?
        get() = if (isSignedIn) PlaylistSessionStore() else null

    private inner class PlaylistSessionStore : TaPlaylistSessionStore {
        override suspend fun read(): String? =
            withContext(Dispatchers.IO) { preferences().customPrefs[TaPlaylistSessionSync.KEY] }

        override suspend fun write(text: String) =
            withContext(Dispatchers.IO) {
                val current = preferences()
                api.displayPreferencesApi.updateDisplayPreferences(
                    displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                    userId = userId,
                    client = TaPlaylistSessionSync.CLIENT,
                    data =
                        current.copy(
                            customPrefs = current.customPrefs + (TaPlaylistSessionSync.KEY to text)
                        ),
                )
                Unit
            }

        private suspend fun preferences() =
            api.displayPreferencesApi
                .getDisplayPreferences(
                    displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                    userId = userId,
                    client = TaPlaylistSessionSync.CLIENT,
                )
                .content
    }

    /** Signs in, keeping the token for later. Throws when the server or the details are wrong. */
    suspend fun signIn(url: String, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val address = url.trim().trimEnd('/')
            api.update(baseUrl = address, accessToken = null)
            val result by
                api.authenticationApi.authenticateUserByName(
                    data = AuthenticateUserByName(username = username, pw = password)
                )
            val token = result.accessToken ?: error("Jellyfin didn't give a token")
            val user = result.user ?: error("Jellyfin didn't say who signed in")
            api.update(baseUrl = address, accessToken = token)
            settings.jellyfinUrl = address
            settings.jellyfinToken = token
            settings.jellyfinUserId = user.id.toString()
            settings.jellyfinUserName = user.name
        }

    fun signOut() {
        settings.jellyfinToken = null
        settings.jellyfinUserId = null
        settings.jellyfinUserName = null
        api.update(accessToken = null)
    }

    /** The user's libraries, such as Movies and Shows. */
    suspend fun libraries(): List<BaseItemDto> =
        withContext(Dispatchers.IO) { api.userViewApi.getUserViews(userId).content.items }

    /** Movies and episodes started but not finished. */
    suspend fun resume(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi
                .getResumeItems(
                    userId,
                    limit = 16,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                )
                .content
                .items
        }

    /** The next episode of each show being watched, or of just the one show. */
    suspend fun nextUp(seriesId: UUID? = null): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.showApi
                .getNextUp(userId, limit = 16, seriesId = seriesId, enableResumable = false)
                .content
                .items
        }

    /** What was added to a library last. */
    suspend fun latest(libraryId: UUID): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.libraryApi.getLatestMedia(userId, parentId = libraryId, limit = 16).content
        }

    /** One page of every movie or show, by name. Also gives how many there are in all. */
    suspend fun items(
        kind: BaseItemKind,
        startIndex: Int,
        limit: Int,
    ): Pair<List<BaseItemDto>, Int> =
        withContext(Dispatchers.IO) {
            val result =
                api.libraryApi
                    .getItems(
                        userId,
                        includeItemTypes = listOf(kind),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        startIndex = startIndex,
                        limit = limit,
                    )
                    .content
            result.items to result.totalRecordCount
        }

    suspend fun item(id: UUID): BaseItemDto =
        withContext(Dispatchers.IO) { api.libraryApi.getItem(itemId = id, userId = userId).content }

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        withContext(Dispatchers.IO) { api.showApi.getSeasons(seriesId, userId).content.items }

    suspend fun episodes(seriesId: UUID, seasonId: UUID? = null): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            api.showApi
                .getEpisodes(
                    seriesId,
                    userId,
                    seasonId = seasonId,
                    fields = listOf(ItemFields.OVERVIEW),
                )
                .content
                .items
        }

    suspend fun setPlayed(id: UUID, played: Boolean) {
        withContext(Dispatchers.IO) {
            if (played) api.userDataApi.markPlayedItem(id) else api.userDataApi.markUnplayedItem(id)
        }
    }

    suspend fun reportStart(id: UUID, seconds: Double) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = id,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(seconds),
                )
            )
        }
    }

    suspend fun reportProgress(id: UUID, seconds: Double, paused: Boolean) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = id,
                    canSeek = true,
                    isPaused = paused,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(seconds),
                )
            )
        }
    }

    suspend fun reportStop(id: UUID, seconds: Double) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(itemId = id, positionTicks = ticks(seconds), failed = false)
            )
        }
    }

    /** The file itself, as stored on the server, which mpv plays as it is. */
    fun streamUrl(item: BaseItemDto): String {
        val source = item.mediaSources?.firstOrNull()?.id ?: item.id.toString().replace("-", "")
        return "${settings.jellyfinUrl}/Videos/${item.id}/stream?static=true&mediaSourceId=$source"
    }

    /** The header that signs mpv's requests in, as Jellyfin's own apps send it. */
    val authHeader: String
        get() =
            "Authorization: MediaBrowser Client=\"Winchivist\", Device=\"${computerName()}\", " +
                "DeviceId=\"${settings.deviceId}\", Version=\"${appVersion()}\", " +
                "Token=\"${settings.jellyfinToken}\""

    /** A picture of an item, such as its poster, sized for the page. */
    fun imageUrl(itemId: UUID, type: ImageType, tag: String?, maxWidth: Int): String? {
        tag ?: return null
        return "${settings.jellyfinUrl}/Items/$itemId/Images/${type.serialName}" +
            "?tag=$tag&maxWidth=$maxWidth&quality=90"
    }

    companion object {
        /** Jellyfin counts time in ticks of 100 nanoseconds. */
        fun ticks(seconds: Double): Long = (seconds * 10_000_000).toLong()

        fun seconds(ticks: Long?): Double = (ticks ?: 0L) / 10_000_000.0
    }
}

private fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "dev"

private fun computerName(): String =
    System.getenv("COMPUTERNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "Windows"
