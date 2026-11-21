package dev.jdtech.jellyfin.presentation.jrwi

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemEvent
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiMessages
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiServerViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiSettingsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowsViewModel
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TaSectionTitle
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import dev.jdtech.jellyfin.presentation.tube.fullDate
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged
import dev.jdtech.jellyfin.presentation.tube.rememberSeparateRowScroll
import dev.jdtech.jellyfin.presentation.tube.startJrwiAudio
import dev.jdtech.jellyfin.presentation.tube.startTaPlayback
import dev.jdtech.jellyfin.utils.ObserveAsEvents

/** Address of the JRWI archiver, for the images it serves. */
val LocalJrwiBaseUrl = staticCompositionLocalOf<String?> { null }

/** Full address of a path the archiver gives, such as /files/posts/300/thumbnail.webp. */
@Composable
private fun jrwiUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = LocalJrwiBaseUrl.current ?: return null
    return base + (if (path.startsWith("/")) path else "/$path")
}

@Composable
private fun JrwiImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = jrwiUrl(path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(JrwiColors.Picture),
    )
}

private val TOP_BAR_INSETS
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

/** Turns a message from a JRWI page into text. */
private fun jrwiMessageText(context: Context, message: String): String =
    when (message) {
        JrwiMessages.SAVED -> context.getString(CoreR.string.jrwi_saved)
        JrwiMessages.CHECK_STARTED -> context.getString(CoreR.string.jrwi_check_started)
        JrwiMessages.CHECK_RUNNING -> context.getString(CoreR.string.jrwi_check_running)
        JrwiMessages.NOT_IN_JELLYFIN -> context.getString(CoreR.string.jrwi_not_in_jellyfin)
        else -> message
    }

