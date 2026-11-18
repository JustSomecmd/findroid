package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelTab
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelViewModel
import dev.jdtech.jellyfin.presentation.theme.ServerSideTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaChannelScreen(
    channelId: String,
    onVideoClick: (videoId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: TaChannelViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaChannelSetting?>(null) }
    val globalConfig by viewModel.globalConfig.collectAsStateWithLifecycle()

    LaunchedEffect(channelId) { viewModel.load(channelId) }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { viewModel.loadMore() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val channel = state.channel

    ServerSideTheme(onServer = state.serverSide) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        TaSideTitle(
                            title = channel?.name.orEmpty(),
                            onServer = state.serverSide,
                            onToggle = { viewModel.toggleServerSide() },
                            localIcon = CoreR.drawable.ic_users,
                            localLabel = stringResource(CoreR.string.ta_side_channel),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        TaOpenInWebButton(path = "/channel/$channelId")
                        if (channel != null) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_more_vertical),
                                        contentDescription = null,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(CoreR.string.ta_queue_channel))
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.queueChannel()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                )
            },
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when {
                    channel == null && state.error != null ->
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.align(Alignment.Center)
                                    .padding(MaterialTheme.spacings.default),
                        )
                    channel == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.medium),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            if (state.serverSide) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "server") {
                                    TaChannelServerSide(
                                        channel = channel,
                                        globalConfig = globalConfig,
                                        onEdit = { editing = it },
                                        onChange = viewModel::setOverwrite,
                                        onRefresh = viewModel::refresh,
                                    )
                                }
                                return@LazyVerticalGrid
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                                Column {
                                    if (channel.bannerUrl != null) {
                                        TaImage(
                                            path = channel.bannerUrl,
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .aspectRatio(6.2f)
                                                    .padding(
                                                        bottom = MaterialTheme.spacings.medium
                                                    ),
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TaAvatar(path = channel.thumbUrl, size = 64.dp)
                                        Spacer(Modifier.width(MaterialTheme.spacings.medium))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = channel.name,
                                                style = MaterialTheme.typography.titleLarge,
                                            )
                                            channel.subs?.let { subs ->
                                                Text(
                                                    text =
                                                        stringResource(
                                                            CoreR.string.ta_subscribers,
                                                            formatCount(subs),
                                                        ),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.padding(top = MaterialTheme.spacings.small))
                                    if (channel.subscribed) {
                                        FilledTonalButton(
                                            onClick = { viewModel.setSubscribed(false) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(stringResource(CoreR.string.ta_subscribed))
                                        }
                                    } else {
                                        Button(
                                            onClick = { viewModel.setSubscribed(true) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(stringResource(CoreR.string.ta_subscribe))
                                        }
                                    }
                                    channel.description
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { description ->
                                            Text(
                                                text = rememberLinkedText(description),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines =
                                                    if (descriptionExpanded) Int.MAX_VALUE else 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier =
                                                    Modifier.padding(
                                                            top = MaterialTheme.spacings.small
                                                        )
                                                        .animateContentSize()
                                                        .clickable {
                                                            descriptionExpanded =
                                                                !descriptionExpanded
                                                        },
                                            )
                                        }
                                    val tabs = state.tabs
                                    if (tabs.size > 1) {
                                        Row(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                                            modifier =
                                                Modifier.padding(
                                                    top = MaterialTheme.spacings.small
                                                ),
                                        ) {
                                            tabs.forEach { tab ->
                                                FilterChip(
                                                    selected = state.tab == tab,
                                                    onClick = { viewModel.selectTab(tab) },
                                                    label = { Text(stringResource(tab.label())) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.tab == TaChannelTab.PLAYLISTS) {
                                items(playlists.items, key = { "p" + it.id }) { playlist ->
                                    TaPlaylistCard(
                                        playlist = playlist,
                                        onClick = { onPlaylistClick(playlist.id) },
                                    )
                                }
                                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                                    TaListFooter(
                                        state = playlists,
                                        onRetry = { viewModel.loadMore() },
                                        emptyText = stringResource(CoreR.string.ta_no_playlists),
                                    )
                                }
                            } else {
                                items(videos.items, key = { "v" + it.youtubeId }) { video ->
                                    TaVideoCard(
                                        video = video,
                                        onClick = { onVideoClick(video.youtubeId) },
                                        showChannel = false,
                                    )
                                }
                                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                                    TaListFooter(
                                        state = videos,
                                        onRetry = { viewModel.loadMore() },
                                        emptyText = stringResource(CoreR.string.ta_no_videos),
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    editing?.let { channelSetting ->
        TaSettingDialog(
            setting = channelSetting.setting,
            current = state.channel?.overwrites?.get(channelSetting.key),
            noneLabel = stringResource(CoreR.string.ta_use_global),
            onSave = { value ->
                viewModel.setOverwrite(channelSetting.key, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun TaChannelTab.label(): Int =
    when (this) {
        TaChannelTab.VIDEOS -> CoreR.string.ta_videos
        TaChannelTab.SHORTS -> CoreR.string.ta_shorts
        TaChannelTab.STREAMS -> CoreR.string.ta_streams
        TaChannelTab.PLAYLISTS -> CoreR.string.ta_playlists
    }

/**
 * A channel's own setting under [key], which replaces the global one that [setting] reads. Its
 * section is null when there is no global setting.
 */
private data class TaChannelSetting(val key: String, val setting: TaSetting)

private val channelSettings =
    listOf(
        TaChannelSetting(
            "download_format",
            TaSetting(
                "downloads",
                "format",
                CoreR.string.ta_set_format,
                TaEditor.FORMAT,
                noneLabel = CoreR.string.ta_ytdlp_default,
            ),
        ),
        TaChannelSetting(
            "autodelete_days",
            TaSetting(
                "downloads",
                "autodelete_days",
                CoreR.string.ta_set_autodelete_days,
                TaEditor.NUMBER,
                noneLabel = CoreR.string.ta_never,
                presets = listOf(1L, 3L, 7L, 14L, 30L, 90L),
                unit = CoreR.string.ta_unit_days,
            ),
        ),
        TaChannelSetting(
            "integrate_sponsorblock",
            TaSetting(
                "downloads",
                "integrate_sponsorblock",
                CoreR.string.ta_set_integrate_sponsorblock,
                TaEditor.CHOICE,
                options =
                    listOf(
                        null to CoreR.string.ta_use_global,
                        "true" to CoreR.string.ta_on,
                        "false" to CoreR.string.ta_off,
                    ),
            ),
        ),
        TaChannelSetting(
            "subscriptions_channel_size",
            TaSetting(
                "subscriptions",
                "channel_size",
                CoreR.string.ta_set_channel_size,
                TaEditor.NUMBER,
                presets = listOf(0L, 5L, 10L, 25L, 50L, 100L, 250L, 500L),
            ),
        ),
        TaChannelSetting(
            "subscriptions_shorts_channel_size",
            TaSetting(
                "subscriptions",
                "shorts_channel_size",
                CoreR.string.ta_set_shorts_channel_size,
                TaEditor.NUMBER,
                presets = listOf(0L, 5L, 10L, 25L, 50L, 100L, 250L, 500L),
            ),
        ),
        TaChannelSetting(
            "subscriptions_live_channel_size",
            TaSetting(
                "subscriptions",
                "live_channel_size",
                CoreR.string.ta_set_live_channel_size,
                TaEditor.NUMBER,
                presets = listOf(0L, 5L, 10L, 25L, 50L, 100L, 250L, 500L),
            ),
        ),
    )

/** The channel's own settings, as on the channel's About page on the web. */
@Composable
private fun TaChannelSettings(
    overwrites: JsonObject,
    globalConfig: JsonObject?,
    onEdit: (TaChannelSetting) -> Unit,
    onChange: (key: String, value: JsonElement) -> Unit,
) {
    Column {
        Text(
            text = stringResource(CoreR.string.ta_channel_settings_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        channelSettings.forEach { channelSetting ->
            val own = overwrites[channelSetting.key]?.takeIf { it !is JsonNull }
            val global = globalConfig?.let { channelSetting.setting.read(it) }
            TaSettingRow(
                label = stringResource(channelSetting.setting.label),
                value =
                    if (own != null) {
                        taSettingValueText(channelSetting.setting, own, null)
                    } else {
                        stringResource(
                            CoreR.string.ta_global_value,
                            taSettingValueText(
                                channelSetting.setting,
                                global,
                                channelSetting.setting.noneLabel?.let { stringResource(it) },
                            ),
                        )
                    },
                onClick = { onEdit(channelSetting) },
            )
        }
        val indexPlaylists =
            (overwrites["index_playlists"] as? JsonPrimitive)?.booleanOrNull == true
        TaSettingRow(
            label = stringResource(CoreR.string.ta_index_playlists),
            value = stringResource(CoreR.string.ta_index_playlists_summary),
            onClick = {
                onChange("index_playlists", if (indexPlaylists) JsonNull else JsonPrimitive(true))
            },
            trailing = {
                Switch(
                    checked = indexPlaylists,
                    onCheckedChange = { checked ->
                        onChange("index_playlists", if (checked) JsonPrimitive(true) else JsonNull)
                    },
                )
            },
        )
    }
}

/** The channel's side on the server: its details, refreshing it, and its own settings. */
@Composable
private fun TaChannelServerSide(
    channel: TaChannel,
    globalConfig: JsonObject?,
    onEdit: (TaChannelSetting) -> Unit,
    onChange: (key: String, value: JsonElement) -> Unit,
    onRefresh: (withVideos: Boolean) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaAvatar(path = channel.thumbUrl, size = 48.dp)
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Text(text = channel.name, style = MaterialTheme.typography.titleLarge)
        }
        TaInfoHeading(stringResource(CoreR.string.ta_info_details))
        TaInfoRow(stringResource(CoreR.string.ta_info_channel_id), channel.id)
        TaInfoRow(
            stringResource(CoreR.string.ta_subscribed),
            stringResource(if (channel.subscribed) CoreR.string.ta_yes else CoreR.string.ta_no),
        )
        channel.active?.let { active ->
            TaInfoRow(
                stringResource(CoreR.string.ta_info_on_youtube),
                stringResource(
                    if (active) CoreR.string.ta_info_available else CoreR.string.ta_info_removed
                ),
            )
        }
        formatTaDate(channel.lastRefresh)?.let {
            TaInfoRow(stringResource(CoreR.string.ta_info_last_refreshed), it)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        ) {
            OutlinedButton(onClick = { onRefresh(false) }) {
                Text(stringResource(CoreR.string.ta_refresh))
            }
            OutlinedButton(onClick = { onRefresh(true) }) {
                Text(stringResource(CoreR.string.ta_refresh_with_videos))
            }
        }
        TaInfoHeading(stringResource(CoreR.string.ta_channel_settings))
        TaChannelSettings(
            overwrites = channel.overwrites ?: JsonObject(emptyMap()),
            globalConfig = globalConfig,
            onEdit = onEdit,
            onChange = onChange,
        )
    }
}
