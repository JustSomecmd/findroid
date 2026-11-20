package dev.jdtech.tubedroid.desktop.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.episodeTitle
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.screens.RefreshButton
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.CardShape
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.DetailButton
import dev.jdtech.tubedroid.desktop.ui.DetailHeader
import dev.jdtech.tubedroid.desktop.ui.DetailLine
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.HeaderTabs
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.MediaCard
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

// Home

/** A row on Jellyfin's home: its title and what's in it. */
class JellyfinRow(val title: String, val items: List<BaseItemDto>, val wide: Boolean)

class JellyfinHomeModel(private val client: JellyfinClient, private val scope: CoroutineScope) {
    var rows by mutableStateOf<List<JellyfinRow>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val listState = LazyListState()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                val found = mutableListOf<JellyfinRow>()
                client
                    .resume()
                    .takeIf { it.isNotEmpty() }
                    ?.let { found += JellyfinRow("Continue watching", it, wide = true) }
                client
                    .nextUp()
                    .takeIf { it.isNotEmpty() }
                    ?.let { found += JellyfinRow("Next up", it, wide = true) }
                client
                    .libraries()
                    .filter { it.isVideoLibrary() }
                    .forEach { library ->
                        val latest = runCatching {
                            client.latest(library.id)
                        }
                            .getOrDefault(emptyList())
                        if (latest.isNotEmpty()) {
                            found += JellyfinRow("Latest in ${library.name}", latest, wide = false)
                        }
                    }
                rows = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

/** Jellyfin's home: what to carry on with, the next episodes, and what's new per library. */
@Composable
fun JellyfinHomeScreen() {
    val app = LocalApp.current
    val model =
        rememberPageModel("jellyfin_home") { scope -> JellyfinHomeModel(app.jellyfin, scope) }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Jellyfin", onBack = null) { RefreshButton(onClick = model::refresh) }
        if (model.rows.isEmpty()) {
            if (model.loading || model.error != null) {
                PageState(loading = model.loading, error = model.error, onRetry = model::refresh)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(top = Spacings.small, bottom = Spacings.large),
            ) {
                items(model.rows, key = { it.title }) { row ->
                    SectionRow(row.title) {
                        items(row.items, key = { it.id }) { item ->
                            if (row.wide) {
                                WideCard(item = item, modifier = Modifier.width(300.dp))
                            } else {
                                PosterCard(item = item, modifier = Modifier.width(170.dp))
                            }
                        }
                    }
                }
            }
            ListScrollbar(model.listState)
        }
    }
}

// Libraries

/** Every movie or show, a page at a time, by name. */
class JellyfinLibraryModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val kind: BaseItemKind,
) {
    val items = mutableStateListOf<BaseItemDto>()
    var total by mutableStateOf<Int?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || (total != null && items.size >= total!!)) return
        loading = true
        error = null
        scope.launch {
            try {
                val (page, count) = client.items(kind, startIndex = items.size, limit = PAGE_SIZE)
                items += page.filter { new -> items.none { it.id == new.id } }
                total = if (page.isEmpty()) items.size else count
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        items.clear()
        total = null
        loadMore()
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}

@Composable
fun JellyfinLibraryScreen(movies: Boolean) {
    val app = LocalApp.current
    val kind = if (movies) BaseItemKind.MOVIE else BaseItemKind.SERIES
    val model =
        rememberPageModel(if (movies) "jellyfin_movies" else "jellyfin_shows") { scope ->
            JellyfinLibraryModel(app.jellyfin, scope, kind)
        }
    LoadMoreEffect(model.gridState) { model.loadMore() }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(
            title = (if (movies) "Movies" else "Shows") + (model.total?.let { " ($it)" } ?: ""),
            onBack = null,
        ) {
            RefreshButton(onClick = model::refresh)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = model.gridState,
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding =
                    PaddingValues(
                        start = PagePadding,
                        end = PagePadding,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            ) {
                items(model.items, key = { it.id }) { item -> PosterCard(item = item) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacings.medium), Alignment.Center) {
                        when {
                            model.loading -> CircularProgressIndicator()
                            model.error != null ->
                                TextButton(onClick = model::loadMore) {
                                    Text("${model.error} Try again")
                                }
                            model.items.isEmpty() ->
                                Text(
                                    if (movies) "No movies" else "No shows",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    }
                }
            }
            GridScrollbar(model.gridState)
        }
    }
}

