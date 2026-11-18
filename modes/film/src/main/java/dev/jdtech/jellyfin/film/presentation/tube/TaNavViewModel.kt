package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Whether TubeArchivist is set up, which makes it the main part of the app. */
@HiltViewModel
class TaNavViewModel @Inject constructor(appPreferences: AppPreferences, taApi: TaApi) :
    ViewModel() {
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
}
