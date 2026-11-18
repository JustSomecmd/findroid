package dev.jdtech.jellyfin.tubearchivist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/** A TubeArchivist playlist being watched through, and the video it's at. */
@Serializable
data class TaPlaylistSession(
    val playlistId: String,
    val playlistName: String,
    /** The video being watched, or the one to watch next once the last one was finished. */
    val videoId: String,
    val updatedAt: Long,
)

/**
 * The playlists being watched through on this phone, newest first, so Home can offer to carry on
 * where they were left.
 */
class TaPlaylistSessions(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TaPlaylistSession.serializer())

    private val _sessions = MutableStateFlow(read())
    val sessions: StateFlow<List<TaPlaylistSession>> = _sessions.asStateFlow()

    fun get(playlistId: String): TaPlaylistSession? =
        _sessions.value.firstOrNull { it.playlistId == playlistId }

    /** Notes that [videoId] is the video the playlist is at now, which moves it to the front. */
    @Synchronized
    fun update(playlistId: String, playlistName: String, videoId: String) {
        val session =
            TaPlaylistSession(playlistId, playlistName, videoId, System.currentTimeMillis())
        save(listOf(session) + _sessions.value.filter { it.playlistId != playlistId })
    }

    /** Forgets a playlist, once it was watched to the end. */
    @Synchronized
    fun remove(playlistId: String) {
        save(_sessions.value.filter { it.playlistId != playlistId })
    }

    private fun save(sessions: List<TaPlaylistSession>) {
        val kept = sessions.take(MAX_SESSIONS)
        _sessions.value = kept
        prefs.edit().putString(KEY, json.encodeToString(serializer, kept)).apply()
    }

    private fun read(): List<TaPlaylistSession> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            Timber.w(e, "Could not read the playlists being watched")
            emptyList()
        }
    }

    private companion object {
        const val PREFS_NAME = "tubearchivist_playlists"
        const val KEY = "sessions"
        const val MAX_SESSIONS = 10
    }
}