// A movie or an episode

class JellyfinItemModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val itemId: String,
) {
    var item by mutableStateOf<BaseItemDto?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val scroll = androidx.compose.foundation.ScrollState(0)

    init {
        load()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                item = client.item(UUID.fromString(itemId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun setPlayed(played: Boolean, onMessage: (String) -> Unit) {
        val current = item ?: return
        scope.launch {
            try {
                client.setPlayed(current.id, played)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JellyfinItemScreen(itemId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model =
        rememberPageModel("jellyfin_item") { scope ->
            JellyfinItemModel(app.jellyfin, scope, itemId)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = null, onBack = if (canGoBack) navigator::back else null)
        val item = model.item
        if (item == null) {
            PageState(loading = model.loading, error = model.error, onRetry = model::load)
            return@Column
        }
        val episode = item.type == BaseItemKind.EPISODE
        val position = JellyfinClient.seconds(item.userData?.playbackPositionTicks)
        val played = item.userData?.played == true
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.verticalScroll(model.scroll).padding(bottom = Spacings.large)
            ) {
                DetailHeader(
                    title = if (episode) episodeTitle(item) else item.name.orEmpty(),
                    info = itemInfo(item),
                    backdrop = {
                        JellyfinImage(
                            url = backdropUrl(app.jellyfin, item),
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    poster = {
                        JellyfinImage(
                            url = posterUrl(item),
                            modifier =
                                Modifier.fillMaxWidth()
                                    .aspectRatio(if (episode) 16f / 9f else 2f / 3f),
                        )
                    },
                    posterWidth = if (episode) 400.dp else 240.dp,
                    buttons = {
                        DetailButton(
                            icon = "ic_play",
                            label =
                                if (position > 5) "Resume from ${formatDuration(position)}"
                                else "Play",
                            onClick = {
                                navigator.play(PlayerRequest.Jellyfin(item.id.toString()))
                            },
                            main = true,
                        )
                        if (position > 5) {
                            DetailButton(
                                icon = "ic_rotate_ccw",
                                label = "Play from the start",
                                onClick = {
                                    navigator.play(
                                        PlayerRequest.Jellyfin(item.id.toString(), fromStart = true)
                                    )
                                },
                            )
                        }
                        DetailButton(
                            icon = "ic_check",
                            label = if (played) "Mark unwatched" else "Mark watched",
                            onClick = { model.setPlayed(!played, navigator::message) },
                            on = played,
                        )
                    },
                    details = {
                        if (episode) {
                            item.seriesName?.let { show ->
                                DetailLine(label = "Show", value = show) {
                                    item.seriesId?.let {
                                        navigator.open(Route.JellyfinShow(it.toString()))
                                    }
                                }
                            }
                        }
                        item.genres
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { DetailLine(label = "Genres", value = it.joinToString(", ")) }
                        item.overview?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier =
                                    Modifier.widthIn(max = 900.dp).padding(top = Spacings.small),
                            )
                        }
                    },
                )
            }
            ColumnScrollbar(model.scroll)
        }
    }
}

// A show

class JellyfinShowModel(
    private val client: JellyfinClient,
    private val scope: CoroutineScope,
    private val seriesId: String,
) {
    var show by mutableStateOf<BaseItemDto?>(null)
        private set

    var seasons by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var season by mutableStateOf<BaseItemDto?>(null)
        private set

    var episodes by mutableStateOf<List<BaseItemDto>>(emptyList())
        private set

    var nextUp by mutableStateOf<BaseItemDto?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val listState = LazyListState()
    private val id = UUID.fromString(seriesId)

    init {
        load()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                show = client.item(id)
                seasons = client.seasons(id)
                nextUp = runCatching { client.nextUp(id).firstOrNull() }.getOrNull()
                // The season of the next episode, or else the first
                val start =
                    seasons.firstOrNull { it.id == nextUp?.seasonId }
                        ?: seasons.firstOrNull { (it.indexNumber ?: 0) > 0 }
                        ?: seasons.firstOrNull()
                start?.let { select(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun select(target: BaseItemDto) {
        season = target
        scope.launch {
            episodes =
                try {
                    client.episodes(id, target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = errorText(e)
                    emptyList()
                }
        }
    }
}

@Composable
fun JellyfinShowScreen(seriesId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val canGoBack = LocalCanGoBack.current
    val model =
        rememberPageModel("jellyfin_show") { scope ->
            JellyfinShowModel(app.jellyfin, scope, seriesId)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = null, onBack = if (canGoBack) navigator::back else null)
        val show = model.show
        if (show == null) {
            PageState(loading = model.loading, error = model.error, onRetry = model::load)
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                contentPadding = PaddingValues(bottom = Spacings.large),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                item(key = "header") {
                    Column {
                        DetailHeader(
                            title = show.name.orEmpty(),
                            info = itemInfo(show),
                            backdrop = {
                                JellyfinImage(
                                    url = backdropUrl(app.jellyfin, show),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            poster = {
                                JellyfinImage(
                                    url = posterUrl(show),
                                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                )
                            },
                            posterWidth = 240.dp,
                            buttons = {
                                val next = model.nextUp
                                DetailButton(
                                    icon = "ic_play",
                                    label = next?.let { "Play ${episodeTitle(it)}" } ?: "Play",
                                    onClick = {
                                        next?.let {
                                            navigator.play(PlayerRequest.Jellyfin(it.id.toString()))
                                        }
                                    },
                                    main = true,
                                    enabled = next != null,
                                )
                            },
                            details = {
                                show.genres
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        DetailLine(label = "Genres", value = it.joinToString(", "))
                                    }
                                show.overview?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 900.dp),
                                    )
                                }
                            },
                        )
                        if (model.seasons.size > 1) {
                            HeaderTabs(
                                tabs = model.seasons.map { it.name.orEmpty() },
                                selected =
                                    model.seasons
                                        .indexOfFirst { it.id == model.season?.id }
                                        .coerceAtLeast(0),
                                onSelect = { model.select(model.seasons[it]) },
                                modifier =
                                    Modifier.padding(
                                            start = PagePadding - 14.dp,
                                            top = Spacings.large,
                                            bottom = Spacings.small,
                                        )
                                        .horizontalScroll(rememberScrollState()),
                            )
                        } else {
                            Spacer(Modifier.height(Spacings.large))
                        }
                    }
                }
                items(model.episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        modifier = Modifier.padding(horizontal = PagePadding - 8.dp),
                    )
                }
            }
            ListScrollbar(model.listState)
        }
    }
}

/** An episode: its picture, number and name, what it's about, and whether it was watched. */
@Composable
private fun EpisodeRow(episode: BaseItemDto, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardShape)
                .clickable { navigator.open(Route.JellyfinItem(episode.id.toString())) }
                .padding(Spacings.small),
    ) {
        Box(modifier = Modifier.width(240.dp).aspectRatio(16f / 9f).clip(CardShape)) {
            JellyfinImage(url = wideUrl(app.jellyfin, episode), modifier = Modifier.fillMaxSize())
            WatchedMarks(episode)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episodeTitle(episode),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = itemDetails(episode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            episode.overview?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { navigator.play(PlayerRequest.Jellyfin(episode.id.toString())) }) {
            AppIcon(name = "ic_play", contentDescription = "Play")
        }
    }
}

// Pieces

/** A movie's or show's poster, with its name and year, as Jellyfin's cards. */
@Composable
fun PosterCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    MediaCard(
        title = item.name.orEmpty(),
        subtitle = item.productionYear?.toString(),
        onClick = { navigator.open(item.route()) },
        onPlay =
            if (item.type == BaseItemKind.SERIES) null
            else {
                { navigator.play(PlayerRequest.Jellyfin(item.id.toString())) }
            },
        aspectRatio = 2f / 3f,
        modifier = modifier,
    ) {
        JellyfinImage(url = posterUrl(item), modifier = Modifier.fillMaxSize())
        WatchedMarks(item)
    }
}

/** An episode or a movie to carry on with, wide, with how far it got. */
@Composable
fun WideCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val episode = item.type == BaseItemKind.EPISODE
    MediaCard(
        title = if (episode) item.seriesName.orEmpty() else item.name.orEmpty(),
        subtitle = if (episode) episodeTitle(item) else item.productionYear?.toString(),
        onClick = { navigator.open(item.route()) },
        onPlay = { navigator.play(PlayerRequest.Jellyfin(item.id.toString())) },
        modifier = modifier,
    ) {
        JellyfinImage(url = wideUrl(app.jellyfin, item), modifier = Modifier.fillMaxSize())
        WatchedMarks(item)
    }
}

