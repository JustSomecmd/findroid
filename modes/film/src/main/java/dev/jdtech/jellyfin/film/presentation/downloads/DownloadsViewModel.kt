package dev.jdtech.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    fun loadItems() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val items = repository.getDownloads()
                val showEpisodes = appPreferences.getValue(appPreferences.downloadsShowEpisodes)
                val episodes =
                    if (showEpisodes) repository.getDownloadedEpisodes() else emptyList()

                val sections = mutableListOf<CollectionSection>()

                withContext(Dispatchers.Default) {
                    CollectionSection(
                            Constants.FAVORITE_TYPE_MOVIES,
                            UiText.StringResource(CoreR.string.movies_label),
                            items.filterIsInstance<FindroidMovie>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    if (showEpisodes) {
                        sections.addAll(episodeSections(episodes))
                    } else {
                        CollectionSection(
                                Constants.FAVORITE_TYPE_SHOWS,
                                UiText.StringResource(CoreR.string.shows_label),
                                items.filterIsInstance<FindroidShow>(),
                            )
                            .let {
                                if (it.items.isNotEmpty()) {
                                    sections.add(it)
                                }
                            }
                    }
                }

                _state.emit(_state.value.copy(isLoading = false, sections = sections))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    /** One section per show, named after the show, with its episodes in season/episode order. */
    private fun episodeSections(episodes: List<FindroidEpisode>): List<CollectionSection> =
        episodes
            .groupBy { it.seriesId }
            .values
            .sortedBy { it.first().seriesName.lowercase() }
            .mapIndexed { index, showEpisodes ->
                CollectionSection(
                    id = EPISODE_SECTION_ID_START + index,
                    name = UiText.DynamicString(showEpisodes.first().seriesName),
                    items =
                        showEpisodes.sortedWith(
                            compareBy<FindroidEpisode>(
                                { it.parentIndexNumber },
                                { it.indexNumber },
                            )
                        ),
                )
            }

    companion object {
        // Keeps per-show section ids apart from the fixed movie/show section ids
        private const val EPISODE_SECTION_ID_START = 1000
    }
}
