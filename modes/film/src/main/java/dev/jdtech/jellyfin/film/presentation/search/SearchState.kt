package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.models.CollectionSection

data class SearchState(
    /** Results grouped by type: movies, shows, episodes, collections, playlists. */
    val sections: List<CollectionSection> = emptyList(),
    /** The query the current results belong to. */
    val query: String = "",
    val loading: Boolean = false,
) {
    val hasResults: Boolean
        get() = sections.isNotEmpty()
}
