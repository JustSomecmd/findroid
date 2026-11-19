package dev.jdtech.jellyfin.tubearchivist

import dev.jdtech.jellyfin.api.JellyfinApi
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi

/**
 * The playlists being watched, kept in the Jellyfin user's display settings, where Winchivist sees
 * them too when it's signed in as the same user.
 */
class JellyfinPlaylistSessionStore(private val jellyfinApi: JellyfinApi) : TaPlaylistSessionStore {
    override suspend fun read(): String? =
        withContext(Dispatchers.IO) { preferences(userId()).customPrefs[TaPlaylistSessionSync.KEY] }

    override suspend fun write(text: String) {
        withContext(Dispatchers.IO) {
            val userId = userId()
            val current = preferences(userId)
            jellyfinApi.api.displayPreferenceApi.updateDisplayPreferences(
                displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                userId = userId,
                client = TaPlaylistSessionSync.CLIENT,
                data =
                    current.copy(
                        customPrefs = current.customPrefs + (TaPlaylistSessionSync.KEY to text)
                    ),
            )
        }
    }

    private fun userId(): UUID = jellyfinApi.userId ?: error("Not signed in to Jellyfin")

    private suspend fun preferences(userId: UUID) =
        jellyfinApi.api.displayPreferenceApi
            .getDisplayPreferences(
                displayPreferencesId = TaPlaylistSessionSync.PREFERENCES_ID,
                userId = userId,
                client = TaPlaylistSessionSync.CLIENT,
            )
            .content
}
