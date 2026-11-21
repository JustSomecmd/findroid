package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiJellyfinLookup
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether TubeArchivist is set up, which makes it the main part of the app. */
@HiltViewModel
class TaNavViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    taApi: TaApi,
    private val taOfflineStore: TaOfflineStore,
    private val jrwiDownloads: JrwiDownloads,
    private val jrwiLookup: JrwiJellyfinLookup,
) : ViewModel() {
    val configured =
        combine(
                appPreferences.observe(appPreferences.tubeArchivistUrl),
                appPreferences.observe(appPreferences.tubeArchivistToken),
            ) { url, token ->
                !url.isNullOrBlank() && !token.isNullOrBlank()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, taApi.isConfigured)

    /** The server address, for the images it serves. */
    val baseUrl =
        appPreferences
            .observe(appPreferences.tubeArchivistUrl)
            .map { url -> url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, taApi.baseUrl)

    init {
        // Progress made offline, and details of earlier downloads
        if (!appPreferences.getValue(appPreferences.offlineMode)) {
            viewModelScope.launch { taOfflineStore.sync() }
        }
    }

    /** The TubeArchivist video of a downloaded Jellyfin item, so it opens on that page. */
    fun downloadedVideoId(itemId: UUID): String? = taOfflineStore.videoIdForItem(itemId)

    /**
     * The JRWI episode of a Jellyfin item, so it opens on the episode's page: kept with the video's
     * download from the moment it starts, or known from looking it up before.
     */
    fun jrwiItemId(itemId: UUID): Long? =
        jrwiDownloads.savedVideoForEpisode(itemId.toString())?.item?.id
            ?: jrwiLookup.knownPostId(itemId)
}
