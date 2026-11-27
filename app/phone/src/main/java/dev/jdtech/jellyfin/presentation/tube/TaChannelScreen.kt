package dev.jdtech.jellyfin.presentation.tube

import android.content.Intent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelTab
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaVideoAction
import dev.jdtech.jellyfin.presentation.theme.ServerSideTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.isWideLayout
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannelTotals
import dev.jdtech.jellyfin.tubearchivist.TaVideo
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
    val wide = isWideLayout()
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

    // The channel's page and its settings on the server, to swipe between
    val scope = rememberCoroutineScope()
    val sides = rememberSidePagerState()
    LaunchedEffect(sides) {
        snapshotFlow { sides.onServerSide }.collect { viewModel.setServerSide(it) }
    }

    ServerSideTheme(pagerState = sides) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    // The name is already shown on the page, so the switch stands alone
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        TaSideSwitch(
                            onServer = sides.onServerSide,
                            onToggle = { sides.switchSide(scope) },
                            localIcon = CoreR.drawable.ic_users,
                            localLabel = stringResource(CoreR.string.ta_side_channel),
                        )
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
                        TaSidePager(
                            state = sides,
                            phone = {
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
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                                        Column {
                                            if (channel.bannerUrl != null) {
                                                TaImage(
                                                    path = channel.bannerUrl,
                                                    modifier =
                                                        Modifier.padding(
                                                                bottom =
                                                                    MaterialTheme.spacings.medium
                                                            )
                                                            .bleed(MaterialTheme.spacings.medium)
                                                            .fillMaxWidth()
                                                            .aspectRatio(
                                                                if (wide) WIDE_BANNER_ASPECT_RATIO
                                                                else BANNER_ASPECT_RATIO
                                                            ),
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TaAvatar(path = channel.thumbUrl, size = 64.dp)
                                                Spacer(
                                                    Modifier.width(MaterialTheme.spacings.medium)
                                                )
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
                                                            style =
                                                                MaterialTheme.typography.bodyMedium,
                                                            color =
                                                                MaterialTheme.colorScheme
                                                                    .onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                            state.totals?.let { totals ->
                                                TaChannelTotalsRow(
                                                    totals = totals,
                                                    metricSizes = state.metricSizes,
                                                    modifier =
                                                        Modifier.padding(
                                                            top = MaterialTheme.spacings.small
                                                        ),
                                                )
                                            }
                                            Spacer(
                                                Modifier.padding(top = MaterialTheme.spacings.small)
                                            )
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
                                                            if (descriptionExpanded) Int.MAX_VALUE
                                                            else 3,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier =
                                                            Modifier.padding(
                                                                    top =
                                                                        MaterialTheme.spacings.small
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
                                                        Arrangement.spacedBy(
                                                            MaterialTheme.spacings.small
                                                        ),
                                                    modifier =
                                                        Modifier.padding(
                                                            top = MaterialTheme.spacings.small
                                                        ),
                                                ) {
                                                    tabs.forEach { tab ->
                                                        FilterChip(
                                                            selected = state.tab == tab,
                                                            onClick = { viewModel.selectTab(tab) },
                                                            label = {
                                                                Text(stringResource(tab.label()))
                                                            },
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
                                                emptyText =
                                                    stringResource(CoreR.string.ta_no_playlists),
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
                                                emptyText =
                                                    stringResource(CoreR.string.ta_no_videos),
                                            )
                                        }
                                    }
                                }
                            },
                            server = {
                                TaChannelServerPage(
                                    channel = channel,
                                    globalConfig = globalConfig,
                                    viewModel = viewModel,
                                    onEdit = { editing = it },
                                )
                            },
                        )
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

/**
 * How wide the banner is for its height. YouTube's strip is about 6.2 times as wide as it's tall,
 * so this shows it bigger, cutting a little off its ends.
 */
private const val BANNER_ASPECT_RATIO = 4.5f

// Sideways the banner gets flatter, so it doesn't fill the screen
private const val WIDE_BANNER_ASPECT_RATIO = 7f

/**
 * Reaches past the list's [padding] on the sides and the top, so the banner runs from edge to edge
 * right under the top bar.
 */
private fun Modifier.bleed(padding: Dp): Modifier = layout { measurable, constraints ->
    val sides = padding.roundToPx()
    val width = constraints.maxWidth + sides * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, (placeable.height - sides).coerceAtLeast(0)) {
        placeable.place(-sides, -sides)
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

/**
 * The channel's own settings, as on the channel's About page on the web, each a tile that opens its
 * editor. A value the channel sets itself is in the accent; one it takes from the global settings
 * is muted.
 */
@Composable
private fun TaChannelSettings(
    overwrites: JsonObject,
    globalConfig: JsonObject?,
    onEdit: (TaChannelSetting) -> Unit,
    onChange: (key: String, value: JsonElement) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Text(
            text = stringResource(CoreR.string.ta_channel_settings_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        channelSettings.forEach { channelSetting ->
            val own = overwrites[channelSetting.key]?.takeIf { it !is JsonNull }
            val global = globalConfig?.let { channelSetting.setting.read(it) }
            TaChannelSettingTile(
                label = stringResource(channelSetting.setting.label),
                own = own != null,
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
        TaChannelSettingTile(
            label = stringResource(CoreR.string.ta_index_playlists),
            own = false,
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

/** One of the channel's settings, as a tile: its name, its value under it, and a chevron. */
@Composable
private fun TaChannelSettingTile(
    label: String,
    value: String,
    own: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val content: @Composable RowScope.() -> Unit = {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (own) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (trailing != null) {
        TaButtonTile(onClick = onClick, trailing = trailing, content = content)
    } else {
        TaButtonTile(onClick = onClick, content = content)
    }
}

/**
 * The channel's side on the server: its details and settings, then all its videos, which can be
 * picked to reindex, download again or delete.
 */
@Composable
private fun TaChannelServerPage(
    channel: TaChannel,
    globalConfig: JsonObject?,
    viewModel: TaChannelViewModel,
    onEdit: (TaChannelSetting) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videos by viewModel.serverVideos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf<TaVideoAction?>(null) }

    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { viewModel.loadMoreServerVideos() }
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = MaterialTheme.spacings.medium,
                    top = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    // Room for the bar with the actions, so the last videos can be reached
                    bottom =
                        if (selected.isEmpty()) MaterialTheme.spacings.medium
                        else 160.dp + navigationBarHeight,
                ),
        ) {
            item(key = "server") {
                TaChannelServerSide(
                    channel = channel,
                    totals = state.totals,
                    metricSizes = state.metricSizes,
                    globalConfig = globalConfig,
                    onEdit = onEdit,
                    onChange = viewModel::setOverwrite,
                    onRefresh = viewModel::refresh,
                )
            }
            item(key = "videos_heading") {
                Column {
                    TaSectionHeading(
                        listOfNotNull(
                                stringResource(CoreR.string.ta_channel_videos),
                                videos.total?.toString(),
                            )
                            .joinToString(" · ")
                    )
                    Text(
                        text = stringResource(CoreR.string.ta_channel_videos_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (videos.items.isNotEmpty()) {
                        val allSelected = videos.items.all { it.youtubeId in selected }
                        TextButton(onClick = viewModel::toggleSelectAll) {
                            Text(
                                stringResource(
                                    if (allSelected) CoreR.string.ta_select_none
                                    else CoreR.string.ta_select_all
                                )
                            )
                        }
                    }
                }
            }
            items(videos.items, key = { "video" + it.youtubeId }) { video ->
                TaSelectableVideoRow(
                    video = video,
                    selected = video.youtubeId in selected,
                    onToggle = { viewModel.toggleSelected(video.youtubeId) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "videos_footer") {
                TaListFooter(
                    state = videos,
                    onRetry = { viewModel.loadMoreServerVideos() },
                    emptyText = stringResource(CoreR.string.ta_no_videos),
                )
            }
        }

        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
                // Clear of the phone's own navigation bar
                modifier =
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(MaterialTheme.spacings.medium),
            ) {
                Column(modifier = Modifier.padding(MaterialTheme.spacings.medium)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                pluralStringResource(
                                    CoreR.plurals.ta_selected_count,
                                    selected.size,
                                    selected.size,
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearSelection) {
                            Text(stringResource(CoreR.string.cancel))
                        }
                    }
                    TaVideoActionButtons(
                        onAction = { confirming = it },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    confirming?.let { action ->
        TaVideoActionDialog(
            action = action,
            count = selected.size,
            onConfirm = { ignore ->
                confirming = null
                viewModel.runOnSelected(action, ignore)
            },
            onDismiss = { confirming = null },
        )
    }
}

/** A video on the channel's server side, with a box to pick it. Tapping anywhere picks it. */
@Composable
private fun TaSelectableVideoRow(
    video: TaVideo,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        TaVideoThumbnail(video = video, modifier = Modifier.width(112.dp))
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Column(modifier = Modifier.weight(1f).padding(end = MaterialTheme.spacings.small)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details =
                listOfNotNull(
                    formatTaDate(video.published),
                    video.mediaSize?.let { formatBytes(it) },
                )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TaChannelServerSide(
    channel: TaChannel,
    totals: TaChannelTotals?,
    metricSizes: Boolean,
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
        totals?.let {
            TaInfoRow(stringResource(CoreR.string.ta_info_videos), formatNumber(it.count))
            TaInfoRow(stringResource(CoreR.string.ta_stats_playtime), formatPlaytime(it.duration))
            TaInfoRow(
                stringResource(CoreR.string.ta_stats_storage),
                formatSize(it.mediaSize, metricSizes),
            )
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
        // As a video's page has under its actions
        val context = LocalContext.current
        OutlinedButton(
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
            onClick = {
                val url = "https://www.youtube.com/channel/${channel.id}"
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, url, Toast.LENGTH_LONG).show()
                }
            },
        ) {
            Text(stringResource(CoreR.string.ta_open_on_youtube))
        }
        TaSectionHeading(stringResource(CoreR.string.ta_channel_settings))
        TaChannelSettings(
            overwrites = channel.overwrites ?: JsonObject(emptyMap()),
            globalConfig = globalConfig,
            onEdit = onEdit,
            onChange = onChange,
        )
    }
}

/**
 * How many videos the channel has on the server, how long they play for and the space they take, as
 * at the top of the channel's page on the web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaChannelTotalsRow(
    totals: TaChannelTotals,
    metricSizes: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = modifier,
    ) {
        TaTotal(
            icon = CoreR.drawable.ic_film,
            label = stringResource(CoreR.string.ta_info_videos),
            value =
                pluralStringResource(
                    CoreR.plurals.ta_video_count,
                    totals.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    formatNumber(totals.count),
                ),
        )
        TaTotal(
            icon = CoreR.drawable.ic_clock,
            label = stringResource(CoreR.string.ta_stats_playtime),
            value = formatPlaytime(totals.duration),
        )
        TaTotal(
            icon = CoreR.drawable.ic_database,
            label = stringResource(CoreR.string.ta_stats_storage),
            value = formatSize(totals.mediaSize, metricSizes),
        )
    }
}

@Composable
private fun TaTotal(@DrawableRes icon: Int, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
