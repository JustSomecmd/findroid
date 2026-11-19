package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A TubeArchivist playlist being watched through, and the video it's at. */
@Serializable
data class PlaylistSession(
    val playlistId: String,
    val playlistName: String,
    /** The video being watched, or the one to watch next once the last one was finished. */
    val videoId: String,
    val updatedAt: Long,
)

@Serializable
private data class HistoryData(
    val playlists: List<PlaylistSession> = emptyList(),
    /** When each video was last played here, by its ID. */
    val played: Map<String, Long> = emptyMap(),
)

/**
 * What was watched on this computer: the playlists being watched through with the video each is at,
 * and when videos were last played. Home uses it to offer to carry on with a playlist, and to put
 * what was watched last first, as the phone app does.
 */
class WatchHistory(private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }
    private var data = read()

    /** The playlists being watched through, most recent first. */
    var playlists by mutableStateOf(data.playlists)
        private set

    /** When the video was last played here, or null if it wasn't. */
    fun lastPlayed(videoId: String): Long? = data.played[videoId]

    /** Notes that the video started playing. */
    @Synchronized
    fun played(videoId: String) {
        val played =
            (data.played + (videoId to System.currentTimeMillis()))
                .entries
                .sortedByDescending { it.value }
                .take(MAX_VIDEOS)
                .associate { it.key to it.value }
        save(data.copy(played = played))
    }

    /** Notes that [videoId] is the video the playlist is at now, which moves it to the front. */
    @Synchronized
    fun updatePlaylist(playlistId: String, playlistName: String, videoId: String) {
        val session = PlaylistSession(playlistId, playlistName, videoId, System.currentTimeMillis())
        val playlists = listOf(session) + data.playlists.filter { it.playlistId != playlistId }
        save(data.copy(playlists = playlists.take(MAX_PLAYLISTS)))
    }

    /** Forgets a playlist, once it was watched to the end or is gone. */
    @Synchronized
    fun removePlaylist(playlistId: String) {
        save(data.copy(playlists = data.playlists.filter { it.playlistId != playlistId }))
    }

    private fun save(new: HistoryData) {
        var kept = new
        var text = json.encodeToString(HistoryData.serializer(), kept)
        // Java's preferences take values of up to 8192 characters, so the oldest videos go first
        while (text.length > MAX_LENGTH && kept.played.isNotEmpty()) {
            val played = kept.played.entries.sortedByDescending { it.value }
            kept = kept.copy(played = played.take(played.size / 2).associate { it.key to it.value })
            text = json.encodeToString(HistoryData.serializer(), kept)
        }
        if (text.length > MAX_LENGTH) {
            kept = kept.copy(playlists = kept.playlists.take(1))
            text = json.encodeToString(HistoryData.serializer(), kept)
        }
        data = kept
        playlists = kept.playlists
        settings.watchHistory = text
    }

    private fun read(): HistoryData =
        settings.watchHistory?.let { text ->
            try {
                json.decodeFromString(HistoryData.serializer(), text)
            } catch (e: Exception) {
                null
            }
        } ?: HistoryData()

    private companion object {
        const val MAX_PLAYLISTS = 10
        const val MAX_VIDEOS = 100
        const val MAX_LENGTH = 8000
    }
}
