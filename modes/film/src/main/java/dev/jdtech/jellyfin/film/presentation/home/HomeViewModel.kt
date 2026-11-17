package dev.jdtech.jellyfin.film.presentation.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.toView
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching =
        UUID(4937169328197226115, -4704919157662094443) // 44845958-8326-4e83-beb4-c4f42e9eeb95
    private val uuidNextUp =
        UUID(1783371395749072194, -6164625418200444295) // 18bfced5-f237-4d42-aa72-d9d7fed19279

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)

    // When Home last finished loading, to skip reloading it moments later
    @Volatile private var lastLoaded = 0L

    /** Loads Home again when it comes back into view, unless that was only moments ago. */
    fun refreshIfStale() {
        val sinceLastLoad = SystemClock.elapsedRealtime() - lastLoaded
        if (lastLoaded == 0L || sinceLastLoad > REFRESH_AFTER_MS) loadData()
    }

    /**
     * Reloads Home when the server reports new, changed or removed items, for as long as this is
     * running. A burst of changes, like during a library scan, leads to a single reload.
     */
    suspend fun followLibraryChanges() {
        repository.libraryChanges().collectLatest {
            delay(LIBRARY_CHANGE_DELAY_MS)
            loadData()
        }
    }

    fun loadData() {
        Timber.i("Loading data")
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                    loadServerName(serverId)
                }

                // The rows don't depend on each other, so they load side by side
                coroutineScope {
                    launch { loadSuggestions() }
                    launch { loadResumeItems() }
                    launch { loadNextUpItems() }
                    launch { loadViews() }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e) }
            }
            lastLoaded = SystemClock.elapsedRealtime()
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadServerName(serverId: String) {
        val server = database.getServer(serverId)
        if (server != null) {
            _state.update { it.copy(server = server) }
        }
    }

    private suspend fun loadSuggestions() {
        Timber.i("Loading suggestions")
        if (!appPreferences.getValue(appPreferences.homeSuggestions)) {
            _state.update { it.copy(suggestionsSection = null) }
            return
        }

        val items = repository.getSuggestions()

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Suggestions(id = uuidSuggestions, items = items)
            }

        _state.update { it.copy(suggestionsSection = section) }
    }

    private suspend fun loadResumeItems() {
        Timber.i("Loading resume items")
        if (!appPreferences.getValue(appPreferences.homeContinueWatching)) {
            _state.update { it.copy(resumeSection = null) }
            return
        }

        val resumeItems = repository.getResumeItems()

        val section =
            if (resumeItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(
                    HomeSection(uuidContinueWatching, uiTextContinueWatching, resumeItems)
                )
            }

        _state.update { it.copy(resumeSection = section) }
    }

    private suspend fun loadNextUpItems() {
        Timber.i("Loading next up items")
        if (!appPreferences.getValue(appPreferences.homeNextUp)) {
            _state.update { it.copy(nextUpSection = null) }
            return
        }

        val nextUpItems = repository.getNextUp()

        val section =
            if (nextUpItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(uuidNextUp, uiTextNextUp, nextUpItems))
            }

        _state.update { it.copy(nextUpSection = section) }
    }

    private suspend fun loadViews() {
        Timber.i("Loading views")
        // Every new episode on its own, with its thumbnail, instead of grouped under the show
        val latestEpisodes = appPreferences.getValue(appPreferences.homeLatestEpisodes)
        val items =
            if (appPreferences.getValue(appPreferences.homeLatest)) {
                repository
                    .getUserViews()
                    .filter { view ->
                        CollectionType.fromString(view.collectionType?.serialName) in
                            CollectionType.supported
                    }
                    // One request per library, all at once
                    .let { views ->
                        coroutineScope {
                            views
                                .map { view ->
                                    async {
                                        view to repository.getLatestMedia(view.id, latestEpisodes)
                                    }
                                }
                                .awaitAll()
                        }
                    }
                    .filter { (_, latest) -> latest.isNotEmpty() }
                    .map { (view, latest) -> view.toView(latest) }
                    .map { HomeItem.ViewItem(it, episodeThumbnails = latestEpisodes) }
            } else {
                emptyList()
            }

        _state.update { it.copy(views = items) }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }
}

// Coming back to Home reloads it if it is older than this
private const val REFRESH_AFTER_MS = 15_000L

// Waits this long after a library change for more to come in before reloading
private const val LIBRARY_CHANGE_DELAY_MS = 1_500L
