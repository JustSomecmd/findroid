package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidEpisode

/** Whether episode titles are shown without their season and episode number. */
val LocalHideEpisodeNumbers = compositionLocalOf { false }

/**
 * The title of an episode as shown in lists: "S1:E2 - Title" when [includeSeason] is true, "2.
 * Title" otherwise, or just "Title" when episode numbers are hidden in the settings.
 */
@Composable
fun episodeTitle(episode: FindroidEpisode, includeSeason: Boolean): String {
    if (LocalHideEpisodeNumbers.current) return episode.name
    return if (includeSeason) {
        stringResource(
            CoreR.string.episode_name_extended,
            episode.parentIndexNumber,
            episode.indexNumber,
            episode.name,
        )
    } else {
        stringResource(CoreR.string.episode_name, episode.indexNumber, episode.name)
    }
}
