package dev.jdtech.jellyfin.tubearchivist

import dev.jdtech.jellyfin.api.JellyfinApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields

/** Name the TubeArchivist Jellyfin plugin stores its IDs under. */
private const val PROVIDER_NAME = "TubeArchivist"

// TubeArchivist keeps each video as <channel ID>/<video ID>.mp4
private val VIDEO_FILE_REGEX =
    Regex("""(?:^|[/\\])UC[A-Za-z0-9_-]{22}[/\\]([A-Za-z0-9_-]{11})\.[A-Za-z0-9]+$""")
private val CHANNEL_FOLDER_REGEX = Regex("""(?:^|[/\\])(UC[A-Za-z0-9_-]{22})[/\\]?$""")

private val LOOKUP_FIELDS = listOf(ItemFields.PROVIDER_IDS, ItemFields.PATH)

/**
 * The TubeArchivist ID of a Jellyfin item: the video ID of an episode or the channel ID of a show.
 * Read from the plugin's provider ID, or else from the file name, which TubeArchivist sets to the
 * video ID (and the folder to the channel ID).
 */
fun BaseItemDto.tubeArchivistId(): String? {
    providerIds
        ?.entries
        ?.firstOrNull { it.key.equals(PROVIDER_NAME, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    val itemPath = path ?: mediaSources?.firstOrNull()?.path ?: return null
    return when (type) {
        BaseItemKind.EPISODE,
        BaseItemKind.VIDEO,
        BaseItemKind.MOVIE -> VIDEO_FILE_REGEX.find(itemPath)?.groupValues?.get(1)
        BaseItemKind.SERIES -> CHANNEL_FOLDER_REGEX.find(itemPath)?.groupValues?.get(1)
        else -> null
    }
}

/**
 * Finds the Jellyfin copies of TubeArchivist videos and channels, so they can be played and
 * downloaded through Jellyfin, and the other way around. Results are kept for the session.
 */
class TaJellyfinLookup(private val jellyfinApi: JellyfinApi) {
    private val episodeIds = ConcurrentHashMap<String, UUID>()
    private val seriesIds = ConcurrentHashMap<String, UUID>()
    private val videoIds = ConcurrentHashMap<UUID, String>()

    /** The Jellyfin episode of a TubeArchivist video, or null if Jellyfin doesn't have it (yet). */
    suspend fun findEpisodeId(
        videoId: String,
        title: String,
        channelId: String?,
        channelName: String?,
    ): UUID? =
        withContext(Dispatchers.IO) {
            episodeIds[videoId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null

            // A search by title is usually enough
            val searchTerm = title.trim().take(SEARCH_LENGTH)
            if (searchTerm.isNotEmpty()) {
                val found =
                    try {
                        jellyfinApi.libraryApi
                            .getItems(
                                userId = userId,
                                searchTerm = searchTerm,
                                includeItemTypes = listOf(BaseItemKind.EPISODE),
                                recursive = true,
                                fields = LOOKUP_FIELDS,
                                limit = 25,
                                enableUserData = false,
                            )
                            .content
                            .items
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        emptyList()
                    }
                remember(found)
                episodeIds[videoId]?.let {
                    return@withContext it
                }
            }

            // Otherwise go through every episode of the channel
            val seriesId =
                channelId?.let { findSeriesId(it, channelName) } ?: return@withContext null
            val episodes =
                jellyfinApi.libraryApi
                    .getItems(
                        userId = userId,
                        parentId = seriesId,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        fields = LOOKUP_FIELDS,
                        enableUserData = false,
                    )
                    .content
                    .items
            remember(episodes)
            episodeIds[videoId]
        }

    /** The Jellyfin show of a TubeArchivist channel. */
    suspend fun findSeriesId(channelId: String, channelName: String?): UUID? =
        withContext(Dispatchers.IO) {
            seriesIds[channelId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null
            val byName =
                channelName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { name ->
                        jellyfinApi.libraryApi
                            .getItems(
                                userId = userId,
                                searchTerm = name.take(SEARCH_LENGTH),
                                includeItemTypes = listOf(BaseItemKind.SERIES),
                                recursive = true,
                                fields = LOOKUP_FIELDS,
                                limit = 25,
                                enableUserData = false,
                            )
                            .content
                            .items
                    }
                    .orEmpty()
            rememberSeries(byName)
            seriesIds[channelId]?.let {
                return@withContext it
            }
            val allSeries =
                jellyfinApi.libraryApi
                    .getItems(
                        userId = userId,
                        includeItemTypes = listOf(BaseItemKind.SERIES),
                        recursive = true,
                        fields = LOOKUP_FIELDS,
                        enableUserData = false,
                    )
                    .content
                    .items
            rememberSeries(allSeries)
            seriesIds[channelId]
        }

    /** The TubeArchivist video ID of a Jellyfin item, or null if it isn't from TubeArchivist. */
    suspend fun videoIdForItem(itemId: UUID): String? =
        withContext(Dispatchers.IO) {
            videoIds[itemId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null
            val item = jellyfinApi.libraryApi.getItem(itemId, userId).content
            val videoId = item.tubeArchivistId() ?: return@withContext null
            videoIds[itemId] = videoId
            episodeIds[videoId] = itemId
            videoId
        }

    private fun remember(items: List<BaseItemDto>) {
        items.forEach { item ->
            item.tubeArchivistId()?.let { videoId ->
                episodeIds[videoId] = item.id
                videoIds[item.id] = videoId
            }
        }
    }

    private fun rememberSeries(items: List<BaseItemDto>) {
        items.forEach { item -> item.tubeArchivistId()?.let { seriesIds[it] = item.id } }
    }

    companion object {
        private const val SEARCH_LENGTH = 60
    }
}
