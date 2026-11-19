package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSession
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessionSync
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class HistoryData(
    /** The playlists being watched through, finished ones included for the phone to hear of. */
    val playlists: List<TaPlaylistSession> = emptyList(),
    /** When each video was last played here, by its ID. */
    val played: Map<String, Long> = emptyMap(),
)

/**
 * What was watched on this computer: the playlists being watched through with the video each is at,
 * and when videos were last played. Home uses it to offer to carry on with a playlist, and to put
 * what was watched last first, as the phone app does. The playlists are kept in step with the phone
 * through [store], Jellyfin, while signed in to it.
 */
class WatchHistory(
    private val settings: AppSettings,
    private val store: () -> TaPlaylistSessionStore? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private var data = read()

    /** The playlists being watched through, most recent first, without the finished ones. */
    var playlists by mutableStateOf(data.playlists.filter { !it.done })
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
    fun updatePlaylist(playlistId: String, playlistName: String, videoId: String) {
        val session =
            TaPlaylistSession(playlistId, playlistName, videoId, System.currentTimeMillis())
        synchronized(this) {
            val playlists = listOf(session) + data.playlists.filter { it.playlistId != playlistId }
            save(data.copy(playlists = playlists))
        }
        syncSoon()
    }

    /** Marks a playlist as finished, once it was watched to the end or is gone. */
    fun removePlaylist(playlistId: String) {
        synchronized(this) {
            val session =
                data.playlists.firstOrNull { it.playlistId == playlistId && !it.done } ?: return
            val done = session.copy(done = true, updatedAt = System.currentTimeMillis())
            val playlists = listOf(done) + data.playlists.filter { it.playlistId != playlistId }
            save(data.copy(playlists = playlists))
        }
        syncSoon()
    }

    /**
     * Brings this computer's playlists and Jellyfin's together, keeping the newest of each. Does
     * nothing when not signed in to Jellyfin or when it can't be reached.
     */
    suspend fun sync() {
        val store = store() ?: return
        syncLock.withLock {
            try {
                val local = synchronized(this) { data.playlists }
                // Jellyfin taking too long counts as not reachable
                val merged =
                    withTimeoutOrNull(SYNC_TIMEOUT_MS) { TaPlaylistSessionSync.sync(local, store) }
                        ?: return@withLock
                // Anything noted while Jellyfin was being asked stays too
                synchronized(this) {
                    save(data.copy(playlists = TaPlaylistSessionSync.merge(data.playlists, merged)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Couldn't bring the playlists in step with Jellyfin: $e")
            }
        }
    }

    private fun syncSoon() {
        scope.launch { sync() }
    }

    private fun save(new: HistoryData) {
        var kept = new.copy(playlists = new.playlists.take(TaPlaylistSessionSync.MAX_SESSIONS))
        var text = json.encodeToString(HistoryData.serializer(), kept)
        // Java's preferences take values of up to 8192 characters, so the oldest videos go first
        while (text.length > MAX_LENGTH && kept.played.isNotEmpty()) {
            val played = kept.played.entries.sortedByDescending { it.value }
            kept = kept.copy(played = played.take(played.size / 2).associate { it.key to it.value })
            text = json.encodeToString(HistoryData.serializer(), kept)
        }
        while (text.length > MAX_LENGTH && kept.playlists.size > 1) {
            kept = kept.copy(playlists = kept.playlists.dropLast(1))
            text = json.encodeToString(HistoryData.serializer(), kept)
        }
        data = kept
        playlists = kept.playlists.filter { !it.done }
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
        const val MAX_VIDEOS = 100
        const val MAX_LENGTH = 8000
        const val SYNC_TIMEOUT_MS = 8_000L
    }
}
