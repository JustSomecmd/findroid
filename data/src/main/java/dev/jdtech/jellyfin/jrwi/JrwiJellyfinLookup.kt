package dev.jdtech.jellyfin.jrwi

import dev.jdtech.jellyfin.api.JellyfinApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields

/** Name the archiver's .nfo files store the post ID under. */
private const val PROVIDER_NAME = "jrwi"

private val LOOKUP_FIELDS = listOf(ItemFields.PROVIDER_IDS, ItemFields.PATH)

/**
 * The JRWI post ID of a Jellyfin item: the archiver's provider ID, or else the `[jrwi-<id>]` tag
 * the archiver puts in its file names.
 */
fun BaseItemDto.jrwiPostId(): Long? {
    providerIds
        ?.entries
        ?.firstOrNull { it.key.equals(PROVIDER_NAME, ignoreCase = true) }
        ?.value
        ?.toLongOrNull()
        ?.let {
            return it
        }
    return JrwiApi.postIdInPath(path ?: mediaSources?.firstOrNull()?.path)
}

/**
 * Finds the Jellyfin episodes of the archiver's videos, so they play and download through Jellyfin,
 * and the other way around. Results are kept for the session.
 */
class JrwiJellyfinLookup(private val jellyfinApi: JellyfinApi) {
    private val episodeIds = ConcurrentHashMap<Long, UUID>()
    private val postIds = ConcurrentHashMap<UUID, Long>()

    /**
     * The Jellyfin episode of an archived video, or null if Jellyfin doesn't have it (yet). The
     * archiver files each video under a folder named after its show, so that show's episodes are
     * gone through.
     */
    suspend fun findEpisodeId(postId: Long, showName: String): UUID? =
        withContext(Dispatchers.IO) {
            episodeIds[postId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null
            val shows =
                jellyfinApi.libraryApi
                    .getItems(
                        userId = userId,
                        searchTerm = showName.trim().take(SEARCH_LENGTH),
                        includeItemTypes = listOf(BaseItemKind.SERIES),
                        recursive = true,
                        fields = LOOKUP_FIELDS,
                        limit = 25,
                        enableUserData = false,
                    )
                    .content
                    .items
            // The show of that name first, in case another library has a show like it
            val inOrder = shows.sortedByDescending { it.name.equals(showName, ignoreCase = true) }
            for (show in inOrder) {
                val episodes =
                    jellyfinApi.libraryApi
                        .getItems(
                            userId = userId,
                            parentId = show.id,
                            includeItemTypes = listOf(BaseItemKind.EPISODE),
                            recursive = true,
                            fields = LOOKUP_FIELDS,
                            enableUserData = false,
                        )
                        .content
                        .items
                remember(episodes)
                episodeIds[postId]?.let {
                    return@withContext it
                }
            }
            null
        }

    /** The post ID of a Jellyfin item when it was looked up or seen before, without asking. */
    fun knownPostId(itemId: UUID): Long? = postIds[itemId]

    /** The post ID of a Jellyfin item, or null if it isn't one of the archiver's videos. */
    suspend fun postIdForItem(itemId: UUID): Long? =
        withContext(Dispatchers.IO) {
            postIds[itemId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null
            val item = jellyfinApi.libraryApi.getItem(itemId, userId).content
            val postId = item.jrwiPostId() ?: return@withContext null
            postIds[itemId] = postId
            episodeIds[postId] = itemId
            postId
        }

    private fun remember(items: List<BaseItemDto>) {
        items.forEach { item ->
            item.jrwiPostId()?.let { postId ->
                episodeIds[postId] = item.id
                postIds[item.id] = postId
            }
        }
    }

    companion object {
        private const val SEARCH_LENGTH = 60
    }
}
