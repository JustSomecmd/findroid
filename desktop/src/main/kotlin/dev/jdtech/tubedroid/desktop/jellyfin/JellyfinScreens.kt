package dev.jdtech.tubedroid.desktop.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.bleed
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
                contentPadding = PaddingValues(bottom = Spacings.default),
            ) {
                items(model.rows, key = { it.title }) { row ->
                    SectionTitle(
                        row.title,
                        modifier = Modifier.padding(horizontal = Spacings.default),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacings.default),
                        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                    ) {
                        items(row.items, key = { it.id }) { item ->
                            if (row.wide) {
                                WideCard(item = item, modifier = Modifier.width(300.dp))
                            } else {
                                PosterCard(item = item, modifier = Modifier.width(160.dp))
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
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(Spacings.default),
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier.verticalScroll(model.scroll)
                        .padding(
                            start = Spacings.default,
                            end = Spacings.default,
                            bottom = Spacings.default,
                        )
            ) {
                Backdrop(item)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacings.large)) {
                    JellyfinImage(
                        url = posterUrl(item),
                        modifier =
                            Modifier.padding(top = Spacings.medium)
                                .width(if (item.type == BaseItemKind.EPISODE) 320.dp else 220.dp)
                                .aspectRatio(
                                    if (item.type == BaseItemKind.EPISODE) 16f / 9f else 2f / 3f
                                )
                                .clip(MaterialTheme.shapes.medium),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacings.small),
                        modifier = Modifier.weight(1f).padding(top = Spacings.medium),
                    ) {
                        if (item.type == BaseItemKind.EPISODE) {
                            item.seriesName?.let { show ->
                                Text(
                                    text = show,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier.clickable(enabled = item.seriesId != null) {
                                            item.seriesId?.let {
                                                navigator.open(Route.JellyfinShow(it.toString()))
                                            }
                                        },
                                )
                            }
                        }
                        Text(
                            text =
                                if (item.type == BaseItemKind.EPISODE) episodeTitle(item)
                                else item.name.orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = itemDetails(item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.genres
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { genres ->
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(Spacings.small)
                                ) {
                                    genres.forEach { genre ->
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) {
                                            Text(
                                                text = genre,
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier =
                                                    Modifier.padding(
                                                        horizontal = 10.dp,
                                                        vertical = 4.dp,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        Progress(item)
                        PlayButtons(
                            item = item,
                            onSetPlayed = { model.setPlayed(it, navigator::message) },
                        )
                        item.overview?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier =
                                    Modifier.widthIn(max = 900.dp).padding(top = Spacings.small),
                            )
                        }
                    }
                }
            }
            ColumnScrollbar(model.scroll)
        }
    }
}

/** Play or carry on, play from the start, and mark as watched or not. */
@Composable
private fun PlayButtons(item: BaseItemDto, onSetPlayed: (Boolean) -> Unit) {
    val navigator = LocalNavigator.current
    val position = JellyfinClient.seconds(item.userData?.playbackPositionTicks)
    val played = item.userData?.played == true
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { navigator.play(PlayerRequest.Jellyfin(item.id.toString())) }) {
            AppIcon(name = "ic_play", contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacings.small))
            Text(if (position > 5) "Resume from ${formatDuration(position)}" else "Play")
        }
        if (position > 5) {
            OutlinedButton(
                onClick = {
                    navigator.play(PlayerRequest.Jellyfin(item.id.toString(), fromStart = true))
                }
            ) {
                Text("Play from the start")
            }
        }
        TextButton(onClick = { onSetPlayed(!played) }) {
            AppIcon(
                name = if (played) "ic_eye_off" else "ic_check",
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacings.small))
            Text(if (played) "Mark unwatched" else "Mark watched")
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
                contentPadding =
                    PaddingValues(
                        start = Spacings.default,
                        end = Spacings.default,
                        bottom = Spacings.default,
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                item(key = "header") {
                    Column {
                        Backdrop(show)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacings.large),
                            modifier = Modifier.padding(top = Spacings.medium),
                        ) {
                            JellyfinImage(
                                url = posterUrl(show),
                                modifier =
                                    Modifier.width(200.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(MaterialTheme.shapes.medium),
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacings.small),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    show.name.orEmpty(),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text(
                                    text = itemDetails(show),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                model.nextUp?.let { next ->
                                    Button(
                                        onClick = {
                                            navigator.play(
                                                PlayerRequest.Jellyfin(next.id.toString())
                                            )
                                        }
                                    ) {
                                        AppIcon(
                                            name = "ic_play",
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(Spacings.small))
                                        Text("Play ${episodeTitle(next)}")
                                    }
                                }
                                show.overview?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 900.dp),
                                    )
                                }
                            }
                        }
                        if (model.seasons.size > 1) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                                modifier =
                                    Modifier.padding(top = Spacings.large, bottom = Spacings.small)
                                        .horizontalScrollIfNeeded(),
                            ) {
                                model.seasons.forEach { season ->
                                    FilterChip(
                                        selected = model.season?.id == season.id,
                                        onClick = { model.select(season) },
                                        label = { Text(season.name.orEmpty()) },
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.height(Spacings.large))
                        }
                    }
                }
                items(model.episodes, key = { it.id }) { episode -> EpisodeRow(episode) }
            }
            ListScrollbar(model.listState)
        }
    }
}

