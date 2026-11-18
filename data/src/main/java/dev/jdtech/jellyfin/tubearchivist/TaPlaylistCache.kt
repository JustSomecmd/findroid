package dev.jdtech.jellyfin.tubearchivist

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import timber.log.Timber

/** A playlist as it was last seen, with the videos of it that were loaded. */
@Serializable
data class TaCachedPlaylist(val playlist: TaPlaylist, val videos: List<TaVideo> = emptyList()) {
    /**
     * The playlist's downloaded videos in order. Videos that were never loaded have just their
     * title, and the thumbnail TubeArchivist keeps for them, in case it was seen before.
     */
    fun orderedVideos(saved: (String) -> TaVideo? = { null }): List<TaVideo> {
        val loaded = videos.associateBy { it.youtubeId }
        return playlist.entries
            .filter { it.downloaded }
            .map { entry ->
                loaded[entry.youtubeId]
                    ?: saved(entry.youtubeId)
                    ?: TaVideo(
                        youtubeId = entry.youtubeId,
                        title = entry.title,
                        thumbUrl = "/media/thumb/video/${entry.youtubeId}.jpg",
                    )
            }
            .ifEmpty { videos }
    }
}

/**
 * Keeps the playlists and their videos on the phone as they were last seen, so they can still be
 * browsed and played through when TubeArchivist can't be reached.
 */
class TaPlaylistCache(context: Context, private val taApi: TaApi) {
    private val dir = File(context.filesDir, "tubearchivist/playlists")
    private val listFile = File(dir, "all.json")
    private val playlistsSerializer = ListSerializer(TaPlaylist.serializer())

    suspend fun savePlaylist(playlist: TaPlaylist, videos: List<TaVideo>) =
        write(file(playlist.id)) {
            taApi.json.encodeToString(
                TaCachedPlaylist.serializer(),
                TaCachedPlaylist(playlist, videos),
            )
        }

    /** Keeps the playlist's new details along with the videos saved for it before. */
    suspend fun updatePlaylist(playlist: TaPlaylist) {
        savePlaylist(playlist, loadPlaylist(playlist.id)?.videos.orEmpty())
    }

    suspend fun loadPlaylist(playlistId: String): TaCachedPlaylist? =
        read(file(playlistId)) { taApi.json.decodeFromString(TaCachedPlaylist.serializer(), it) }

    /** The list of all playlists, as on the Playlists tab. */
    suspend fun savePlaylists(playlists: List<TaPlaylist>) =
        write(listFile) { taApi.json.encodeToString(playlistsSerializer, playlists) }

    suspend fun loadPlaylists(): List<TaPlaylist> =
        read(listFile) { taApi.json.decodeFromString(playlistsSerializer, it) }.orEmpty()

    // Only letters, digits, dashes and underscores, as in YouTube's playlist IDs
    private fun file(playlistId: String) =
        File(dir, playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private suspend fun write(file: File, text: () -> String) {
        withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val temp = File(dir, file.name + ".tmp")
                temp.writeText(text())
                temp.renameTo(file)
            } catch (e: Exception) {
                Timber.w(e, "Could not save playlist data")
            }
        }
    }

    private suspend fun <T> read(file: File, parse: (String) -> T): T? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                parse(file.readText())
            } catch (e: Exception) {
                Timber.w(e, "Could not read saved playlist data")
                null
            }
        }
}
