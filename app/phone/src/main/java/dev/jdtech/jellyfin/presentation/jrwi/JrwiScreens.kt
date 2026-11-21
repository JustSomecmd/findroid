package dev.jdtech.jellyfin.presentation.jrwi

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemEvent
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiMessages
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiServerViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiSettingsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowPage
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.pages
import dev.jdtech.jellyfin.jrwi.JrwiBadge
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiContinue
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.presentation.audio.AudioViewModel
import dev.jdtech.jellyfin.presentation.components.pageSwipes
import dev.jdtech.jellyfin.presentation.components.rememberPageSwiper
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TaSectionTitle
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import dev.jdtech.jellyfin.presentation.tube.fullDate
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged
import dev.jdtech.jellyfin.presentation.tube.rememberSeparateRowScroll
import dev.jdtech.jellyfin.presentation.tube.startTaPlayback
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Address of the JRWI archiver, for the images it serves. */
val LocalJrwiBaseUrl = staticCompositionLocalOf<String?> { null }

/** Full address of a path the archiver gives, such as /files/posts/300/thumbnail.webp. */
@Composable
internal fun jrwiUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    // Including a picture kept on the phone with a download
    if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file:")) {
        return path
    }
    val base = LocalJrwiBaseUrl.current ?: return null
    return base + (if (path.startsWith("/")) path else "/$path")
}

@Composable
internal fun JrwiImage(
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
        JrwiMessages.NOT_SAVED -> context.getString(CoreR.string.jrwi_not_saved)
        else -> message
    }

/** Opens an address in the browser, or whatever app handles it. */
internal fun openUrl(context: Context, url: String?) {
    if (url == null) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

// The JRWI tab

/**
 * The JRWI tab: what was started and not finished, what's new, then the shows, as the Channels tab
 * lists channels. The archiver's own page is a purple [sheet] over it, which a swipe up from the
 * bottom bar or a double tap on the tab brings out, and which goes away once the tab is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiShowsScreen(
    onShowClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
    onSetUp: () -> Unit,
    sheet: JrwiSheetState,
    viewModel: JrwiShowsViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
        }
        DisposableEffect(sheet) { onDispose { sheet.closeNow() } }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Box(modifier = Modifier.fillMaxSize().recalculateWindowInsetsUnlessPaged()) {
            JrwiScaffold(
                modifier =
                    Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(CoreR.string.jrwi_tab)) },
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
                        if (state.continueWatching.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                                JrwiItemRow(
                                    title = stringResource(CoreR.string.jrwi_continue_watching)
                                ) {
                                    items(
                                        state.continueWatching,
                                        key = { "continue:" + it.item.id },
                                    ) { entry ->
                                        JrwiContinueCard(
                                            entry = entry,
                                            // On the version it was played in
                                            onClick = {
                                                onItemClick(entry.progress.postId ?: entry.item.id)
                                            },
                                            modifier = Modifier.width(240.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (state.latest.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "latest") {
                                JrwiItemRow(title = stringResource(CoreR.string.jrwi_latest)) {
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
            if (state.configured) JrwiServerSheet(sheet = sheet)
        }
    }
}

/** A titled row of cards, which scrolls apart from the main pages, as Continue watching does. */
@Composable
private fun JrwiItemRow(title: String, content: LazyListScope.() -> Unit) {
    Column {
        TaSectionTitle(title)
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = rememberSeparateRowScroll(rowState),
            content = content,
        )
    }
}

/**
 * An episode in Continue watching: its picture with how far it got and the version it was played
 * in, as the web app shows it, then its title and the minutes left.
 */
