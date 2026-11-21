package dev.jdtech.jellyfin.presentation.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.components.ReorderableColumn
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.PlayerButton
import dev.jdtech.jellyfin.settings.domain.PlayerButtonConfig
import dev.jdtech.jellyfin.settings.domain.PlayerButtonPlacement
import dev.jdtech.jellyfin.settings.presentation.playerbuttons.PlayerButtonsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerButtonsScreen(
    navigateBack: () -> Unit,
    viewModel: PlayerButtonsViewModel = hiltViewModel(),
) {
    val buttons by viewModel.buttons.collectAsStateWithLifecycle()
    val byButton = buttons.associateBy { it.button }
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = Modifier.fillMaxSize().recalculateWindowInsets(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SettingsR.string.pref_player_buttons)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.reset() }) {
                        Text(stringResource(SettingsR.string.player_buttons_reset))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(scrollState),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacings.default),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                Text(
                    text = stringResource(SettingsR.string.player_buttons_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReorderableColumn(
                    keys = buttons.map { it.button },
                    onReorder = { viewModel.reorder(it) },
                    spacing = MaterialTheme.spacings.small,
                    scrollState = scrollState,
                ) { button ->
                    val config = byButton[button]
                    if (config != null) {
                        PlayerButtonRow(
                            config = config,
                            onPlacementChange = { viewModel.setPlacement(button, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerButtonRow(
    config: PlayerButtonConfig,
    onPlacementChange: (PlayerButtonPlacement) -> Unit,
) {
    val hidden = config.placement == PlayerButtonPlacement.HIDDEN
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacings.medium,
                        end = MaterialTheme.spacings.small,
                        top = MaterialTheme.spacings.small,
                        bottom = MaterialTheme.spacings.small,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_down_up),
                contentDescription = null,
                modifier = Modifier.alpha(0.6f),
            )
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Icon(
                painter = painterResource(config.button.iconRes()),
                contentDescription = null,
                modifier = Modifier.alpha(if (hidden) 0.4f else 1f),
            )
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Column(modifier = Modifier.weight(1f).alpha(if (hidden) 0.5f else 1f)) {
                Text(
                    text = stringResource(config.button.nameRes()),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (config.button.isConditional()) {
                    Text(
                        text = stringResource(SettingsR.string.player_button_conditional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(stringResource(config.placement.nameRes()))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    PlayerButtonPlacement.entries.forEach { placement ->
                        DropdownMenuItem(
                            text = { Text(stringResource(placement.nameRes())) },
                            onClick = {
                                menuOpen = false
                                onPlacementChange(placement)
                            },
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun PlayerButton.nameRes(): Int =
    when (this) {
        PlayerButton.AUTOPLAY -> SettingsR.string.player_button_autoplay
        PlayerButton.LOCK -> SettingsR.string.player_button_lock
        PlayerButton.PIP -> SettingsR.string.player_button_pip
        PlayerButton.CHAPTERS -> SettingsR.string.player_button_chapters
        PlayerButton.QUALITY -> SettingsR.string.player_button_quality
        PlayerButton.SPEED -> SettingsR.string.player_button_speed
        PlayerButton.SLEEP_TIMER -> SettingsR.string.player_button_sleep_timer
        PlayerButton.AUDIO -> SettingsR.string.player_button_audio
        PlayerButton.SUBTITLES -> SettingsR.string.player_button_subtitles
        PlayerButton.ROTATE -> SettingsR.string.player_button_rotate
        PlayerButton.PLAYLIST -> SettingsR.string.player_button_playlist
        PlayerButton.LISTEN -> SettingsR.string.player_button_listen
    }

@DrawableRes
private fun PlayerButton.iconRes(): Int =
    when (this) {
        PlayerButton.AUTOPLAY -> CoreR.drawable.ic_autoplay_on
        PlayerButton.LOCK -> CoreR.drawable.ic_lock
        PlayerButton.PIP -> CoreR.drawable.ic_picture_in_picture
        PlayerButton.CHAPTERS -> CoreR.drawable.ic_list_chapters
        PlayerButton.QUALITY -> CoreR.drawable.ic_film
        PlayerButton.SPEED -> CoreR.drawable.ic_gauge
        PlayerButton.SLEEP_TIMER -> CoreR.drawable.ic_moon
        PlayerButton.AUDIO -> CoreR.drawable.ic_speaker
        PlayerButton.SUBTITLES -> CoreR.drawable.ic_closed_caption
        PlayerButton.ROTATE -> CoreR.drawable.ic_rotate_to_portrait
        PlayerButton.PLAYLIST -> CoreR.drawable.ic_list_video
        PlayerButton.LISTEN -> CoreR.drawable.ic_headphones
    }

/**
 * Chapters and subtitles only show for videos that have them, quality only for streams, PiP only if
 * allowed, and the playlist only while one plays.
 */
private fun PlayerButton.isConditional(): Boolean =
    when (this) {
        PlayerButton.CHAPTERS,
        PlayerButton.SUBTITLES,
        PlayerButton.QUALITY,
        PlayerButton.PIP,
        PlayerButton.PLAYLIST -> true
        else -> false
    }

@StringRes
private fun PlayerButtonPlacement.nameRes(): Int =
    when (this) {
        PlayerButtonPlacement.TOP -> SettingsR.string.player_button_placement_top
        PlayerButtonPlacement.BOTTOM -> SettingsR.string.player_button_placement_bottom
        PlayerButtonPlacement.MENU -> SettingsR.string.player_button_placement_menu
        PlayerButtonPlacement.HIDDEN -> SettingsR.string.player_button_placement_hidden
    }
