package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.formatCount
import kotlinx.coroutines.CoroutineScope

/** The kinds of videos TubeArchivist keeps, as the library's tabs. */
enum class VideoType(val label: String, val apiName: String) {
    VIDEOS("Videos", "videos"),
    STREAMS("Streams", "streams"),
    SHORTS("Shorts", "shorts"),
}

/** The orders TubeArchivist can sort videos in, as its own pages offer them. */
enum class VideoSort(val label: String, val apiName: String) {
    DOWNLOADED("Date added", "downloaded"),
    PUBLISHED("Date published", "published"),
    VIEWS("Views", "views"),
    LIKES("Likes", "likes"),
    DURATION("Length", "duration"),
    SIZE("File size", "mediasize"),
}

/** All of TubeArchivist's videos, of one kind, in the order chosen. */
class VideosModel(private val api: TaApi, private val scope: CoroutineScope) {
    var type by mutableStateOf(VideoType.VIDEOS)
        private set

    var sort by mutableStateOf(VideoSort.DOWNLOADED)
        private set

    var descending by mutableStateOf(true)
        private set

    val gridState = LazyGridState()

    var videos = newList()
        private set

    private fun newList() =
        PagedList<TaVideo>(scope, key = { it.youtubeId }) { page ->
            api.getVideos(
                page = page,
                type = type.apiName,
                sort = sort.apiName,
                order = if (descending) "desc" else "asc",
            )
        }

    init {
        videos.loadMore()
    }

    fun show(
        type: VideoType = this.type,
        sort: VideoSort = this.sort,
        descending: Boolean = this.descending,
    ) {
        this.type = type
        this.sort = sort
        this.descending = descending
        videos = newList().also { it.loadMore() }
    }
}

/** All the videos, as Jellyfin shows a library: tabs, a bar with the count and order, a grid. */
@Composable
fun VideosScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("videos") { scope -> VideosModel(app.api, scope) }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(
            title = "Videos",
            onBack = if (LocalCanGoBack.current) navigator::back else null,
            tabs = VideoType.entries.map { it.label },
            selectedTab = model.type.ordinal,
            onTabSelected = { model.show(type = VideoType.entries[it]) },
        )
        LibraryToolbar(
            count =
                model.videos.total?.let {
                    "${formatCount(it.toLong())} ${model.type.label.lowercase()}"
                },
            sortLabel = model.sort.label,
            sortOptions = VideoSort.entries.map { it.label },
            onSort = { model.show(sort = VideoSort.entries[it]) },
            descending = model.descending,
            onFlipOrder = { model.show(descending = !model.descending) },
        )
        VideoGrid(
            videos = model.videos,
            state = model.gridState,
            emptyText = "No ${model.type.label.lowercase()} yet",
            onClick = { navigator.open(Route.Video(it)) },
        )
    }
}

/**
 * The bar above a library's grid, as on Jellyfin: how many there are at the left, the order and
 * which way it runs at the right.
 */
@Composable
fun LibraryToolbar(
    count: String?,
    sortLabel: String,
    sortOptions: List<String>,
    onSort: (Int) -> Unit,
    descending: Boolean,
    onFlipOrder: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PagePadding - 8.dp),
    ) {
        Text(
            text = count.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                AppIcon(
                    name = "ic_arrow_down_up",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                Text(sortLabel)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                sortOptions.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            onSort(index)
                        },
                    )
                }
            }
        }
        RoundIconButton(
            icon = "ic_chevron_down",
            label = if (descending) "Highest first" else "Lowest first",
            onClick = onFlipOrder,
            modifier = Modifier.rotate(if (descending) 0f else 180f),
        )
    }
}

/** A grid of video cards that loads more as it's scrolled. */
@Composable
fun VideoGrid(
    videos: PagedList<TaVideo>,
    state: LazyGridState,
    emptyText: String,
    onClick: (String) -> Unit,
    showChannel: Boolean = true,
) {
    LoadMoreEffect(state) { videos.loadMore() }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(minSize = VIDEO_CARD_MIN_WIDTH),
            contentPadding =
                PaddingValues(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos.items, key = { it.youtubeId }) { video ->
                VideoCard(
                    video = video,
                    showChannel = showChannel,
                    onClick = { onClick(video.youtubeId) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { ListFooter(videos, emptyText = emptyText) }
        }
        GridScrollbar(state)
    }
}
