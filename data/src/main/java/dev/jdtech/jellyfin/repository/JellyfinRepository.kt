package dev.jdtech.jellyfin.repository

import androidx.paging.PagingData
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidPlaylist
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidServerSize
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidTranscodeStream
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

interface JellyfinRepository {
    suspend fun getPublicSystemInfo(): PublicSystemInfo

    suspend fun getUserViews(): List<BaseItemDto>

    suspend fun getEpisode(itemId: UUID): FindroidEpisode

    suspend fun getMovie(itemId: UUID): FindroidMovie

    suspend fun getShow(itemId: UUID): FindroidShow

    suspend fun getSeason(itemId: UUID): FindroidSeason

    suspend fun getLibraries(): List<FindroidCollection>

    suspend fun getItem(itemId: UUID): FindroidItem?

    suspend fun getItems(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        startIndex: Int? = null,
        limit: Int? = null,
    ): List<FindroidItem>

    suspend fun getItemsPaging(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
    ): Flow<PagingData<FindroidItem>>

    suspend fun getPerson(personId: UUID): FindroidPerson

    suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = true,
    ): List<FindroidItem>

    suspend fun getFavoriteItems(): List<FindroidItem>

    suspend fun getSearchItems(query: String): List<FindroidItem>

    /** Searches for items of the given types, best matches first. */
    suspend fun searchItems(
        query: String,
        types: List<BaseItemKind>,
        limit: Int? = null,
    ): List<FindroidItem>

    suspend fun getSuggestions(): List<FindroidItem>

    suspend fun getResumeItems(): List<FindroidItem>

    suspend fun getLatestMedia(parentId: UUID): List<FindroidItem>

    suspend fun getSeasons(seriesId: UUID, offline: Boolean = false): List<FindroidSeason>

    suspend fun getNextUp(seriesId: UUID? = null): List<FindroidEpisode>

    suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>? = null,
        startItemId: UUID? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<FindroidEpisode>

    /** Every episode of a show across all seasons, in no particular order. */
    suspend fun getAllEpisodes(seriesId: UUID): List<FindroidEpisode>

    suspend fun getMediaSources(itemId: UUID, includePath: Boolean = false): List<FindroidSource>

    suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String

    /**
     * Asks the server to convert a video to at most [maxWidth] x [maxHeight] and [maxBitrate] bits
     * per second. Returns null when the server can't convert it.
     */
    suspend fun getTranscodeStream(
        itemId: UUID,
        mediaSourceId: String,
        maxWidth: Int,
        maxHeight: Int,
        maxBitrate: Int,
    ): FindroidTranscodeStream?

    /**
     * Asks the server for only the sound of a video, converted to AAC at [bitrate] bits per second
     * and streamed over HLS. Returns null when it can't be streamed.
     */
    suspend fun getAudioOnlyStream(
        itemId: UUID,
        mediaSourceId: String,
        bitrate: Int,
    ): FindroidTranscodeStream?

    /**
     * How many episodes a show has on the server and how much space their files take there. Returns
     * null when the server can't be reached.
     */
    suspend fun getShowServerSize(seriesId: UUID): FindroidServerSize?

    suspend fun getSegments(itemId: UUID): List<FindroidSegment>

    suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray?

    suspend fun postCapabilities()

    suspend fun postPlaybackStart(itemId: UUID)

    suspend fun postPlaybackStop(itemId: UUID, positionTicks: Long, playedPercentage: Int)

    suspend fun postPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean)

    suspend fun markAsFavorite(itemId: UUID)

    suspend fun unmarkAsFavorite(itemId: UUID)

    suspend fun markAsPlayed(itemId: UUID)

    suspend fun markAsUnplayed(itemId: UUID)

    fun getBaseUrl(): String

    suspend fun updateDeviceName(name: String)

    suspend fun getUserConfiguration(): UserConfiguration?

    suspend fun getDownloads(): List<FindroidItem>

    /** Every downloaded episode on this device for the current server. */
    suspend fun getDownloadedEpisodes(): List<FindroidEpisode>

    /** The user's playlists that can contain videos. */
    suspend fun getPlaylists(): List<FindroidPlaylist>

    /** The movies and episodes in a playlist, in playlist order. */
    suspend fun getPlaylistItems(playlistId: UUID): List<FindroidItem>

    fun getUserId(): UUID
}
