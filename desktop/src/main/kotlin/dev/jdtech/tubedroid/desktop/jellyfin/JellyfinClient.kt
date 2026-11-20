package dev.jdtech.tubedroid.desktop.jellyfin

import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionSync
import dev.jdtech.tubedroid.desktop.AppSettings
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
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
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile

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
                api.displayPreferenceApi.updateDisplayPreferences(
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
            api.displayPreferenceApi
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

    /**
     * Asks Jellyfin how to play an item, as its own apps do first: the file and its tracks, the
     * tracks the user's settings pick, and a play session to report on.
     */
    suspend fun playbackInfo(id: UUID, startSeconds: Double?): PlaybackInfoResponse =
        withContext(Dispatchers.IO) {
            api.mediaInfoApi
                .getPostedPlaybackInfo(
                    id,
                    PlaybackInfoDto(
                        userId = userId,
                        startTimeTicks = startSeconds?.let(::ticks),
                        maxStreamingBitrate = MAX_BITRATE,
                        deviceProfile = DIRECT_PLAY_PROFILE,
                        enableDirectPlay = true,
                        enableDirectStream = true,
                        enableTranscoding = false,
                        autoOpenLiveStream = true,
                    ),
                )
                .content
        }

    /** The parts of an item Jellyfin knows of, such as its intro and credits, to skip. */
    suspend fun segments(id: UUID): List<MediaSegmentDto> =
        withContext(Dispatchers.IO) { api.mediaSegmentApi.getItemSegments(id).content.items }

    suspend fun reportStart(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    audioStreamIndex = state.audioStreamIndex,
                    subtitleStreamIndex = state.subtitleStreamIndex,
                    canSeek = true,
                    isPaused = state.paused,
                    isMuted = state.muted,
                    volumeLevel = state.volume,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(state.seconds),
                )
            )
        }
    }

    suspend fun reportProgress(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    audioStreamIndex = state.audioStreamIndex,
                    subtitleStreamIndex = state.subtitleStreamIndex,
                    canSeek = true,
                    isPaused = state.paused,
                    isMuted = state.muted,
                    volumeLevel = state.volume,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = ticks(state.seconds),
                )
            )
        }
    }

    suspend fun reportStop(state: PlayState) {
        withContext(Dispatchers.IO) {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    positionTicks = ticks(state.seconds),
                    failed = false,
                )
            )
        }
    }

    /**
     * What plays and how, for Jellyfin's list of what's playing and for the tracks it picks next
     * time. A subtitle index of -1 means subtitles are off.
     */
    data class PlayState(
        val itemId: UUID,
        val seconds: Double,
        val paused: Boolean = false,
        val muted: Boolean = false,
        /** From 0 to 100. */
        val volume: Int = 100,
        val mediaSourceId: String? = null,
        val playSessionId: String? = null,
        val audioStreamIndex: Int? = null,
        val subtitleStreamIndex: Int? = null,
    )

    /** The file itself, as stored on the server, which mpv plays as it is. */
    fun streamUrl(itemId: UUID, mediaSourceId: String, playSessionId: String?): String =
        "${settings.jellyfinUrl}/Videos/$itemId/stream?static=true&mediaSourceId=$mediaSourceId" +
            "&deviceId=${settings.deviceId}" +
            (playSessionId?.let { "&playSessionId=$it" } ?: "")

    /** A file of the server's own, such as a subtitle file, from the path Jellyfin gave for it. */
    fun serverUrl(path: String): String = settings.jellyfinUrl.orEmpty() + path

    /**
     * One tile of an item's preview pictures, which Jellyfin makes for the seek bar. The token goes
     * in the address, as the pictures load without the sign-in header.
     */
    fun trickplayTileUrl(itemId: UUID, mediaSourceId: String, width: Int, index: Int): String =
        "${settings.jellyfinUrl}/Videos/$itemId/Trickplay/$width/$index.jpg" +
            "?mediaSourceId=$mediaSourceId&api_key=${settings.jellyfinToken}"

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
        // Higher than any file, so Jellyfin never asks for a smaller copy
        private const val MAX_BITRATE = 1_000_000_000

        // mpv plays the files as they are, so the profile only says how subtitle files come:
        // separate files as files, and the rest inside the video
        private val DIRECT_PLAY_PROFILE =
            DeviceProfile(
                name = "Winchivist",
                maxStaticBitrate = MAX_BITRATE,
                maxStreamingBitrate = MAX_BITRATE,
                codecProfiles = emptyList(),
                containerProfiles = emptyList(),
                directPlayProfiles = emptyList(),
                transcodingProfiles = emptyList(),
                subtitleProfiles =
                    listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "sub", "smi").map {
                        SubtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
                    } +
                        listOf(
                                "srt",
                                "subrip",
                                "ass",
                                "ssa",
                                "vtt",
                                "webvtt",
                                "sub",
                                "smi",
                                "pgssub",
                                "dvdsub",
                                "dvbsub",
                                "mov_text",
                            )
                            .map { SubtitleProfile(it, SubtitleDeliveryMethod.EMBED) },
            )

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
