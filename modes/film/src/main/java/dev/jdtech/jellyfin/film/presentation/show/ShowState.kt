package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow

data class ShowState(
    val show: FindroidShow? = null,
    val nextUp: FindroidEpisode? = null,
    val seasons: List<FindroidSeason> = emptyList(),
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    /** When true, the page lists [episodes] instead of Next up and the seasons row. */
    val showAllEpisodes: Boolean = false,
    val episodes: List<FindroidEpisode> = emptyList(),
    val newestFirst: Boolean = true,
    val error: Exception? = null,
)