/** An episode: its picture, number and name, what it's about, and whether it was watched. */
@Composable
private fun EpisodeRow(episode: BaseItemDto) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { navigator.open(Route.JellyfinItem(episode.id.toString())) }
                .padding(Spacings.small),
    ) {
        Box(
            modifier = Modifier.width(240.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)
        ) {
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

/** A movie's or show's poster, with its name and year. */
@Composable
fun PosterCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    Column(
        modifier =
            modifier.clip(MaterialTheme.shapes.medium).clickable { navigator.open(item.route()) }
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)
        ) {
            JellyfinImage(url = posterUrl(item), modifier = Modifier.fillMaxSize())
            WatchedMarks(item)
        }
        Text(
            text = item.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacings.small),
        )
        item.productionYear?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An episode or a movie to carry on with, wide, with how far it got. */
@Composable
fun WideCard(item: BaseItemDto, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    Column(
        modifier =
            modifier.clip(MaterialTheme.shapes.medium).clickable {
                navigator.play(PlayerRequest.Jellyfin(item.id.toString()))
            }
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
        ) {
            JellyfinImage(url = wideUrl(app.jellyfin, item), modifier = Modifier.fillMaxSize())
            WatchedMarks(item)
        }
        Text(
            text =
                if (item.type == BaseItemKind.EPISODE) item.seriesName.orEmpty()
                else item.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(top = Spacings.small).clickable { navigator.open(item.route()) },
        )
        if (item.type == BaseItemKind.EPISODE) {
            Text(
                text = episodeTitle(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/** How far a movie or episode got, as a bar under its details. */
@Composable
private fun Progress(item: BaseItemDto) {
    val percent = item.userData?.playedPercentage ?: return
    if (percent <= 0 || item.userData?.played == true) return
    LinearProgressIndicator(
        progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
    )
}

/** The wide picture behind the top of an item's page, as wide as the page. */
@Composable
private fun Backdrop(item: BaseItemDto) {
    val app = LocalApp.current
    val url = backdropUrl(app.jellyfin, item) ?: return
    Box(
        modifier =
            Modifier.bleed(Spacings.default).fillMaxWidth().aspectRatio(BACKDROP_ASPECT_RATIO)
    ) {
        JellyfinImage(url = url, modifier = Modifier.fillMaxSize())
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

/** Lets a row of season chips scroll sideways when there are more than fit. */
@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

private const val BACKDROP_ASPECT_RATIO = 3.2f
