package dev.jdtech.jellyfin.tubearchivist

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The playlists being watched through, newest first, so Home can offer to carry on where they were
 * left. Kept on the phone and, through [store], in Jellyfin, where Winchivist meets them.
 */
class TaPlaylistSessions(context: Context, private val store: TaPlaylistSessionStore? = null) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()

    // All the notes, finished playlists' included, which other devices need to hear about
    private var all: List<TaPlaylistSession> =
        TaPlaylistSessionSync.decode(prefs.getString(KEY, null))

    private val _sessions = MutableStateFlow(all.filter { !it.done })

    /** The playlists being watched through, without the finished ones. */
    val sessions: StateFlow<List<TaPlaylistSession>> = _sessions.asStateFlow()

    fun get(playlistId: String): TaPlaylistSession? =
        _sessions.value.firstOrNull { it.playlistId == playlistId }

    /** Notes that [videoId] is the video the playlist is at now, which moves it to the front. */
    fun update(playlistId: String, playlistName: String, videoId: String) {
        val session =
            TaPlaylistSession(playlistId, playlistName, videoId, System.currentTimeMillis())
        synchronized(this) { save(listOf(session) + all.filter { it.playlistId != playlistId }) }
        syncSoon()
    }

    /** Marks a playlist as finished, once it was watched to the end or is gone. */
    fun remove(playlistId: String) {
        synchronized(this) {
            val session = all.firstOrNull { it.playlistId == playlistId && !it.done } ?: return
            val done = session.copy(done = true, updatedAt = System.currentTimeMillis())
            save(listOf(done) + all.filter { it.playlistId != playlistId })
        }
        syncSoon()
    }

    /**
     * Brings the phone's notes and Jellyfin's together, keeping the newest of each playlist. Does
     * nothing when Jellyfin can't be reached.
     */
    suspend fun sync() {
        val store = store ?: return
        syncLock.withLock {
            try {
                val local = synchronized(this) { all }
                // Jellyfin taking too long counts as not reachable
                val merged =
                    withTimeoutOrNull(SYNC_TIMEOUT_MS) { TaPlaylistSessionSync.sync(local, store) }
                        ?: return@withLock
                // Anything noted while Jellyfin was being asked stays too
                synchronized(this) { save(TaPlaylistSessionSync.merge(all, merged)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not bring the playlists being watched in step with Jellyfin")
            }
        }
    }

    private fun syncSoon() {
        scope.launch { sync() }
    }

    private fun save(sessions: List<TaPlaylistSession>) {
        val kept = sessions.take(TaPlaylistSessionSync.MAX_SESSIONS)
        all = kept
        _sessions.value = kept.filter { !it.done }
        prefs.edit().putString(KEY, TaPlaylistSessionSync.encode(kept)).apply()
    }

    private companion object {
        const val PREFS_NAME = "tubearchivist_playlists"
        const val KEY = "sessions"
        const val SYNC_TIMEOUT_MS = 8_000L
    }
}
