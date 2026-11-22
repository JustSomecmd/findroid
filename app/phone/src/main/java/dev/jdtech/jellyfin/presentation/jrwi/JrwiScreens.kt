package dev.jdtech.jellyfin.presentation.jrwi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiFeedPost
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemEvent
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiItemViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiMessages
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiPageDownload
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiPageDownloadResult
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiServerViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiSettingsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowPage
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowsState
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiStatsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.pages
import dev.jdtech.jellyfin.jrwi.JrwiAuthor
import dev.jdtech.jellyfin.jrwi.JrwiBadge
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiContinue
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiProblem
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.jellyfin.presentation.audio.AudioViewModel
import dev.jdtech.jellyfin.presentation.components.pageSwipes
import dev.jdtech.jellyfin.presentation.components.rememberPageSwiper
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.StorageSelectionDialog
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TA_CARD_MIN_WIDTH
import dev.jdtech.jellyfin.presentation.tube.TaSectionTitle
import dev.jdtech.jellyfin.presentation.tube.TaServerCard
import dev.jdtech.jellyfin.presentation.tube.TaSidePager
import dev.jdtech.jellyfin.presentation.tube.TaSideSwitch
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import dev.jdtech.jellyfin.presentation.tube.fullDate
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged
import dev.jdtech.jellyfin.presentation.tube.rememberSeparateRowScroll
import dev.jdtech.jellyfin.presentation.tube.serverSideShown
import dev.jdtech.jellyfin.presentation.tube.startTaPlayback
import dev.jdtech.jellyfin.presentation.tube.switchSide
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlin.math.ceil
import kotlin.math.roundToInt
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
    /** Behind the picture, before it loads and around it when it's fitted. */
    background: Color = JrwiColors.Picture,
) {
    AsyncImage(
        model = jrwiUrl(path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(background),
    )
}

internal val TOP_BAR_INSETS
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

/** Turns a message from a JRWI page into text. */
private fun jrwiMessageText(context: Context, message: String): String =
    when (message) {
        JrwiMessages.SAVED -> context.getString(CoreR.string.jrwi_saved)
        JrwiMessages.CHECK_STARTED -> context.getString(CoreR.string.jrwi_check_started)
        JrwiMessages.CHECK_RUNNING -> context.getString(CoreR.string.jrwi_check_running)
        JrwiMessages.NOT_IN_JELLYFIN -> context.getString(CoreR.string.jrwi_not_in_jellyfin)
        JrwiMessages.NOT_SAVED -> context.getString(CoreR.string.jrwi_not_saved)
        JrwiMessages.STOPPING -> context.getString(CoreR.string.jrwi_stopping)
        JrwiMessages.NOTHING_RUNNING -> context.getString(CoreR.string.jrwi_nothing_running)
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
 * JRWI mode's Home: what was started and not finished, then the archive's feed, as the web app's
 * Home has it, with a search button in its bar. Its server side, to the left as with
 * TubeArchivist's Home, is the archiver's page in purple: the archive's numbers, what it's doing,
 * Check now and the posts it couldn't fully save. A swipe, a tap on the switch, or a double tap on
 * the tab turns it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiHomeScreen(
    onItemClick: (Long) -> Unit,
    onShowClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSetUp: () -> Unit,
    sides: PagerState,
    viewModel: JrwiShowsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A post's pictures full screen, after one is tapped in the feed
    var pictures by remember { mutableStateOf<JrwiPictures?>(null) }
    pictures?.let { shown ->
        JrwiPictureViewer(
            pictures = shown,
            onClose = { pictures = null },
            onSave = { path -> saveJrwiPicture(context, viewModel.fileUrl(path), path) },
        )
    }
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose {}
    }
    val scope = rememberCoroutineScope()
    val onServer = sides.serverSideShown(serverFirst = true)
    // The archiver, from the header's pill, as TubeArchivist's servers from its Home
    var serverSheet by rememberSaveable { mutableStateOf(false) }
    // One page with the glow behind its bar, as the other JRWI pages, so the bar is one colour
    JrwiTheme {
        if (serverSheet) {
            val baseUrl = LocalJrwiBaseUrl.current
            JrwiServerSheet(
                address = baseUrl,
                version = state.version,
                error = state.error,
                onSettings = {
                    serverSheet = false
                    onSetUp()
                },
                onOpenWebApp = baseUrl?.let { url -> { openUrl(context, url) } },
                onDismiss = { serverSheet = false },
            )
        }
        JrwiScaffold(
            modifier = Modifier.fillMaxSize().recalculateWindowInsetsUnlessPaged(),
            topBar = {
                // As TubeArchivist Home's header: the archiver's pill with the side switch next to
                // it, and search at the other end, in the side's colours: the archiver's yellow,
                // or the purple of its server side, blending between them along with a swipe
                val header =
                    @Composable {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            HomeHeader(
                                serverName = stringResource(CoreR.string.jrwi_tab),
                                isLoading =
                                    state.configured &&
                                        state.loading &&
                                        state.shows.isEmpty() &&
                                        state.error == null,
                                isError = state.error != null,
                                onServerClick = { serverSheet = true },
                                onErrorClick = { serverSheet = true },
                                onRetryClick = { viewModel.load(force = true) },
                                onSearchClick = onSearchClick,
                                // The app's settings are on TubeArchivist's Home
                                onUserClick = null,
                                logo = {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_dice),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                },
                                actions =
                                    if (state.configured) {
                                        {
                                            TaSideSwitch(
                                                onServer = onServer,
                                                onToggle = { sides.switchSide(scope) },
                                                localIcon = CoreR.drawable.ic_home,
                                                localLabel =
                                                    stringResource(CoreR.string.ta_side_home),
                                                showLabel = false,
                                                serverFirst = true,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                modifier =
                                    Modifier.windowInsetsPadding(TOP_BAR_INSETS)
                                        .padding(
                                            horizontal = MaterialTheme.spacings.medium,
                                            vertical = MaterialTheme.spacings.small,
                                        ),
                            )
                        }
                    }
                JrwiSideTheme(sides = sides, serverFirst = true, content = header)
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            if (!state.configured) {
                JrwiSetUpPrompt(onSetUp, Modifier.padding(innerPadding))
                return@JrwiScaffold
            }
            TaSidePager(
                state = sides,
                serverFirst = true,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                phone = {
                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = { viewModel.load(force = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        JrwiHomeList(
                            state = state,
                            onItemClick = onItemClick,
                            onPicture = { pictures = it },
                            onOpen = { openUrl(context, it) },
                            onLoadDetails = viewModel::loadFeedDetails,
                            onLoadMore = viewModel::loadMoreFeed,
                        )
                    }
                },
                server = {
                    // Only while it's shown, so the archiver is asked only then
                    JrwiSheetTheme {
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                        ) {
                            JrwiServerContent(
                                onShowClick = onShowClick,
                                onItemClick = onItemClick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** The archiver JRWI mode is connected to, from its Home's pill: its address and version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JrwiServerSheet(
    address: String?,
    version: String?,
    error: String?,
    onSettings: () -> Unit,
    onOpenWebApp: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier.padding(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.default,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            TaServerCard(
                logo = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_dice),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                },
                kind = stringResource(CoreR.string.jrwi_archiver),
                name = address,
                lines = listOfNotNull(version?.let { "jrwi-archiver $it" }),
                status =
                    error
                        ?: stringResource(
                            if (version != null) CoreR.string.ta_connected
                            else CoreR.string.ta_checking
                        ),
                isError = error != null,
                buttonText = stringResource(CoreR.string.title_settings),
                onButtonClick = onSettings,
            )
            onOpenWebApp?.let { open ->
                TextButton(onClick = open) { Text(stringResource(CoreR.string.jrwi_open_web_app)) }
            }
        }
    }
}

/**
 * Continue watching, then the archive's feed: every post on its own, newest first, loaded a page at
 * a time as it's scrolled, as the web app's Home.
 */
@Composable
private fun JrwiHomeList(
    state: JrwiShowsState,
    onItemClick: (Long) -> Unit,
    onPicture: (JrwiPictures) -> Unit,
    onOpen: (String?) -> Unit,
    onLoadDetails: (Long) -> Unit,
    onLoadMore: () -> Unit,
) {
    val showNames = remember(state.shows) { state.shows.associate { it.id to it.name } }
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.error?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        if (state.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                JrwiItemRow(title = stringResource(CoreR.string.jrwi_continue_watching)) {
                    items(state.continueWatching, key = { "continue:" + it.item.id }) { entry ->
                        JrwiContinueCard(
                            entry = entry,
                            // On the version it was played in
                            onClick = { onItemClick(entry.progress.postId ?: entry.item.id) },
                            modifier = Modifier.width(240.dp),
                        )
                    }
                }
            }
        }
        if (state.feed.isNotEmpty()) {
            item(key = "feed_title") { TaSectionTitle(stringResource(CoreR.string.jrwi_latest)) }
        }
        itemsIndexed(state.feed, key = { _, entry -> "feed:" + entry.post.id }) { index, entry ->
            val post = entry.post
            LaunchedEffect(post.id) { onLoadDetails(post.id) }
            // The next page, a few posts before the end
            if (index >= state.feed.size - FEED_PREFETCH) {
                LaunchedEffect(state.feed.size) { onLoadMore() }
            }
            JrwiFeedCard(
                entry = entry,
                showName = showNames[post.show],
                onClick = { onItemClick(post.id) },
                onPicture = onPicture,
                onOpen = onOpen,
            )
        }
        if (state.feedLoading) {
            item(key = "feed_loading") {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

// How many posts before the end of the feed the next page is asked for
private const val FEED_PREFETCH = 5

/**
 * A post in the feed, laid out as the web app's feed has it: its media, then the show and date, its
 * title, a short preview of its text that opens in place, its files and tags, likes, comments, the
 * post in the archive's web app, and its first comments. A tap opens the post's page, and pictures
 * open full screen.
 */
@Composable
private fun JrwiFeedCard(
    entry: JrwiFeedPost,
    showName: String?,
    onClick: () -> Unit,
    onPicture: (JrwiPictures) -> Unit,
    onOpen: (String?) -> Unit,
) {
    val post = entry.post
    val full = entry.full
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .background(JrwiColors.Card)
                .border(1.dp, JrwiColors.Line, JrwiShape)
                .clickable(onClick = onClick)
    ) {
        JrwiFeedMedia(
            post = post,
            pictures = full?.images.orEmpty().map { it.url },
            onPicture = onPicture,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier = Modifier.padding(vertical = MaterialTheme.spacings.medium),
        ) {
            val inset = Modifier.padding(horizontal = MaterialTheme.spacings.medium)
            JrwiFeedMeta(post = post, showName = showName, modifier = inset)
            Text(
                text = post.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = JrwiColors.Text,
                modifier = inset,
            )
            if (full != null) {
                JrwiFeedText(post = full, modifier = inset)
                JrwiFeedFiles(post = full, onClick = onClick)
                if (full.tags.isNotEmpty()) JrwiTags(full.tags)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier = inset.padding(top = MaterialTheme.spacings.small),
            ) {
                post.likeCount?.let { JrwiPill(CoreR.drawable.ic_heart, it.toString()) }
                JrwiPill(
                    icon = CoreR.drawable.ic_message_square,
                    text = (post.commentCount ?: 0).toString(),
                    onClick = onClick,
                )
                // The post in the archiver's web app, as the post's page opens it
                jrwiUrl("/items/${post.id}")?.let { url ->
                    JrwiPill(
                        icon = CoreR.drawable.ic_external_link,
                        text = stringResource(CoreR.string.jrwi_original_post),
                        onClick = { onOpen(url) },
                    )
                }
            }
            if (entry.comments.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = MaterialTheme.spacings.small)) {
                    entry.comments.forEach { comment -> JrwiFeedComment(comment, onOpen) }
                    if (entry.moreComments) {
                        Text(
                            text = stringResource(CoreR.string.jrwi_more_comments),
                            style = MaterialTheme.typography.labelLarge,
                            color = JrwiColors.Accent,
                            modifier =
                                Modifier.clickable(onClick = onClick)
                                    .padding(
                                        horizontal = MaterialTheme.spacings.medium,
                                        vertical = MaterialTheme.spacings.small,
                                    ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A feed post's media, as the web app's feed shows it: an audio or video post's picture with its
 * length and watched mark, or the post's pictures, up to four with how many more.
 */
@Composable
private fun JrwiFeedMedia(
    post: JrwiItem,
    pictures: List<String>,
    onPicture: (JrwiPictures) -> Unit,
) {
    val thumbnail = post.thumbnail
    when {
        post.audio != null || (post.video != null && thumbnail != null) ->
            JrwiThumbnail(
                item = post,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        pictures.isNotEmpty() -> JrwiImageGrid(paths = pictures, onPicture = onPicture)
        thumbnail != null ->
            JrwiImage(
                path = thumbnail,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
    }
}

/**
 * Up to four of a post's pictures, as the web app's feed lays them out: one whole, two or four side
 * by side, or three with the first across the top. The fourth says how many more there are. A tap
 * opens them all full screen.
 */
@Composable
private fun JrwiImageGrid(paths: List<String>, onPicture: (JrwiPictures) -> Unit) {
    if (paths.size == 1) {
        JrwiImage(
            path = paths[0],
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable {
                    onPicture(JrwiPictures(paths, 0))
                },
        )
        return
    }
    val rows =
        if (paths.size == 3) listOf(listOf(0), listOf(1, 2))
        else listOf(listOf(0, 1), listOf(2, 3).filter { it < paths.size })
    Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        rows
            .filter { it.isNotEmpty() }
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    row.forEach { index ->
                        // One across the top is twice as wide as it's tall
                        val ratio = if (row.size == 1) 2f else 1f
                        JrwiGridPicture(
                            paths = paths,
                            index = index,
                            onPicture = onPicture,
                            modifier = Modifier.weight(1f).aspectRatio(ratio),
                        )
                    }
                }
            }
    }
}

/** One picture of the grid; the last says how many more there are. */
@Composable
private fun JrwiGridPicture(
    paths: List<String>,
    index: Int,
    onPicture: (JrwiPictures) -> Unit,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clickable { onPicture(JrwiPictures(paths, index)) },
    ) {
        JrwiImage(
            path = paths[index],
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val more = paths.size - MAX_GRID
        if (index == MAX_GRID - 1 && more > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Text(
                    text = "+$more",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

private const val MAX_GRID = 4
private val GRID_GAP = 2.dp

/** The show and date of a feed post, and whether it's pinned and which version it is. */
@Composable
private fun JrwiFeedMeta(post: JrwiItem, showName: String?, modifier: Modifier = Modifier) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = listOfNotNull(showName, fullDate(post.date)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = JrwiColors.Muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (post.pinned) JrwiTag(stringResource(CoreR.string.jrwi_pinned))
        versionLabel(post)?.let { JrwiTag(it) }
    }
}

/** Which version of its episode a single post is, when the episode has more than one. */
@Composable
internal fun versionLabel(post: JrwiItem): String? {
    if (!post.paired) return null
    val version =
        when (post.version) {
            "video" -> CoreR.string.jrwi_video
            "audio" -> CoreR.string.jrwi_audio
            "youtube" -> CoreR.string.jrwi_youtube
            else -> return null
        }
    return stringResource(version)
}

/** A small label, as the web app's tags next to a post's date. */
@Composable
internal fun JrwiTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = JrwiColors.PillText,
        modifier =
            Modifier.background(JrwiColors.Pill, JrwiPillShape)
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/**
 * A short preview of a post's text, as the web app's feed has it, and "...Continue reading" to open
 * the rest in place, with its links.
 */
@Composable
private fun JrwiFeedText(post: JrwiItem, modifier: Modifier = Modifier) {
    val text = post.bodyText?.trim().orEmpty()
    if (text.isEmpty()) return
    var open by rememberSaveable(post.id) { mutableStateOf(false) }
    var cut by remember(post.id) { mutableStateOf(false) }
    if (open) {
        JrwiHtmlText(
            html = post.bodyHtml?.takeIf { it.isNotBlank() },
            text = text,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = JrwiColors.BodyText,
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut) {
            Text(
                text = stringResource(CoreR.string.jrwi_continue_reading),
                style = MaterialTheme.typography.labelLarge,
                color = JrwiColors.Accent,
                modifier =
                    Modifier.clickable { open = true }
                        .padding(vertical = MaterialTheme.spacings.small),
            )
        }
    }
}

private const val PREVIEW_LINES = 4

/** A feed post's attachments and documents, which open on its page, the first few listed. */
@Composable
private fun JrwiFeedFiles(post: JrwiItem, onClick: () -> Unit) {
    val files =
        (post.attachments.map { it to "other" } + post.documents.map { it to "document" })
            .distinctBy { it.first.url }
    if (files.isEmpty()) return
    Column {
        files.take(FEED_FILES).forEach { (file, type) ->
            JrwiLinkRow(
                icon = jrwiFileIcon(type),
                text = file.name,
                detail = file.size?.let { formatBytes(it) },
                onClick = onClick,
            )
        }
        if (files.size > FEED_FILES) {
            Text(
                text = stringResource(CoreR.string.jrwi_more_files, files.size - FEED_FILES),
                style = MaterialTheme.typography.labelLarge,
                color = JrwiColors.Accent,
                modifier =
                    Modifier.clickable(onClick = onClick)
                        .padding(
                            horizontal = MaterialTheme.spacings.medium,
                            vertical = MaterialTheme.spacings.small,
                        ),
            )
        }
    }
}

private const val FEED_FILES = 3

/** An outlined pill under a feed post, as the web app's: its likes, comments or a link. */
@Composable
private fun JrwiPill(icon: Int, text: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.clip(JrwiPillShape)
                .border(1.dp, JrwiColors.Line, JrwiPillShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = JrwiColors.PillText,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = JrwiColors.PillText)
    }
}

/**
 * One of a feed post's first comments, as the web app's feed shows them: the name in its tier's
 * colour with its badges, Pinned, how long ago and a link to it on the site, the start of the text
 * with "Read more" to open the rest, its likes, and its replies, which open under it.
 */
@Composable
private fun JrwiFeedComment(comment: JrwiComment, onOpen: (String) -> Unit) {
    var open by rememberSaveable(comment.id) { mutableStateOf(false) }
    var cut by remember(comment.id) { mutableStateOf(false) }
    var replies by rememberSaveable(comment.id) { mutableStateOf(false) }
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacings.medium,
                        vertical = MaterialTheme.spacings.small,
                    ),
        ) {
            JrwiCommentAvatar(comment.author, 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                JrwiCommentHeader(comment, onOpen)
                if (open) {
                    JrwiHtmlText(
                        html = comment.bodyHtml?.takeIf { it.isNotBlank() },
                        text = comment.bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        gap = JRWI_COMMENT_PARAGRAPH_GAP,
                    )
                } else {
                    Text(
                        text = comment.bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JrwiColors.BodyText,
                        maxLines = COMMENT_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { cut = it.hasVisualOverflow },
                    )
                    if (cut) {
                        Text(
                            text = stringResource(CoreR.string.jrwi_read_more),
                            style = MaterialTheme.typography.labelLarge,
                            color = JrwiColors.Accent,
                            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
                        )
                    }
                }
                // The web feed's foot: likes, and the replies, which open under the comment
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    JrwiCommentCount(
                        icon = CoreR.drawable.ic_heart,
                        text = comment.likeCount?.takeIf { it > 0 }?.toString(),
                    )
                    val count = comment.replies.size
                    if (count > 0) {
                        JrwiCommentCount(
                            icon = CoreR.drawable.ic_message_square,
                            text = count.toString(),
                            action =
                                stringResource(
                                    if (replies) CoreR.string.jrwi_hide_replies
                                    else CoreR.string.jrwi_load_replies
                                ),
                            onClick = { replies = !replies },
                        )
                    }
                }
            }
        }
        if (replies) {
            comment.replies.flattenReplies(depth = 1).forEach { (reply, depth) ->
                JrwiCommentRow(comment = reply, depth = depth, onOpen = onOpen)
            }
        }
    }
}

private const val COMMENT_LINES = 3

/** A count under a comment in the feed, with what a tap does, as the web feed's foot. */
@Composable
private fun JrwiCommentCount(
    icon: Int,
    text: String?,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.clip(JrwiShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 4.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = JrwiColors.Muted,
            modifier = Modifier.size(14.dp),
        )
        text?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = JrwiColors.Muted)
        }
        action?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = JrwiColors.Accent)
        }
    }
}

/** For the JRWI pages before the archiver is set up in Settings. */
@Composable
private fun JrwiSetUpPrompt(onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
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
}

/** JRWI mode's Shows: every show of the archive, as the Channels tab lists channels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiShowsScreen(
    onShowClick: (String) -> Unit,
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
                // As tall as Home's header, so the bar stays put between the tabs
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.jrwi_shows)) },
                    expandedHeight = JRWI_BAR_HEIGHT,
                    windowInsets = TOP_BAR_INSETS,
                    scrollBehavior = scrollBehavior,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            if (!state.configured) {
                JrwiSetUpPrompt(onSetUp, Modifier.padding(innerPadding))
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
 * An item's picture with its length, and whether it has a video, audio or both. With [marks], a
 * check when it was watched and a bar when it was partly played, as in the TubeArchivist lists.
 * [contentScale] crops the picture to fill its 16:9 box, or fits it whole inside.
 */
@Composable
private fun JrwiThumbnail(
    item: JrwiItem,
    modifier: Modifier = Modifier,
    marks: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(JrwiShape)) {
        JrwiImage(
            path = item.thumbnail,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        if (marks && item.progress?.finished == true) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = stringResource(CoreR.string.jrwi_watched),
                tint = JrwiColors.AccentInk,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(JrwiShape)
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
                    .clip(JrwiShape)
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

/**
 * A show: its picture across the top with its name, as the web app's show page has it, then a tab
 * for each kind of post it has, such as episodes and aftershows, or each season of them, and their
 * list. Scrolling a list folds the picture away into the top bar, which then shows the picture
 * small with the name, and keeps the tabs under it.
 */
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
        val collapse = remember { JrwiCollapse() }
        val context = LocalContext.current
        // A page downloaded whole, such as a season: its pop-up, and a note on what came of it
        val pageDownload by viewModel.pageDownload.collectAsStateWithLifecycle()
        val queueing by viewModel.queueing.collectAsStateWithLifecycle()
        ObserveAsEvents(viewModel.downloadResults) { result ->
            Toast.makeText(context, pageDownloadText(context, result), Toast.LENGTH_LONG).show()
        }
        pageDownload?.let { download ->
            JrwiPageDownloadDialog(
                download = download,
                onDownload = viewModel::download,
                onDismiss = viewModel::cancelDownload,
            )
        }
        val topInset = TOP_BAR_INSETS.asPaddingValues().calculateTopPadding()
        val barHeight = topInset + JRWI_SHOW_BAR_HEIGHT

        JrwiScaffold(contentWindowInsets = WindowInsets(0)) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().nestedScroll(collapse.connection)) {
                    show?.let { shown ->
                        JrwiShowHeader(
                            show = shown,
                            collapse = collapse,
                            topInset = topInset,
                            barHeight = barHeight,
                            // The picture fades into the tabs' purple when they follow it
                            fadeTo =
                                if (tabs.size > 1) JrwiColors.Header else JrwiColors.Background,
                        )
                    } ?: Spacer(Modifier.height(barHeight))
                    if (tabs.size > 1) {
                        JrwiShowTabs(
                            tabs = tabs,
                            selected = shownTab,
                            onSelect = { swiper.turnTo(it) },
                        )
                    }
                    when {
                        tabs.isNotEmpty() ->
                            HorizontalPager(
                                state = pages,
                                modifier = Modifier.weight(1f).fillMaxWidth().pageSwipes(swiper),
                                userScrollEnabled = false,
                                key = { page -> tabs[page].key },
                            ) { page ->
                                JrwiTabList(
                                    viewModel = viewModel,
                                    showId = showId,
                                    tab = tabs[page],
                                    onItemClick = onItemClick,
                                    queueing = queueing == tabs[page].key,
                                    onDownload = { viewModel.prepareDownload(tabs[page]) },
                                    topPadding =
                                        if (tabs.size > 1) 4.dp else MaterialTheme.spacings.medium,
                                )
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
                JrwiShowBar(show = show, collapse = collapse, navigateBack = navigateBack)
            }
        }
    }
}

/**
 * How far a show page's header has folded away: [offset] goes from 0, open, to minus [range],
 * folded. Lists scrolled up fold it before they move, and once a list is back at its top, scrolling
 * on opens it again.
 */
@Stable
private class JrwiCollapse {
    var offset by mutableFloatStateOf(0f)
    var range by mutableFloatStateOf(0f)

    /** From 0, open, to 1, folded. */
    val fraction: Float
        get() = if (range > 0f) (-offset / range).coerceIn(0f, 1f) else 0f

    /** The bar's purple, which comes in over the last part of the folding. */
    val barAlpha: Float
        get() = ((fraction - BAR_FROM) / (1f - BAR_FROM)).coerceIn(0f, 1f)

    val connection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) Offset(0f, move(available.y)) else Offset.Zero

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (available.y > 0f) Offset(0f, move(available.y)) else Offset.Zero
        }

    private fun move(delta: Float): Float {
        val before = offset.coerceIn(-range, 0f)
        val after = (before + delta).coerceIn(-range, 0f)
        offset = after
        return after - before
    }

    private companion object {
        const val BAR_FROM = 0.6f
    }
}

/**
 * The show's picture across the top, fading into [fadeTo], with its name and how many posts it has
 * over it. A show without a picture of its own has the JRWI logo shown whole on the bar's purple.
 * It folds away up to the bar as [collapse] says.
 */
@Composable
private fun JrwiShowHeader(
    show: JrwiShow,
    collapse: JrwiCollapse,
    topInset: Dp,
    barHeight: Dp,
    fadeTo: Color,
) {
    val ownPicture = show.poster ?: show.banner
    val picture = show.image ?: ownPicture
    // The logo is shown whole, as on the show tiles
    val logo = ownPicture == null
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val heroHeight = (JRWI_HERO_HEIGHT + topInset).coerceAtMost(screenHeight * 0.5f)
    Column(
        modifier =
            Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val placeable =
                    measurable.measure(
                        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                    )
                val range = (placeable.height - barHeight.roundToPx()).coerceAtLeast(0).toFloat()
                if (collapse.range != range) collapse.range = range
                val offset = collapse.offset.coerceIn(-range, 0f).roundToInt()
                layout(placeable.width, placeable.height + offset) { placeable.place(0, offset) }
            }
    ) {
        Box(
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier.fillMaxWidth().height(heroHeight).background(JrwiColors.Header),
        ) {
            if (logo) {
                // The glow behind the logo, as on the rest of the archiver's pages
                Box(
                    Modifier.matchParentSize().drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                0f to JrwiColors.LogoGlow,
                                1f to Color.Transparent,
                                center = Offset(size.width / 2, size.height * 0.4f),
                                radius = size.width * 0.6f,
                            )
                        )
                    }
                )
                JrwiImage(
                    path = picture,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier.matchParentSize()
                            .padding(
                                top = topInset + MaterialTheme.spacings.large,
                                bottom = 96.dp,
                                start = 64.dp,
                                end = 64.dp,
                            ),
                    // On the glow, not on a dark box
                    background = Color.Transparent,
                )
            } else {
                JrwiImage(path = picture, modifier = Modifier.matchParentSize())
            }
            // Dark at the top for the back button, and into the page at the bottom, as the web
            // app's .hero
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to JrwiColors.Background.copy(alpha = if (logo) 0f else 0.55f),
                            0.24f to Color.Transparent,
                            0.42f to Color.Transparent,
                            1f to fadeTo.copy(alpha = 0.96f),
                        )
                    )
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.padding(
                        start = MaterialTheme.spacings.medium,
                        end = MaterialTheme.spacings.medium,
                        bottom = 14.dp,
                    ),
            ) {
                Text(
                    text = show.name.uppercase(),
                    color = JrwiColors.Text,
                    style =
                        MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            lineHeight = 34.sp,
                            shadow =
                                Shadow(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    offset = Offset(0f, 4f),
                                    blurRadius = 24f,
                                ),
                        ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val count =
                    pluralStringResource(
                        CoreR.plurals.jrwi_item_count,
                        show.itemCount,
                        show.itemCount,
                    )
                val newest =
                    fullDate(show.latest)?.let { stringResource(CoreR.string.jrwi_show_newest, it) }
                Text(
                    text = listOfNotNull(count, newest).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.ChipText,
                )
            }
        }
    }
}