/** Opens an address in the browser, or whatever app handles it. */
private fun openUrl(context: Context, url: String?) {
    if (url == null) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

// The JRWI tab

/** The JRWI tab: what's new, then the shows, as the Channels tab lists channels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiShowsScreen(
    onShowClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
    onServerClick: () -> Unit,
    onSetUp: () -> Unit,
    viewModel: JrwiShowsViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        JrwiScaffold(
            modifier =
                Modifier.fillMaxSize()
                    .recalculateWindowInsetsUnlessPaged()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.jrwi_tab)) },
                    actions = {
                        if (state.configured) {
                            IconButton(onClick = onServerClick) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_server),
                                    contentDescription = stringResource(CoreR.string.jrwi_server),
                                )
                            }
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    scrollBehavior = scrollBehavior,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            if (!state.configured) {
                Column(
                    modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(CoreR.string.jrwi_not_set_up),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    Button(shape = JrwiShape, onClick = onSetUp) {
                        Text(stringResource(CoreR.string.jrwi_set_up))
                    }
                }
                return@JrwiScaffold
            }
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.load(force = true) },
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.error?.let { error ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(text = error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.latest.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "latest") {
                            Column {
                                TaSectionTitle(stringResource(CoreR.string.jrwi_latest))
                                val rowState = rememberLazyListState()
                                // Scrolls apart from the main pages, as Continue watching does
                                LazyRow(
                                    state = rowState,
                                    userScrollEnabled = false,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.medium),
                                    modifier = rememberSeparateRowScroll(rowState),
                                ) {
                                    items(state.latest, key = { it.id }) { item ->
                                        JrwiItemCard(
                                            item = item,
                                            onClick = { onItemClick(item.id) },
                                            modifier = Modifier.width(240.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.shows.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "shows") {
                            TaSectionTitle(stringResource(CoreR.string.jrwi_shows))
                        }
                    }
                    items(state.shows, key = { it.id }) { show ->
                        JrwiShowCard(
                            show = show,
                            picture = state.showPictures[show.id],
                            onClick = { onShowClick(show.id) },
                        )
                    }
                }
            }
        }
    }
}

/** A show's tile. [picture] stands in for the show's own art when it has none. */
@Composable
private fun JrwiShowCard(show: JrwiShow, picture: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.clip(JrwiShape).clickable(onClick = onClick)) {
        JrwiImage(
            path = show.poster ?: show.banner ?: picture,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(JrwiShape),
        )
        Text(
            text = show.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        Text(
            text =
                pluralStringResource(CoreR.plurals.jrwi_item_count, show.itemCount, show.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An item with its picture on top, as in a row of what's new. Its text always takes the same lines,
 * so every card is as tall as the others and the shows under the row stay put.
 */
@Composable
private fun JrwiItemCard(item: JrwiItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(JrwiShape).clickable(onClick = onClick)) {
        JrwiThumbnail(item = item, modifier = Modifier.fillMaxWidth())
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        Text(
            text = itemDetails(item).orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An item's picture with its length, and whether it has a video, audio or both. With [marks], a
 * check when it was watched and a bar when it was partly played, as in the TubeArchivist lists.
 */
@Composable
private fun JrwiThumbnail(item: JrwiItem, modifier: Modifier = Modifier, marks: Boolean = true) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(JrwiShape)) {
        JrwiImage(path = item.thumbnail, modifier = Modifier.fillMaxSize())
        if (marks && item.progress?.finished == true) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = stringResource(CoreR.string.jrwi_watched),
                tint = JrwiColors.AccentInk,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(JrwiColors.Accent)
                        .padding(4.dp)
                        .size(14.dp),
            )
        }
        val played = item.playedFraction
        if (marks && played != null) {
            val bar = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
            Box(modifier = bar.background(Color.Black.copy(alpha = 0.4f))) {
                Box(Modifier.fillMaxWidth(played).height(4.dp).background(JrwiColors.Accent))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            if (item.video != null) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_film),
                    contentDescription = stringResource(CoreR.string.jrwi_video),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (item.audio != null) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_headphones),
                    contentDescription = stringResource(CoreR.string.jrwi_audio),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            item.duration
                ?.takeIf { it > 0 }
                ?.let {
                    Text(
                        text = formatDuration(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
        }
    }
}

/** Such as "Episode 136 · 7 Nov 2026". */
@Composable
private fun itemDetails(item: JrwiItem): String? {
    val parts =
        listOfNotNull(
            item.episode
                ?.takeIf { item.tab == "episodes" }
                ?.let { stringResource(CoreR.string.jrwi_episode, it) },
            fullDate(item.date),
        )
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

// A show

/** A show, with a tab for each kind of post it has, such as episodes and aftershows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiShowScreen(
    showId: String,
    onItemClick: (Long) -> Unit,
    navigateBack: () -> Unit,
    viewModel: JrwiShowViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        LaunchedEffect(showId) { viewModel.load(showId) }
        val show by viewModel.show.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()
        var tabIndex by rememberSaveable { mutableIntStateOf(0) }
        val tabs = show?.tabs.orEmpty()

        JrwiScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(show?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                if (tabs.size > 1) {
                    // Under the header in its purple, as the site's menu, with the yellow line
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tabIndex.coerceIn(0, tabs.lastIndex),
                        edgePadding = MaterialTheme.spacings.medium,
                        containerColor = JrwiColors.Header,
                        contentColor = JrwiColors.Text,
                        divider = {},
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == tabIndex,
                                onClick = { tabIndex = index },
                                text = { Text(tab.name, fontWeight = FontWeight.Bold) },
                                selectedContentColor = JrwiColors.Text,
                                unselectedContentColor = JrwiColors.Muted,
                            )
                        }
                    }
                }
                val tab = tabs.getOrNull(tabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
                when {
                    tab != null -> JrwiTabList(viewModel, tab.id, onItemClick)
                    error != null ->
                        Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                        )
                    show != null ->
                        Text(
                            text = stringResource(CoreR.string.jrwi_nothing_here),
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                        )
                    else ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                }
            }
        }
    }
}

@Composable
private fun JrwiTabList(viewModel: JrwiShowViewModel, tab: String, onItemClick: (Long) -> Unit) {
    val flow = remember(tab) { viewModel.items(tab) }
    val list by flow.collectAsStateWithLifecycle()
    LaunchedEffect(tab) { if (list.page == 0) viewModel.loadMore(tab) }
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list.items, key = { it.id }) { item ->
            JrwiItemRow(item = item, onClick = { onItemClick(item.id) })
        }
        list.error?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        if (list.hasMore && list.error == null) {
            item {
                // Shown at the end of the list, which loads the next page
                LaunchedEffect(list.items.size) { viewModel.loadMore(tab) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (list.items.isEmpty() && list.error == null) {
            item { Text(stringResource(CoreR.string.jrwi_nothing_here)) }
        }
    }
}

/** An item with its picture at the side, as in a show's list. */
@Composable
private fun JrwiItemRow(item: JrwiItem, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxWidth().clip(JrwiShape).clickable(onClick = onClick),
    ) {
        JrwiThumbnail(item = item, modifier = Modifier.width(160.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            itemDetails(item)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.locked) {
                Text(
                    text = stringResource(CoreR.string.jrwi_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// One item

/**
 * One post: its video and audio behind one switch and one Play button, then its text, pictures,
 * files, links and comments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiItemScreen(
    postId: Long,
    onOpenYoutube: (String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: JrwiItemViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        // Loaded again on coming back from the player, for the new position
        LifecycleResumeEffect(postId) {
            viewModel.load(postId)
            onPauseOrDispose {}
        }
        ObserveAsEvents(viewModel.events) { event ->
            when (event) {
                is JrwiItemEvent.PlayVideo ->
                    startTaPlayback(
                        context,
                        event.episodeId,
                        startFromBeginning = event.fromStart,
                        positionSeconds = event.positionSeconds,
                    )
                is JrwiItemEvent.PlayYoutube -> onOpenYoutube(event.videoId)
                is JrwiItemEvent.PlayAudio -> startJrwiAudio(context, event.postId, event.fromStart)
                is JrwiItemEvent.Message ->
                    Toast.makeText(context, jrwiMessageText(context, event.text), Toast.LENGTH_LONG)
                        .show()
            }
        }
        val item = state.item
        var menuOpen by remember { mutableStateOf(false) }
        // The video, unless there's only the audio
        var audioChosen by rememberSaveable(postId) { mutableStateOf<Boolean?>(null) }
        val playsAudio = audioChosen ?: (item?.video == null)

        JrwiScaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        if (item != null) {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painterResource(CoreR.drawable.ic_more_vertical),
                                    contentDescription = null,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (item.video != null || item.audio != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(CoreR.string.jrwi_play_from_start))
                                        },
                                        onClick = {
                                            menuOpen = false
                                            if (playsAudio) viewModel.playAudio(fromStart = true)
                                            else viewModel.playVideo(fromStart = true)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (state.finished)
                                                        CoreR.string.jrwi_mark_unwatched
                                                    else CoreR.string.jrwi_mark_watched
                                                )
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setWatched(!state.finished)
                                        },
                                    )
                                }
                                item.originalUrl?.let { url ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(CoreR.string.jrwi_open_original))
                                        },
                                        onClick = {
                                            menuOpen = false
                                            openUrl(context, url)
                                        },
                                    )
                                }
                            }
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            if (item == null) {
                Box(
                    Modifier.padding(innerPadding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val error = state.error
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator()
                }
                return@JrwiScaffold
            }
            val comments = state.comments
            val flatComments = remember(comments.items) { comments.items.flattenReplies() }
            LazyColumn(
                contentPadding = PaddingValues(bottom = MaterialTheme.spacings.large),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                item(key = "header") {
                    JrwiItemHeader(
                        item = item,
                        position = if (playsAudio) state.audioPosition else state.videoPosition,
                        finished = state.finished,
                        playsAudio = playsAudio,
                        lookingUp = state.lookingUp,
                        onChoose = { audioChosen = it },
                        onPlay = {
                            if (playsAudio) viewModel.playAudio() else viewModel.playVideo()
                        },
                    )
                }
                val body = item.bodyHtml?.takeIf { it.isNotBlank() }
                if (body != null || !item.bodyText.isNullOrBlank()) {
                    item(key = "body") {
                        JrwiHtmlText(
                            html = body,
                            text = item.bodyText.orEmpty(),
                            modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                        )
                    }
                }
                if (item.images.isNotEmpty()) {
                    item(key = "images") {
                        LazyRow(
                            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            items(item.images) { image ->
                                JrwiImage(
                                    path = image.url,
                                    contentScale = ContentScale.Fit,
                                    modifier =
                                        Modifier.height(220.dp)
                                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                            .clip(JrwiShape)
                                            .clickable {
                                                openUrl(context, viewModel.fileUrl(image.url))
                                            },
                                )
                            }
                        }
                    }
                }
                val files = (item.attachments + item.documents).distinctBy { it.url }
                if (files.isNotEmpty()) {
                    item(key = "files_title") {
                        JrwiSectionTitle(stringResource(CoreR.string.jrwi_files))
                    }
                    items(files, key = { "file:" + it.url }) { file ->
                        JrwiLinkRow(
                            icon = CoreR.drawable.ic_download,
                            text = file.name,
                            detail = file.size?.let { formatBytes(it) },
                            onClick = { openUrl(context, viewModel.fileUrl(file.url)) },
                        )
                    }
                }
                if (item.links.isNotEmpty()) {
                    item(key = "links_title") {
                        JrwiSectionTitle(stringResource(CoreR.string.jrwi_links))
                    }
                    items(item.links.distinctBy { it.url }, key = { "link:" + it.url }) { link ->
                        JrwiLinkRow(
                            icon = CoreR.drawable.ic_external_link,
                            text = link.text.ifBlank { link.url },
                            detail = link.url.takeIf { link.text.isNotBlank() },
                            onClick = { openUrl(context, link.url) },
                        )
                    }
                }
                item(key = "comments_title") {
                    JrwiSectionTitle(
                        stringResource(CoreR.string.jrwi_comments) +
                            (comments.total?.let { " ($it)" } ?: "")
                    )
                }
                items(flatComments, key = { "comment:" + it.comment.id }) { flat ->
                    JrwiCommentRow(comment = flat.comment, depth = flat.depth)
                }
                when {
                    comments.error != null ->
                        item(key = "comments_error") {
                            Text(
                                text = comments.error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                            )
                        }
                    comments.hasMore && comments.page > 0 ->
                        item(key = "more_comments") {
                            TextButton(
                                onClick = viewModel::loadMoreComments,
                                modifier =
                                    Modifier.padding(horizontal = MaterialTheme.spacings.small),
                            ) {
                                Text(stringResource(CoreR.string.jrwi_more_comments))
                            }
                        }
                    comments.items.isEmpty() && !comments.loading ->
                        item(key = "no_comments") {
                            Text(
                                text = stringResource(CoreR.string.jrwi_no_comments),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                            )
                        }
                }
            }
        }
    }
}

/**
 * The picture, title and details, and the switch between the video and the audio. [position] is in
 * the timeline of the version that's picked.
 */
@Composable
private fun JrwiItemHeader(
    item: JrwiItem,
    position: Double,
    finished: Boolean,
    playsAudio: Boolean,
    lookingUp: Boolean,
    onChoose: (audio: Boolean) -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        Box {
            // With its own bar, for the version that's picked
            JrwiThumbnail(item = item, modifier = Modifier.fillMaxWidth(), marks = false)
            val length =
                (if (playsAudio) item.audio?.duration else item.video?.duration) ?: item.duration
            if (!finished && position > 0 && length != null && length > 0) {
                LinearProgressIndicator(
                    progress = { (position / length).toFloat().coerceIn(0f, 1f) },
                    trackColor = JrwiBarTrack,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                )
            }
        }
        Text(text = item.title, style = MaterialTheme.typography.titleLarge)
        itemDetails(item)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.locked) {
            Text(
                text = stringResource(CoreR.string.jrwi_locked),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (item.video == null && item.audio == null) return@Column
        if (item.video != null && item.audio != null) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !playsAudio,
                    onClick = { onChoose(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = jrwiSwitchColors(),
                    icon = {
                        Icon(
                            painterResource(CoreR.drawable.ic_film),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(stringResource(CoreR.string.jrwi_video))
                }
                SegmentedButton(
                    selected = playsAudio,
                    onClick = { onChoose(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = jrwiSwitchColors(),
                    icon = {
                        Icon(
                            painterResource(CoreR.drawable.ic_headphones),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(stringResource(CoreR.string.jrwi_audio))
                }
            }
        }
        val resuming = !finished && position > 0
        Button(
            shape = JrwiShape,
            onClick = onPlay,
            enabled = !lookingUp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (lookingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    painterResource(CoreR.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacings.small))
                Text(
                    stringResource(
                        if (resuming) CoreR.string.jrwi_resume else CoreR.string.jrwi_play
                    )
                )
            }
        }
        when {
            finished ->
                Text(
                    text = stringResource(CoreR.string.jrwi_watched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            resuming -> {
                Text(
                    text = stringResource(CoreR.string.jrwi_resume_at, formatDuration(position)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!playsAudio && item.video?.isYoutube == true) {
            Text(
                text = stringResource(CoreR.string.jrwi_youtube_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A post's or comment's text, with its links and formatting. */
@Composable
private fun JrwiHtmlText(html: String?, text: String, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val content =
        remember(html, text, linkColor) {
            if (html == null) {
                AnnotatedString(text)
            } else {
                AnnotatedString.fromHtml(
                    html,
                    linkStyles =
                        TextLinkStyles(
                            style =
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                )
                        ),
                )
            }
        }
    Text(text = content, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@Composable
private fun JrwiSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.padding(
                start = MaterialTheme.spacings.medium,
                end = MaterialTheme.spacings.medium,
                top = MaterialTheme.spacings.large,
                bottom = MaterialTheme.spacings.small,
            ),
    )
}

@Composable
private fun JrwiLinkRow(icon: Int, text: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.medium,
                    vertical = MaterialTheme.spacings.small,
                ),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class FlatComment(val comment: JrwiComment, val depth: Int)

/** The comments with their replies under each, as deep as they go. */
private fun List<JrwiComment>.flattenReplies(depth: Int = 0): List<FlatComment> = flatMap {
    listOf(FlatComment(it, depth)) + it.replies.flattenReplies(depth + 1)
}

@Composable
private fun JrwiCommentRow(comment: JrwiComment, depth: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacings.medium + (depth.coerceAtMost(3) * 24).dp,
                    end = MaterialTheme.spacings.medium,
                    top = MaterialTheme.spacings.small,
                    bottom = MaterialTheme.spacings.small,
                ),
    ) {
        JrwiImage(
            path = comment.author.avatar,
            modifier = Modifier.size(if (depth == 0) 36.dp else 28.dp).clip(CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Text(
                    text = comment.author.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                (comment.dateText ?: fullDate(comment.date))?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            JrwiHtmlText(
                html = comment.bodyHtml?.takeIf { it.isNotBlank() },
                text = comment.bodyText,
            )
            comment.likeCount
                ?.takeIf { it > 0 }
                ?.let { likes ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(CoreR.drawable.ic_heart),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = likes.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

// The archiver

/** What the archiver is doing, a check now, and the posts it couldn't fully save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiServerScreen(navigateBack: () -> Unit, viewModel: JrwiServerViewModel = hiltViewModel()) {
    // In the archiver's own colours
    JrwiTheme {
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
        }
        ObserveAsEvents(viewModel.messages) { message ->
            Toast.makeText(context, jrwiMessageText(context, message), Toast.LENGTH_LONG).show()
        }
        JrwiScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.jrwi_server)) },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            LazyColumn(
                contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                state.error?.let { error ->
                    item { Text(text = error, color = MaterialTheme.colorScheme.error) }
                }
                state.status?.let { status ->
                    item {
                        val phase = listOfNotNull(status.phase, status.detail).joinToString(": ")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            // The site's dot: green while it checks
                            if (status.running) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(JrwiColors.Ok))
                            }
                            Text(
                                text =
                                    if (status.running) {
                                        stringResource(CoreR.string.jrwi_status_running, phase)
                                    } else {
                                        stringResource(CoreR.string.jrwi_status_idle)
                                    },
                                style = MaterialTheme.typography.titleMedium,
                                color = JrwiColors.Text,
                            )
                        }
                        val progress = status.progress
                        if (status.running && progress?.total != null && progress.total!! > 0) {
                            LinearProgressIndicator(
                                trackColor = JrwiBarTrack,
                                progress = {
                                    ((progress.done ?: 0).toFloat() / progress.total!!).coerceIn(
                                        0f,
                                        1f,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (status.loginOk == false) {
                        item {
                            Text(
                                text = stringResource(CoreR.string.jrwi_login_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val finished = fullDate(status.lastCheckFinished)
                            if (finished != null && status.lastResult != null) {
                                Text(
                                    stringResource(
                                        CoreR.string.jrwi_last_check,
                                        finished,
                                        status.lastResult.orEmpty(),
                                    )
                                )
                            }
                            Text(
                                text =
                                    status.nextCheck?.let {
                                        stringResource(
                                            CoreR.string.jrwi_next_check,
                                            fullDate(it) ?: it,
                                        )
                                    } ?: stringResource(CoreR.string.jrwi_next_check_manual)
                            )
                            Text(
                                stringResource(
                                    CoreR.string.jrwi_counts,
                                    status.counts.items,
                                    status.counts.archived,
                                    status.counts.locked,
                                )
                            )
                            val archived = status.disk.archiveBytes
                            val free = status.disk.freeBytes
                            if (archived != null && free != null) {
                                Text(
                                    stringResource(
                                        CoreR.string.jrwi_disk,
                                        formatBytes(archived),
                                        formatBytes(free),
                                    )
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        Button(shape = JrwiShape, onClick = { viewModel.check() }) {
                            Text(stringResource(CoreR.string.jrwi_check_now))
                        }
                        OutlinedButton(
                            shape = JrwiShape,
                            border = JrwiGhostBorder,
                            colors = jrwiGhostButtonColors(),
                            onClick = { viewModel.check(full = true) },
                        ) {
                            Text(stringResource(CoreR.string.jrwi_full_check))
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(CoreR.string.jrwi_problems),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = MaterialTheme.spacings.medium),
                    )
                }
                if (state.problems.isEmpty() && state.status != null) {
                    item { Text(stringResource(CoreR.string.jrwi_no_problems)) }
                }
                items(state.problems, key = { it.postId }) { problem ->
                    Column {
                        Text(text = problem.title, style = MaterialTheme.typography.bodyLarge)
                        problem.problems.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = MaterialTheme.spacings.small)
                        )
                    }
                }
            }
        }
    }
}

// Settings

/** The archiver's address and API token. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiSettingsScreen(
    navigateBack: () -> Unit,
    viewModel: JrwiSettingsViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        var showToken by rememberSaveable { mutableStateOf(false) }
        ObserveAsEvents(viewModel.messages) { message ->
            Toast.makeText(context, jrwiMessageText(context, message), Toast.LENGTH_SHORT).show()
        }
        JrwiScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.jrwi_archiver)) },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier.padding(innerPadding)
                        .fillMaxSize()
                        .padding(MaterialTheme.spacings.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                Text(
                    text = stringResource(CoreR.string.jrwi_connection_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text(stringResource(CoreR.string.ta_server_address)) },
                    placeholder = { Text("http://192.168.1.10:8090") },
                    singleLine = true,
                    shape = JrwiShape,
                    colors = jrwiFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text(stringResource(CoreR.string.ta_api_token)) },
                    singleLine = true,
                    shape = JrwiShape,
                    colors = jrwiFieldColors(),
                    visualTransformation =
                        if (showToken) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (showToken) CoreR.drawable.ic_eye_off
                                        else CoreR.drawable.ic_eye
                                    ),
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text(stringResource(CoreR.string.jrwi_api_token_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                if (state.connected && state.error == null) {
                    Text(
                        text =
                            state.version?.let {
                                stringResource(CoreR.string.jrwi_connected_version, it)
                            } ?: stringResource(CoreR.string.ta_connected),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    Button(
                        shape = JrwiShape,
                        onClick = { viewModel.connect() },
                        enabled =
                            !state.checking && state.url.isNotBlank() && state.token.isNotBlank(),
                    ) {
                        if (state.checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(CoreR.string.ta_connect))
                        }
                    }
                    if (state.connected) {
                        OutlinedButton(
                            shape = JrwiShape,
                            border = JrwiGhostBorder,
                            colors = jrwiGhostButtonColors(),
                            onClick = { viewModel.disconnect() },
                        ) {
                            Text(stringResource(CoreR.string.ta_disconnect))
                        }
                    }
                }
            }
        }
    }
}