/** A tick when watched, the number left for a show, and a bar for how far it got. */
@Composable
private fun WatchedMarks(item: BaseItemDto) {
    Box(modifier = Modifier.fillMaxSize()) {
        val data = item.userData
        val left = data?.unplayedItemCount ?: 0
        if (data?.played == true) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.primary),
            ) {
                AppIcon(
                    name = "ic_check",
                    contentDescription = "Watched",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (item.type == BaseItemKind.SERIES && left > 0) {
            Text(
                text = left.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val percent = data?.playedPercentage
        if (percent != null && percent > 0 && data?.played != true) {
            LinearProgressIndicator(
                progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            )
        }
    }
}

/** A picture from Jellyfin, which doesn't need signing in for. */
@Composable
fun JellyfinImage(url: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * The facts under an item's name, as on Jellyfin: year, length, rating, age rating, seasons, and
 * when it would end if started now.
 */
private fun itemInfo(item: BaseItemDto): List<String> =
    listOfNotNull(
        item.productionYear?.toString(),
        item.runTimeTicks?.let { runtime(JellyfinClient.seconds(it)) },
        item.communityRating?.let { "★ %.1f".format(it) },
        item.officialRating,
        item.childCount
            ?.takeIf { item.type == BaseItemKind.SERIES }
            ?.let { if (it == 1) "1 season" else "$it seasons" },
        item.runTimeTicks
            ?.takeIf { item.type != BaseItemKind.SERIES }
            ?.let { ticks ->
                val left =
                    JellyfinClient.seconds(ticks) -
                        JellyfinClient.seconds(item.userData?.playbackPositionTicks)
                "Ends at " +
                    java.time.LocalTime.now()
                        .plusSeconds(left.toLong().coerceAtLeast(0))
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            },
    )

/** Year, length, rating and age rating, such as "2019 · 1 h 52 min · ★ 7.8 · 12". */
private fun itemDetails(item: BaseItemDto): String =
    listOfNotNull(
            item.productionYear?.toString(),
            item.runTimeTicks?.let { runtime(JellyfinClient.seconds(it)) },
            item.communityRating?.let { "★ %.1f".format(it) },
            item.officialRating,
            item.childCount
                ?.takeIf { item.type == BaseItemKind.SERIES }
                ?.let { if (it == 1) "1 season" else "$it seasons" },
        )
        .joinToString(" · ")

private fun runtime(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private fun BaseItemDto.isVideoLibrary(): Boolean =
    collectionType?.serialName in setOf("movies", "tvshows", "homevideos", "musicvideos")

private fun BaseItemDto.route(): Route =
    when (type) {
        BaseItemKind.SERIES -> Route.JellyfinShow(id.toString())
        BaseItemKind.EPISODE,
        BaseItemKind.MOVIE -> Route.JellyfinItem(id.toString())
        else -> Route.JellyfinItem(id.toString())
    }

@Composable
private fun posterUrl(item: BaseItemDto): String? {
    val client = LocalApp.current.jellyfin
    return client.imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), 400)
}

/** For an episode its own picture, for a movie its backdrop, with the show's as the last resort. */
private fun wideUrl(client: JellyfinClient, item: BaseItemDto): String? =
    if (item.type == BaseItemKind.EPISODE) {
        client.imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), 600)
            ?: backdropUrl(client, item)
    } else {
        backdropUrl(client, item)
            ?: client.imageUrl(
                item.id,
                ImageType.PRIMARY,
                item.imageTags?.get(ImageType.PRIMARY),
                600,
            )
    }

private fun backdropUrl(client: JellyfinClient, item: BaseItemDto): String? {
    item.backdropImageTags?.firstOrNull()?.let { tag ->
        return client.imageUrl(item.id, ImageType.BACKDROP, tag, 1600)
    }
    val parent = item.parentBackdropItemId ?: return null
    val tag = item.parentBackdropImageTags?.firstOrNull() ?: return null
    return client.imageUrl(parent, ImageType.BACKDROP, tag, 1600)
}