@Composable
private fun JrwiContinueCard(entry: JrwiContinue, onClick: () -> Unit, modifier: Modifier) {
    val item = entry.item
    val progress = entry.progress
    val audio = progress.postId != null && progress.postId == item.audio?.postId
    val length =
        progress.duration
            ?: (if (audio) item.audio?.duration else item.video?.duration)
            ?: item.duration
    Column(modifier = modifier.clip(JrwiShape).clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(JrwiShape)) {
            JrwiImage(path = item.thumbnail, modifier = Modifier.fillMaxSize())
            Icon(
                painter =
                    painterResource(
                        if (audio) CoreR.drawable.ic_headphones else CoreR.drawable.ic_film
                    ),
                contentDescription =
                    stringResource(if (audio) CoreR.string.jrwi_audio else CoreR.string.jrwi_video),
                tint = JrwiColors.AccentInk,
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(JrwiShape)
                        .background(JrwiColors.Accent)
                        .padding(4.dp)
                        .size(14.dp),
            )
            if (length != null && length > 0) {
                val played = (progress.position / length).toFloat().coerceIn(0f, 1f)
                val bar = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                Box(modifier = bar.background(Color.Black.copy(alpha = 0.4f))) {
                    Box(Modifier.fillMaxWidth(played).height(4.dp).background(JrwiColors.Accent))
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        val left =
            length
                ?.takeIf { it > progress.position }
                ?.let { ceil((it - progress.position) / 60).toInt() }
        Text(
            text =
                listOfNotNull(
                        left?.let { stringResource(CoreR.string.runtime_minutes_left, it) },
                        itemDetails(item),
                    )
                    .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A show's tile, with the show's picture, or the JRWI logo for a show without one of its own.
 * [picture] stands in on archivers older than 0.6.0, which give neither.
 */
@Composable
private fun JrwiShowCard(show: JrwiShow, picture: String?, onClick: () -> Unit) {
    val ownPicture = show.poster ?: show.banner
    // The logo is shown whole, as the web app does
    val logo = ownPicture == null && show.image != null
    Column(modifier = Modifier.clip(JrwiShape).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(JrwiShape)
                    .background(JrwiColors.Picture)
        ) {
            JrwiImage(
                path = show.image ?: ownPicture ?: picture,
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize()
                        .then(
                            if (logo) Modifier.padding(MaterialTheme.spacings.medium) else Modifier
                        ),
            )
        }
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

/** Such as "Episode 136 · 7 Nov 2026", or "Season 2, episode 1" after the first season. */
@Composable
private fun itemDetails(item: JrwiItem, otherShow: String? = null): String? {
    val episode = item.episode?.takeIf { "episodes" in item.ownTabs }
    val season = item.season ?: 1
    val number =
        when {
            episode == null -> null
            season > 1 -> stringResource(CoreR.string.jrwi_season_episode, season, episode)
            else -> stringResource(CoreR.string.jrwi_episode, episode)
        }
    val parts = listOfNotNull(otherShow, number, fullDate(item.date))
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
        // A tab of a show with more than one season gets a page per season
        val tabs = remember(show) { show?.pages().orEmpty() }
        // Swiped between like the main tabs, with the same feel
        val pages = rememberPagerState { tabs.size }
        val swiper = rememberPageSwiper(pages)
        val shownTab =
            (swiper.target ?: pages.currentPage).coerceIn(0, tabs.lastIndex.coerceAtLeast(0))

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
                        selectedTabIndex = shownTab,
                        edgePadding = MaterialTheme.spacings.medium,
                        containerColor = JrwiColors.Header,
                        contentColor = JrwiColors.Text,
                        divider = {},
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == shownTab,
                                onClick = { swiper.turnTo(index) },
                                text = { Text(pageTitle(tab), fontWeight = FontWeight.Bold) },
                                selectedContentColor = JrwiColors.Text,
                                unselectedContentColor = JrwiColors.Muted,
                            )
                        }
                    }
                }
                when {
                    tabs.isNotEmpty() ->
                        HorizontalPager(
                            state = pages,
                            modifier = Modifier.fillMaxSize().pageSwipes(swiper),
                            userScrollEnabled = false,
                            key = { page -> tabs[page].key },
                        ) { page ->
                            JrwiTabList(viewModel, showId, tabs[page], onItemClick)
                        }
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

/**
 * A page's name: its tab's, or for one season "Season 2: Empty Graves" and "Just Rolled With It:
 * Season 2", as on the archiver's web app.
 */
@Composable
private fun pageTitle(page: JrwiShowPage): String {
    val season = page.season ?: return page.tabName
    val name = page.seasonName?.takeIf { it.isNotBlank() }
    return when {
        page.tab != "episodes" -> stringResource(CoreR.string.jrwi_tab_season, page.tabName, season)
        name != null -> stringResource(CoreR.string.jrwi_season_named, season, name)
        else -> stringResource(CoreR.string.jrwi_season, season)
    }
}

@Composable
private fun JrwiTabList(
    viewModel: JrwiShowViewModel,
    showId: String,
    tab: JrwiShowPage,
    onItemClick: (Long) -> Unit,
) {
    val flow = remember(tab) { viewModel.items(tab) }
    val list by flow.collectAsStateWithLifecycle()
    val showNames by viewModel.showNames.collectAsStateWithLifecycle()
    LaunchedEffect(tab) { if (list.page == 0) viewModel.loadMore(tab) }
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list.items, key = { it.id }) { item ->
            JrwiItemRow(
                item = item,
                // Posts the site's tags place here from another show say which show they're from
                otherShow = showNames[item.show]?.takeIf { item.show != showId },
                onClick = { onItemClick(item.id) },
            )
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
private fun JrwiItemRow(item: JrwiItem, onClick: () -> Unit, otherShow: String? = null) {
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
            itemDetails(item, otherShow)?.let {
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
    /** Opens the page of the show the episode is from. */
    onShowClick: (showId: String) -> Unit,
    onOpenAudioPlayer: () -> Unit,
    navigateBack: () -> Unit,
    // Opened from the audio player to watch the video from where the audio was
    playVideoOnOpen: Boolean = false,
    viewModel: JrwiItemViewModel = hiltViewModel(),
    audioViewModel: AudioViewModel = hiltViewModel(),
    // The video downloads through Jellyfin, as the app's other downloads
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    // In the archiver's own colours
    JrwiTheme {
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val download by viewModel.download.collectAsStateWithLifecycle()
        val videoDownload by downloaderViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.jellyfinEpisode) {
            state.jellyfinEpisode?.let { downloaderViewModel.update(it) }
        }
        ObserveAsEvents(downloaderViewModel.events) { event ->
            when (event) {
                is DownloaderEvent.Successful,
                is DownloaderEvent.Deleted -> viewModel.reloadEpisode()
            }
        }
        // Opened from the audio player to watch the video, it plays once, not again on coming back
        var autoPlayed by rememberSaveable { mutableStateOf(false) }
        // Loaded again on coming back from the player, for the new position
        LifecycleResumeEffect(postId) {
            viewModel.load(postId, startVideo = playVideoOnOpen && !autoPlayed)
            autoPlayed = true
            onPauseOrDispose {}
        }
        val scope = rememberCoroutineScope()
        val audioPlayer = audioViewModel.player
        val audioState = audioPlayer.state.collectAsStateWithLifecycle()
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
                // In the app's own audio player, which opens with it
                is JrwiItemEvent.PlayAudio -> {
                    audioViewModel.player.playJrwi(event.postId, event.fromStart)
                    onOpenAudioPlayer()
                }
                is JrwiItemEvent.Message ->
                    Toast.makeText(context, jrwiMessageText(context, event.text), Toast.LENGTH_LONG)
                        .show()
            }
        }
        val item = state.item
        var menuOpen by remember { mutableStateOf(false) }
        // Where the audio player is in this episode, to the second, while it has it
        val livePosition by
            remember(item?.id) {
                derivedStateOf {
                    audioState.value
                        .takeIf { item != null && it.jrwiItem?.id == item.id }
                        ?.let { (it.positionMs / 1000).toDouble() }
                }
            }
        // The video, after the audio player hands this episode over to it, when it has it
        fun playVideo(fromStart: Boolean = false) {
            if (item != null && audioState.value.jrwiItem?.id == item.id) {
                scope.launch {
                    audioPlayer.closeForVideo()
                    viewModel.playVideo(fromStart, freshPosition = true)
                }
            } else {
                viewModel.playVideo(fromStart)
            }
        }
        // A post's pictures, full screen, after one is tapped
        var pictures by remember { mutableStateOf<JrwiPictures?>(null) }
        // A file shown in the file inspector, after it's tapped
        var inspected by remember { mutableStateOf<JrwiPostFile?>(null) }
        inspected?.let { file ->
            JrwiFileInspector(
                file = file,
                hasLocalCopy = remember(file) { viewModel.localCopy(file) != null },
                loadPreview = viewModel::preview,
                fileOnPhone = viewModel::fileOnPhone,
                fileUrl = viewModel::fileUrl,
                onClose = { inspected = null },
            )
        }
        pictures?.let { shown ->
            JrwiPictureViewer(
                pictures = shown,
                onClose = { pictures = null },
                onSave = { path -> saveJrwiPicture(context, viewModel.fileUrl(path), path) },
            )
        }
        val playsAudio = state.playsAudio
        // The picked version's own post, or the whole item when there's one version
        val post = state.content.post

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
                                (post?.originalUrl ?: item.originalUrl)?.let { url ->
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
            val comments = state.content.comments
            val flatComments = remember(comments.items) { comments.items.flattenReplies() }
            LazyColumn(
                contentPadding = PaddingValues(bottom = MaterialTheme.spacings.large),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                item(key = "header") {
                    JrwiItemHeader(
                        item = item,
                        position =
                            if (playsAudio) livePosition ?: state.audioPosition
                            else state.videoPosition,
                        finished = state.finished,
                        playsAudio = playsAudio,
                        onChoose = viewModel::choose,
                    )
                }
                // The show it's from, as a TubeArchivist video has its channel
                state.show?.let { show ->
                    item(key = "show") {
                        JrwiShowLink(show = show, onClick = { onShowClick(show.id) })
                    }
                }
                if (item.video != null || item.audio != null) {
                    item(key = "buttons") {
                        JrwiItemButtons(
                            item = item,
                            playsAudio = playsAudio,
                            position =
                                if (playsAudio) livePosition ?: state.audioPosition
                                else state.videoPosition,
                            finished = state.finished,
                            lookingUp = state.lookingUp,
                            onPlay = { fromStart ->
                                if (playsAudio) viewModel.playAudio(fromStart)
                                else playVideo(fromStart)
                            },
                            onToggleWatched = { viewModel.setWatched(!state.finished) },
                            audioDownload = download,
                            onDownloadAudio = viewModel::downloadAudio,
                            onRemoveAudio = viewModel::removeDownload,
                            episode = state.jellyfinEpisode,
                            videoDownload = videoDownload,
                            onDownloadVideo = { storageIndex, withFiles ->
                                state.jellyfinEpisode?.let { episode ->
                                    viewModel.keepVideo(withFiles)
                                    downloaderViewModel.onAction(
                                        DownloaderAction.Download(episode, storageIndex)
                                    )
                                }
                            },
                            onVideoAction = { action ->
                                when (action) {
                                    is DownloaderAction.Download -> viewModel.keepVideo()
                                    is DownloaderAction.DeleteDownload,
                                    is DownloaderAction.CancelDownload -> viewModel.forgetVideo()
                                    else -> {}
                                }
                                downloaderViewModel.onAction(action)
                            },
                            modifier =
                                Modifier.padding(
                                    start = MaterialTheme.spacings.medium,
                                    end = MaterialTheme.spacings.medium,
                                    bottom = MaterialTheme.spacings.medium,
                                ),
                        )
                    }
                }
                if (state.offline) {
                    item(key = "offline") {
                        Text(
                            text = stringResource(CoreR.string.jrwi_offline_copy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.padding(
                                    horizontal = MaterialTheme.spacings.medium,
                                    vertical = MaterialTheme.spacings.small,
                                ),
                        )
                    }
                }
                val error = state.content.error
                when {
                    post == null && error != null ->
                        item(key = "post_error") {
                            Text(
                                text = jrwiMessageText(context, error),
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                            )
                        }
                    post == null ->
                        item(key = "post_loading") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    else ->
                        jrwiPostContent(
                            post = post,
                            files = state.content.files,
                            onOpen = { openUrl(context, it) },
                            onPicture = { pictures = it },
                            onFile = { file ->
                                when {
                                    file.type == "youtube" -> file.youtubeId?.let(onOpenYoutube)
                                    file.type == "link" -> openUrl(context, file.source)
                                    // The episode's own video and audio play as Play does
                                    file.role == "video" -> playVideo()
                                    file.role == "audio" -> viewModel.playAudio()
                                    file.type == "image" ->
                                        viewModel.showFrom(file)?.let {
                                            pictures = JrwiPictures(listOf(it), 0)
                                        }
                                    // Other sound and video open in another app
                                    file.type == "audio" || file.type == "video" ->
                                        openUrl(context, viewModel.fileUrl(file.url))
                                    else -> inspected = file
                                }
                            },
                            fileUrl = viewModel::fileUrl,
                        )
                }
                item(key = "comments_title") {
                    JrwiSectionTitle(
                        stringResource(CoreR.string.jrwi_comments) +
                            (comments.total?.let { " ($it)" } ?: "")
                    )
                }
                items(flatComments, key = { "comment:" + it.comment.id }) { flat ->
                    JrwiCommentRow(
                        comment = flat.comment,
                        depth = flat.depth,
                        onOpen = { openUrl(context, it) },
                    )
                }
                when {
                    comments.error != null ->
                        item(key = "comments_error") {
                            Text(
                                text = jrwiMessageText(context, comments.error.orEmpty()),
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
 * A post's own text, pictures, files and links. [onOpen] opens an address in the browser, and
 * [fileUrl] gives an archived file's full address.
 */
private fun LazyListScope.jrwiPostContent(
    post: JrwiItem,
    files: List<JrwiPostFile>?,
    onOpen: (String?) -> Unit,
    onPicture: (JrwiPictures) -> Unit,
    onFile: (JrwiPostFile) -> Unit,
    fileUrl: (String?) -> String?,
) {
    // Keyed by post, so switching versions never mixes the two
    val id = post.id
    // The site took it down, such as a members-only episode once it's on YouTube (v0.15)
    val removed =
        post.removedFromSite ?: post.versions.firstOrNull { it.postId == id }?.removedFromSite
    if (removed != null) {
        item(key = "removed:$id") {
            Text(
                text =
                    stringResource(
                        CoreR.string.jrwi_removed_from_site,
                        fullDate(removed) ?: removed,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
            )
        }
    }
    val body = post.bodyHtml?.takeIf { it.isNotBlank() }
    if (body != null || !post.bodyText.isNullOrBlank()) {
        item(key = "body:$id") {
            JrwiHtmlText(
                html = body,
                text = post.bodyText.orEmpty(),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
            )
        }
    }
    if (post.tags.isNotEmpty()) {
        item(key = "tags:$id") { JrwiTags(post.tags) }
    }
    if (post.images.isNotEmpty()) {
        item(key = "images:$id") {
            LazyRow(
                contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                itemsIndexed(post.images) { index, image ->
                    JrwiImage(
                        path = image.url,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.height(220.dp)
                                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                .clip(JrwiShape)
                                .clickable {
                                    onPicture(JrwiPictures(post.images.map { it.url }, index))
                                },
                    )
                }
            }
        }
    }
    // Every file of the post, to open in the file inspector (archiver 0.9.0 and newer); the
    // pictures are in their row above
    val listed = files?.filter { it.role != "image" && it.role != "thumbnail" }
    if (listed != null) {
        if (listed.isNotEmpty()) {
            item(key = "files_title:$id") {
                JrwiSectionTitle(stringResource(CoreR.string.jrwi_files))
            }
            itemsIndexed(listed, key = { index, _ -> "postfile:$id:$index" }) { _, file ->
                JrwiLinkRow(
                    icon = jrwiFileIcon(file.type),
                    text = file.name,
                    detail = jrwiFileDetail(file),
                    onClick = { onFile(file) },
                )
            }
        }
    } else {
        // Older archivers list a post's attachments and documents, which open in the browser
        val attached = (post.attachments + post.documents).distinctBy { it.url }
        if (attached.isNotEmpty()) {
            item(key = "files_title:$id") {
                JrwiSectionTitle(stringResource(CoreR.string.jrwi_files))
            }
            items(attached, key = { "file:$id:" + it.url }) { file ->
                JrwiLinkRow(
                    icon = CoreR.drawable.ic_download,
                    text = file.name,
                    detail = file.size?.let { formatBytes(it) },
                    onClick = { onOpen(fileUrl(file.url)) },
                )
            }
        }
    }
    if (post.links.isNotEmpty()) {
        item(key = "links_title:$id") { JrwiSectionTitle(stringResource(CoreR.string.jrwi_links)) }
        items(post.links.distinctBy { it.url }, key = { "link:$id:" + it.url }) { link ->
            JrwiLinkRow(
                icon = CoreR.drawable.ic_external_link,
                text = link.text.ifBlank { link.url },
                detail = link.url.takeIf { link.text.isNotBlank() },
                onClick = { onOpen(link.url) },
            )
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
    onChoose: (audio: Boolean) -> Unit,
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
        // The archived video was deleted once TubeArchivist had it from YouTube (v0.15)
        item.video?.handedOver?.let { handedOver ->
            Text(
                text =
                    stringResource(
                        CoreR.string.jrwi_handed_over,
                        fullDate(handedOver.at) ?: handedOver.at.orEmpty(),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (!playsAudio && item.video?.isYoutube == true) {
            Text(
                text = stringResource(CoreR.string.jrwi_youtube_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The show an episode is from, under its title: the show's picture, name and number of posts. A tap
 * opens the show.
 */
@Composable
private fun JrwiShowLink(show: JrwiShow, onClick: () -> Unit) {
    val ownPicture = show.poster ?: show.banner
    // The logo is shown whole, as on the show's tile
    val logo = ownPicture == null && show.image != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.medium,
                    vertical = MaterialTheme.spacings.small,
                ),
    ) {
        Box(
            modifier =
                Modifier.width(72.dp)
                    .aspectRatio(16f / 9f)
                    .clip(JrwiShape)
                    .background(JrwiColors.Picture)
        ) {
            JrwiImage(
                path = show.image ?: ownPicture,
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize().then(if (logo) Modifier.padding(4.dp) else Modifier),
            )
        }
        Spacer(Modifier.width(MaterialTheme.spacings.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (show.itemCount > 0) {
                Text(
                    text =
                        pluralStringResource(
                            CoreR.plurals.jrwi_item_count,
                            show.itemCount,
                            show.itemCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** A post's tags under its description, as the web app shows them. */
@Composable
private fun JrwiTags(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacings.medium,
                    vertical = MaterialTheme.spacings.small,
                ),
    ) {
        tags.forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.bodySmall,
                color = JrwiColors.ChipText,
                modifier =
                    Modifier.background(JrwiColors.Chip, JrwiLabelShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A comment as the site shows it: the name in its tier's colour, its badges, Pinned, how long ago,
 * and a link to it on the site.
 */
@Composable
private fun JrwiCommentRow(comment: JrwiComment, depth: Int, onOpen: (String) -> Unit) {
    val author = comment.author
    val avatarSize = if (depth == 0) 36.dp else 28.dp
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
        if (author.avatar != null) {
            JrwiImage(path = author.avatar, modifier = Modifier.size(avatarSize).clip(CircleShape))
        } else {
            // The name's first letter, as the site shows members without a picture
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(avatarSize).clip(CircleShape).background(JrwiColors.LineSoft),
            ) {
                Text(
                    text = author.name.take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = JrwiColors.Text,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = author.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = cssColor(author.color) ?: JrwiColors.Text,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    author.badges.forEach { badge ->
                        JrwiBadgeLabel(badge, Modifier.align(Alignment.CenterVertically))
                    }
                    if (comment.pinned) {
                        Text(
                            text = stringResource(CoreR.string.jrwi_pinned),
                            style = MaterialTheme.typography.labelSmall,
                            color = JrwiColors.PillText,
                            modifier =
                                Modifier.align(Alignment.CenterVertically)
                                    .background(JrwiColors.Pill, JrwiLabelShape)
                                    .padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
                (comment.dateText ?: fullDate(comment.date))?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MaterialTheme.spacings.small),
                    )
                }
                comment.link?.let { link ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_external_link),
                        contentDescription = stringResource(CoreR.string.jrwi_open_comment),
                        tint = JrwiColors.Muted,
                        modifier =
                            Modifier.padding(start = MaterialTheme.spacings.small)
                                .size(16.dp)
                                .clickable { onOpen(link) },
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

/**
 * A badge next to a commenter's name, as the site shows it: the tier as its picture alone, others
 * outlined with their picture or emoji and their text.
 */
@Composable
private fun JrwiBadgeLabel(badge: JrwiBadge, modifier: Modifier = Modifier) {
    val image = badge.image
    val icon = badge.icon
    if (image != null && icon == null) {
        JrwiBadgeImage(image, badge.name, modifier.height(16.dp))
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .border(1.dp, JrwiColors.Line, JrwiLabelShape)
                .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        when {
            image != null -> JrwiBadgeImage(image, null, Modifier.height(14.dp))
            icon != null -> Text(icon, style = MaterialTheme.typography.labelSmall)
        }
        badge.name?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = JrwiColors.BodyText,
            )
        }
    }
}

/** A badge's picture, as wide as it is for its height. */
@Composable
private fun JrwiBadgeImage(path: String, description: String?, modifier: Modifier) {
    AsyncImage(
        model = jrwiUrl(path),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

// Labels and tags, as the web app's badges and chips
private val JrwiLabelShape = RoundedCornerShape(6.dp)

/** A CSS colour as the site writes it, such as "rgb(247, 221, 210)" or "#f7ddd2"; else null. */
private fun cssColor(value: String?): Color? {
    val text = value?.trim()?.lowercase() ?: return null
    RGB_REGEX.matchEntire(text)?.let { match ->
        val channels = (1..3).map { match.groupValues[it].toFloatOrNull() ?: return null }
        val alpha = match.groupValues[4].toFloatOrNull() ?: 1f
        return Color(
            red = (channels[0] / 255f).coerceIn(0f, 1f),
            green = (channels[1] / 255f).coerceIn(0f, 1f),
            blue = (channels[2] / 255f).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
    if (!text.startsWith("#")) return null
    val hex = text.removePrefix("#")
    val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    val rgb = full.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}

private val RGB_REGEX =
    Regex("""rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)""")

// The archiver

/**
 * The server side of the JRWI tab: what the archiver is doing, a check now, and the posts it
 * couldn't fully save. Read again every few seconds while it's shown, so a check can be followed.
 */
@Composable
internal fun JrwiServerContent(
    modifier: Modifier = Modifier,
    viewModel: JrwiServerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(SERVER_REFRESH_MILLIS)
            }
        }
    }
    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, jrwiMessageText(context, message), Toast.LENGTH_LONG).show()
    }
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = modifier,
    ) {
        state.error?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        state.status?.let { status ->
            item {
                // Archivers since 0.9.3 word the detail to stand on its own line
                val phase =
                    if (status.step != null) status.phase.orEmpty()
                    else listOfNotNull(status.phase, status.detail).joinToString(": ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
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
                if (status.running && status.step != null) {
                    status.detail
                        ?.takeIf { it.isNotBlank() }
                        ?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = JrwiColors.BodyText,
                            )
                        }
                }
                val progress = status.progress
                val total = progress?.total ?: 0
                if (status.running && progress != null && total > 0) {
                    val done = progress.done ?: 0
                    // With the file that's downloading now as part of the one being worked on
                    val fraction = (done + (progress.current ?: 0.0)).toFloat() / total
                    LinearProgressIndicator(
                        // In the sheet's own purple
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    // "Post 3 of 12", as the web app shows it
                    val number = (done + 1).coerceAtMost(total)
                    Text(
                        text =
                            when (progress.unit) {
                                "post" ->
                                    stringResource(CoreR.string.jrwi_count_post, number, total)
                                "episode" ->
                                    stringResource(CoreR.string.jrwi_count_episode, number, total)
                                "page" ->
                                    stringResource(CoreR.string.jrwi_count_page, number, total)
                                else -> stringResource(CoreR.string.jrwi_count, number, total)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
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
                HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacings.small))
            }
        }
    }
}

private const val SERVER_REFRESH_MILLIS = 5_000L

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