/**
 * A show page's pages as tabs under its picture, as they were under the bar before: on the bar's
 * purple, the shown one in white with the archiver's yellow line under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JrwiShowTabs(tabs: List<JrwiShowPage>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selected,
        edgePadding = MaterialTheme.spacings.medium,
        containerColor = JrwiColors.Header,
        contentColor = JrwiColors.Text,
        divider = {},
    ) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                text = { Text(pageTitle(tab), fontWeight = FontWeight.Bold) },
                selectedContentColor = JrwiColors.Text,
                unselectedContentColor = JrwiColors.Muted,
            )
        }
    }
}

/**
 * The show page's bar, over its picture: Back on a dark square while the picture shows, and once
 * it's folded away the bar's purple with the picture small and the show's name.
 */
@Composable
private fun JrwiShowBar(show: JrwiShow?, collapse: JrwiCollapse, navigateBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(JrwiColors.Header.copy(alpha = collapse.barAlpha)) }
                .windowInsetsPadding(TOP_BAR_INSETS)
                .height(JRWI_SHOW_BAR_HEIGHT)
                .padding(horizontal = MaterialTheme.spacings.small),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(44.dp)
                    .clip(JrwiShape)
                    .drawBehind {
                        drawRect(
                            JrwiColors.Background.copy(alpha = 0.55f * (1f - collapse.barAlpha))
                        )
                    }
                    .clickable(onClick = navigateBack),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_left),
                contentDescription = null,
                tint = JrwiColors.Text,
            )
        }
        if (show != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).graphicsLayer { alpha = collapse.barAlpha },
            ) {
                val ownPicture = show.poster ?: show.banner
                JrwiImage(
                    path = show.image ?: ownPicture,
                    contentScale = if (ownPicture == null) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.size(32.dp).clip(JrwiShape),
                )
                Text(
                    text = show.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = JrwiColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// The show page's bar, under the status bar, and its picture's height under that
private val JRWI_SHOW_BAR_HEIGHT = 64.dp
private val JRWI_HERO_HEIGHT = 300.dp

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
    /** Its episodes are being read or queued to download. */
    queueing: Boolean,
    /** Downloads every episode of the page, after the pop-up. */
    onDownload: () -> Unit,
    topPadding: Dp,
) {
    val flow = remember(tab) { viewModel.items(tab) }
    val list by flow.collectAsStateWithLifecycle()
    val showNames by viewModel.showNames.collectAsStateWithLifecycle()
    LaunchedEffect(tab) { if (list.page == 0) viewModel.loadMore(tab) }
    val downloadLabel = pageDownloadLabel(tab)
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = MaterialTheme.spacings.medium,
                end = MaterialTheme.spacings.medium,
                top = topPadding,
                bottom = MaterialTheme.spacings.medium,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        downloadLabel?.let { label ->
            item(key = "download") {
                JrwiPageDownloadButton(label = label, busy = queueing, onClick = onDownload)
            }
        }
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

/**
 * What a page's download button says: "Download season" on a season's page, "Download show" on the
 * episodes of a show with one season, and "Download all" on its Just Rolled With It. Other pages,
 * such as the extras, have none.
 */
@Composable
private fun pageDownloadLabel(page: JrwiShowPage): String? =
    when {
        page.tab != "episodes" && page.tab != "aftershow" -> null
        page.season != null -> stringResource(CoreR.string.jrwi_download_season)
        page.tab == "episodes" -> stringResource(CoreR.string.jrwi_download_show)
        else -> stringResource(CoreR.string.jrwi_download_all)
    }

/**
 * Puts the page's episodes in the download queue, after its pop-up. Their progress shows in a
 * notification, which needs asking for on Android 13 and newer; the downloads start either way.
 */
@Composable
private fun JrwiPageDownloadButton(label: String, busy: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    OutlinedButton(
        shape = JrwiShape,
        border = JrwiGhostBorder,
        colors = jrwiGhostButtonColors(),
        enabled = !busy,
        onClick = {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            onClick()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(CoreR.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Text(label)
    }
}

/**
 * The pop-up for downloading a page whole: the audio or the archived videos, when the page has
 * both, then with or without the posts' files, as an episode's own download asks. Videos go to the
 * storage picked when the phone has more than one.
 */
@Composable
private fun JrwiPageDownloadDialog(
    download: JrwiPageDownload,
    onDownload: (video: Boolean, withFiles: Boolean, storageIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val both = download.audioCount > 0 && download.videoCount > 0
    var video by remember(download) { mutableStateOf(download.audioCount == 0) }
    // The files choice, waiting for the storage to be picked
    var storageFor by remember(download) { mutableStateOf<Boolean?>(null) }
    val waiting = storageFor
    if (waiting != null) {
        val locations = remember {
            context.getExternalFilesDirs(null).map { dir ->
                val where =
                    context.getString(
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    )
                context.getString(
                    CoreR.string.storage_name,
                    where,
                    StatFs(dir.path).availableBytes / 1_000_000,
                )
            }
        }
        StorageSelectionDialog(
            storageLocations = locations,
            onSelect = { index -> onDownload(true, waiting, index) },
            onDismiss = onDismiss,
        )
        return
    }
    val choose: (Boolean) -> Unit = { withFiles ->
        if (video && context.getExternalFilesDirs(null).size > 1) storageFor = withFiles
        else onDownload(video, withFiles, 0)
    }
    val count = if (video) download.videoCount else download.audioCount
    AlertDialog(
        title = {
            Text(pluralStringResource(CoreR.plurals.jrwi_download_page_title, count, count))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
                if (both) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        listOf(false, true).forEach { isVideo ->
                            val number = if (isVideo) download.videoCount else download.audioCount
                            val name =
                                stringResource(
                                    if (isVideo) CoreR.string.jrwi_video
                                    else CoreR.string.jrwi_audio
                                )
                            FilterChip(
                                selected = video == isVideo,
                                onClick = { video = isVideo },
                                label = {
                                    Text(
                                        stringResource(
                                            CoreR.string.jrwi_version_count,
                                            name,
                                            number,
                                        )
                                    )
                                },
                                shape = JrwiPillShape,
                            )
                        }
                    }
                }
                Text(stringResource(CoreR.string.jrwi_download_page_files_text))
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { choose(true) }) {
                Text(stringResource(CoreR.string.jrwi_download_with_files))
            }
        },
        dismissButton = {
            TextButton(onClick = { choose(false) }) {
                Text(stringResource(CoreR.string.jrwi_download_without_files))
            }
        },
    )
}

/** What came of downloading a page whole, as a short note. */
private fun pageDownloadText(context: Context, result: JrwiPageDownloadResult): String {
    val resources = context.resources
    return when (result) {
        is JrwiPageDownloadResult.Added ->
            listOfNotNull(
                    result.added
                        .takeIf { it > 0 }
                        ?.let {
                            resources.getQuantityString(
                                CoreR.plurals.jrwi_download_page_added,
                                it,
                                it,
                            )
                        },
                    result.missing
                        .takeIf { it > 0 }
                        ?.let {
                            resources.getQuantityString(
                                CoreR.plurals.jrwi_download_page_missing,
                                it,
                                it,
                            )
                        },
                )
                .joinToString(" ")
        JrwiPageDownloadResult.AllThere ->
            context.getString(CoreR.string.jrwi_download_page_all_there)
        JrwiPageDownloadResult.NothingToDownload ->
            context.getString(CoreR.string.jrwi_download_page_nothing)
        is JrwiPageDownloadResult.Failed ->
            listOfNotNull(
                    result.added
                        .takeIf { it > 0 }
                        ?.let {
                            resources.getQuantityString(
                                CoreR.plurals.jrwi_download_page_added,
                                it,
                                it,
                            )
                        },
                    result.error.asString(resources),
                )
                .joinToString(" ")
    }
}

/**
 * An item with its picture at the side, as in a show's list. [label] goes under its details, such
 * as which version of its episode a post is.
 */
@Composable
internal fun JrwiItemRow(
    item: JrwiItem,
    onClick: () -> Unit,
    otherShow: String? = null,
    label: String? = null,
) {
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
            label?.let { Box(Modifier.padding(top = 4.dp)) { JrwiTag(it) } }
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
                        // The shown version's post in the archiver's web app, as a TubeArchivist
                        // video's page opens it in TubeArchivist
                        if (item != null) {
                            val archiveUrl = jrwiUrl("/items/${post?.id ?: item.id}")
                            if (archiveUrl != null) {
                                IconButton(onClick = { openUrl(context, archiveUrl) }) {
                                    Icon(
                                        painterResource(CoreR.drawable.ic_external_link),
                                        contentDescription =
                                            stringResource(CoreR.string.jrwi_open_in_archiver),
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
 * the timeline of the version that's picked. A post with neither starts with its title, as on the
 * web app, its pictures being under its text.
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
        if (item.video != null || item.audio != null)
            Box {
                // With its own bar, for the version that's picked
                JrwiThumbnail(item = item, modifier = Modifier.fillMaxWidth(), marks = false)
                val length =
                    (if (playsAudio) item.audio?.duration else item.video?.duration)
                        ?: item.duration
                if (!finished && position > 0 && length != null && length > 0) {
                    LinearProgressIndicator(
                        progress = { (position / length).toFloat().coerceIn(0f, 1f) },
                        trackColor = JrwiBarTrack,
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    )
                }
            }
        // Bold, as in the feed
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = JrwiColors.Text,
        )
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
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = 0,
                            count = 2,
                            baseShape = JrwiShape,
                        ),
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
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = 1,
                            count = 2,
                            baseShape = JrwiShape,
                        ),
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
        JrwiCommentAvatar(author, avatarSize)
        Column(modifier = Modifier.weight(1f)) {
            JrwiCommentHeader(comment, onOpen)
            JrwiHtmlText(
                html = comment.bodyHtml?.takeIf { it.isNotBlank() },
                text = comment.bodyText,
                style = MaterialTheme.typography.bodyMedium,
                gap = JRWI_COMMENT_PARAGRAPH_GAP,
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

/** A commenter's picture, or the name's first letter, as the site shows members without one. */
@Composable
internal fun JrwiCommentAvatar(author: JrwiAuthor, size: Dp) {
    if (author.avatar != null) {
        JrwiImage(path = author.avatar, modifier = Modifier.size(size).clip(CircleShape))
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(JrwiColors.LineSoft),
    ) {
        Text(
            text = author.name.take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = JrwiColors.Text,
        )
    }
}

/**
 * Who wrote a comment, as the site shows it: the name in its tier's colour, the badges (tier,
 * months as a member, Verified), Pinned, how long ago, and a link to it on the site.
 */
@Composable
private fun JrwiCommentHeader(comment: JrwiComment, onOpen: (String) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        JrwiAuthorName(
            author = comment.author,
            pinned = comment.pinned,
            modifier = Modifier.weight(1f),
        )
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
                    Modifier.padding(start = MaterialTheme.spacings.small).size(16.dp).clickable {
                        onOpen(link)
                    },
            )
        }
    }
}

/** A member's name in their tier's colour, with their badges and, for a comment, Pinned. */
@Composable
internal fun JrwiAuthorName(
    author: JrwiAuthor,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
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
        if (pinned) {
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
 * The server side of JRWI Home: the archive's numbers, as TubeArchivist's stats on its Home's
 * server side, what the archiver is doing, a check now, and the posts it couldn't fully save. While
 * it's shown, the status is read every few seconds during a check, so it can be followed, and once
 * a minute otherwise. The numbers are loaded once while the app runs, and again on a pull or once a
 * check has finished; the problems likewise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JrwiServerContent(
    onShowClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JrwiServerViewModel = hiltViewModel(),
    statsViewModel: JrwiStatsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by statsViewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            statsViewModel.onShown()
            // The status every few seconds while a check runs, to follow it, and now and then
            // while the archiver waits, to see a check start
            viewModel.refresh()
            var waited = 0L
            while (true) {
                delay(SERVER_REFRESH_MILLIS)
                waited += SERVER_REFRESH_MILLIS
                if (state.status?.running == true || waited >= SERVER_IDLE_REFRESH_MILLIS) {
                    viewModel.refresh()
                    waited = 0L
                }
            }
        }
    }
    // The numbers again once a check has finished
    LaunchedEffect(state.status?.running, state.status?.lastCheckFinished) {
        state.status?.let { statsViewModel.onStatus(it.running, it.lastCheckFinished) }
    }
    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, jrwiMessageText(context, message), Toast.LENGTH_LONG).show()
    }
    PullToRefreshBox(
        isRefreshing = stats.loading && stats.stats != null,
        onRefresh = {
            statsViewModel.refresh()
            scope.launch { viewModel.refresh(force = true) }
        },
        modifier = modifier,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxSize(),
        ) {
            state.error?.let { error ->
                jrwiWideItem("error") {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
            }
            jrwiWideItem("hero") { JrwiStatsHero(stats = stats.stats, status = state.status) }
            state.status?.let { status ->
                item(key = "status") {
                    JrwiStatusCard(
                        status = status,
                        canStop = state.canStop,
                        onCheck = { viewModel.check() },
                        onFullCheck = { viewModel.check(full = true) },
                        onStop = { viewModel.stop() },
                    )
                }
            }
            val numbers = stats.stats
            when {
                numbers != null ->
                    jrwiStatsCards(
                        stats = numbers,
                        status = state.status,
                        onShowClick = onShowClick,
                        onItemClick = onItemClick,
                    )
                stats.error != null ->
                    jrwiWideItem("stats_error") {
                        Column {
                            Text(
                                text = stats.error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { statsViewModel.refresh() }) {
                                Text(stringResource(CoreR.string.retry))
                            }
                        }
                    }
                else ->
                    jrwiWideItem("stats_loading") {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
            }
            if (state.status != null) {
                item(key = "problems") { JrwiProblemsCard(state.problems) }
            }
        }
    }
}

/**
 * What the archiver is doing: checking, on which post and how far, or waiting for the next check,
 * with Check now and Full check. While a check runs, an archiver that can stop one has Stop in
 * their place, as its admin page does.
 */
@Composable
private fun JrwiStatusCard(
    status: JrwiStatus,
    canStop: Boolean,
    onCheck: () -> Unit,
    onFullCheck: () -> Unit,
    onStop: () -> Unit,
) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_status)) {
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
                style = MaterialTheme.typography.titleSmall,
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
                        modifier = Modifier.padding(top = 4.dp),
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
                        "post" -> stringResource(CoreR.string.jrwi_count_post, number, total)
                        "episode" -> stringResource(CoreR.string.jrwi_count_episode, number, total)
                        "page" -> stringResource(CoreR.string.jrwi_count_page, number, total)
                        else -> stringResource(CoreR.string.jrwi_count, number, total)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.loginOk == false) {
            Text(
                text = stringResource(CoreR.string.jrwi_login_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = MaterialTheme.spacings.small),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
        ) {
            val finished = fullDate(status.lastCheckFinished)
            if (finished != null && status.lastResult != null) {
                Text(
                    text =
                        stringResource(
                            CoreR.string.jrwi_last_check,
                            finished,
                            status.lastResult.orEmpty(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text =
                    status.nextCheck?.let {
                        stringResource(CoreR.string.jrwi_next_check, fullDate(it) ?: it)
                    } ?: stringResource(CoreR.string.jrwi_next_check_manual),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (status.running && canStop) {
            // The admin page's Stop: a ghost button with its square in red
            OutlinedButton(
                shape = JrwiShape,
                border = JrwiGhostBorder,
                colors = jrwiGhostButtonColors(),
                onClick = onStop,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_stop),
                    contentDescription = null,
                    tint = JrwiColors.Bad,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacings.small))
                Text(stringResource(CoreR.string.jrwi_stop))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                Button(shape = JrwiShape, onClick = onCheck) {
                    Text(stringResource(CoreR.string.jrwi_check_now))
                }
                OutlinedButton(
                    shape = JrwiShape,
                    border = JrwiGhostBorder,
                    colors = jrwiGhostButtonColors(),
                    onClick = onFullCheck,
                ) {
                    Text(stringResource(CoreR.string.jrwi_full_check))
                }
            }
        }
    }
}

/** The posts the archiver couldn't fully save, and why. */
@Composable
private fun JrwiProblemsCard(problems: List<JrwiProblem>) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_problems)) {
        if (problems.isEmpty()) {
            Text(
                text = stringResource(CoreR.string.jrwi_no_problems),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        problems.forEachIndexed { index, problem ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
                )
            }
            Text(text = problem.title, style = MaterialTheme.typography.bodyLarge)
            problem.problems.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SERVER_REFRESH_MILLIS = 5_000L
private const val SERVER_IDLE_REFRESH_MILLIS = 60_000L

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
