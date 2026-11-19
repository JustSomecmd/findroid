package dev.jdtech.jellyfin.tubearchivist

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A TubeArchivist playlist being watched through, and the video it's at. */
@Serializable
data class TaPlaylistSession(
    val playlistId: String,
    val playlistName: String,
    /** The video being watched, or the one to watch next once the last one was finished. */
    val videoId: String,
    val updatedAt: Long,
    /**
     * Watched to the end. The note stays for a while, so other devices forget the playlist too
     * instead of bringing it back.
     */
    val done: Boolean = false,
)

/**
 * A place every device can reach, where the playlists being watched are kept as text. The apps keep
 * it in the Jellyfin user's display settings, under [TaPlaylistSessionSync.PREFERENCES_ID].
 */
interface TaPlaylistSessionStore {
    suspend fun read(): String?

    suspend fun write(text: String)
}

/**
 * Brings together what the phone and the computer know of the playlists being watched. Neither
 * TubeArchivist nor Jellyfin has playlists being watched through, so the apps keep them themselves:
 * each device has its own copy and they meet in a [TaPlaylistSessionStore]. Of each playlist, the
 * newest note wins.
 */
object TaPlaylistSessionSync {
    /** Where Jellyfin keeps them: the display settings by this ID, for this client, at this key. */
    const val PREFERENCES_ID = "tubedroid"
    const val CLIENT = "tubedroid"
    const val KEY = "playlistSessions"

    /** How many playlists are kept, finished ones included. */
    const val MAX_SESSIONS = 20

    /** How long a finished playlist's note is kept. */
    private const val DONE_KEPT_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TaPlaylistSession.serializer())

    fun decode(text: String?): List<TaPlaylistSession> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(sessions: List<TaPlaylistSession>): String =
        json.encodeToString(serializer, sessions)

    /**
     * The newest note of each playlist, newest first. Finished playlists are dropped once they've
     * been done for a while.
     */
    fun merge(
        vararg lists: List<TaPlaylistSession>,
        now: Long = System.currentTimeMillis(),
    ): List<TaPlaylistSession> =
        lists
            .flatMap { it }
            .groupBy { it.playlistId }
            .values
            .map { notes -> notes.maxBy { it.updatedAt } }
            .filter { !it.done || now - it.updatedAt < DONE_KEPT_MS }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SESSIONS)

    /**
     * Brings [local] together with the store's copy, and writes it back when the store's copy was
     * behind. Returns them together, for the device to keep.
     */
    suspend fun sync(
        local: List<TaPlaylistSession>,
        store: TaPlaylistSessionStore,
    ): List<TaPlaylistSession> {
        val stored = store.read()
        val merged = merge(local, decode(stored))
        val text = encode(merged)
        if (text != stored) store.write(text)
        return merged
    }
}
