package dev.jdtech.jellyfin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    var currentJob: Job? = null

    private data class SectionType(
        val id: Int,
        val name: UiText,
        val types: List<BaseItemKind>,
        val limit: Int,
    )

    // Searched separately, like the official Jellyfin apps do, so that many matching episodes
    // can't push movies and shows out of the results
    private val sectionTypes =
        listOf(
            SectionType(
                SECTION_MOVIES,
                UiText.StringResource(CoreR.string.movies_label),
                listOf(BaseItemKind.MOVIE),
                limit = 24,
            ),
            SectionType(
                SECTION_SHOWS,
                UiText.StringResource(CoreR.string.shows_label),
                listOf(BaseItemKind.SERIES),
                limit = 24,
            ),
            SectionType(
                SECTION_EPISODES,
                UiText.StringResource(CoreR.string.episodes_label),
                listOf(BaseItemKind.EPISODE),
                limit = 40,
            ),
            SectionType(
                SECTION_COLLECTIONS,
                UiText.StringResource(CoreR.string.collections_label),
                listOf(BaseItemKind.BOX_SET),
                limit = 12,
            ),
            SectionType(
                SECTION_PLAYLISTS,
                UiText.StringResource(CoreR.string.title_playlists),
                listOf(BaseItemKind.PLAYLIST),
                limit = 12,
            ),
        )

    private fun search(query: String) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                _state.emit(SearchState())
                return@launch
            }

            _state.emit(_state.value.copy(loading = true))

            val (sections, people) =
                coroutineScope {
                    val sectionResults = sectionTypes.map { type ->
                        async {
                            val items = searchSafely {
                                repository.searchItems(trimmed, type.types, type.limit)
                            }
                            CollectionSection(type.id, type.name, items)
                        }
                    }
                    val peopleResult = async {
                        searchSafely { repository.searchPeople(trimmed, PEOPLE_LIMIT) }
                    }
                    Pair(
                        sectionResults.map { it.await() }.filter { it.items.isNotEmpty() },
                        peopleResult.await(),
                    )
                }

            _state.emit(
                SearchState(sections = sections, people = people, query = trimmed, loading = false)
            )
        }
    }

    /** One failing search type (for example people while offline) doesn't hide the others. */
    private suspend fun <T> searchSafely(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            else -> Unit
        }
    }

    companion object {
        private const val SECTION_MOVIES = 0
        private const val SECTION_SHOWS = 1
        private const val SECTION_EPISODES = 2
        private const val SECTION_COLLECTIONS = 3
        private const val SECTION_PLAYLISTS = 4
        private const val PEOPLE_LIMIT = 20
    }
}
