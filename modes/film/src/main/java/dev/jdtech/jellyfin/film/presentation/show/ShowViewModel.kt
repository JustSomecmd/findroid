package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class ShowViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val showAllEpisodes = appPreferences.getValue(appPreferences.showAllEpisodes)
                val newestFirst = appPreferences.getValue(appPreferences.showEpisodesNewestFirst)
                val nextUp = if (showAllEpisodes) null else getNextUp(showId)
                val seasons = repository.getSeasons(showId)
                val episodes =
                    if (showAllEpisodes) {
                        sortEpisodes(
                            repository.getAllEpisodes(showId).filter { !it.missing },
                            newestFirst,
                        )
                    } else {
                        emptyList()
                    }
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                _state.emit(
                    _state.value.copy(
                        show = show,
                        nextUp = nextUp,
                        seasons = seasons,
                        actors = actors,
                        director = director,
                        writers = writers,
                        showAllEpisodes = showAllEpisodes,
                        episodes = episodes,
                        newestFirst = newestFirst,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getNextUp(showId: UUID): FindroidEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    /**
     * Sorts by release date, newest or oldest first. Episodes on the same date keep their season
     * and episode order. Episodes without a release date go at the end.
     */
    private suspend fun sortEpisodes(
        episodes: List<FindroidEpisode>,
        newestFirst: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.Default) {
            val byNumber = compareBy<FindroidEpisode>({ it.parentIndexNumber }, { it.indexNumber })
            val (dated, undated) = episodes.partition { it.premiereDate != null }
            val oldestFirst =
                dated.sortedWith(compareBy<FindroidEpisode> { it.premiereDate }.then(byNumber))
            val sortedDated =
                if (newestFirst) {
                    oldestFirst.reversed()
                } else {
                    oldestFirst
                }
            sortedDated + undated.sortedWith(byNumber)
        }

    private suspend fun getActors(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people
                .filter { it.type == PersonKind.ACTOR }
                // Jellyfin 12+ returns a person once per role, keep one card per role
                .distinctBy { it.id to it.role }
        }
    }

    private suspend fun getDirector(item: FindroidShow): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }.distinctBy { it.id }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(showId)
                    loadShow(showId)
                }
            }
            is ShowAction.ToggleEpisodeOrder -> {
                viewModelScope.launch {
                    val newestFirst = !_state.value.newestFirst
                    // Remembered for every show page
                    appPreferences.setValue(appPreferences.showEpisodesNewestFirst, newestFirst)
                    val episodes = sortEpisodes(_state.value.episodes, newestFirst)
                    _state.emit(_state.value.copy(newestFirst = newestFirst, episodes = episodes))
                }
            }
            else -> Unit
        }
    }
}
