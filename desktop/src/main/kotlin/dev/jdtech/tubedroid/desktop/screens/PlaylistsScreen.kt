package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.PlaylistCard
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which playlists show: all, the ones made here, or the ones followed on YouTube. */
enum class PlaylistsTab(val label: String, val type: String?) {
    ALL("All playlists", null),
    CUSTOM("Made here", "custom"),
    YOUTUBE("From YouTube", "regular"),
}

class PlaylistsModel(private val api: TaApi, private val scope: CoroutineScope) {
    var tab by mutableStateOf(PlaylistsTab.ALL)

    val lists =
        PlaylistsTab.entries.associateWith { tab ->
            PagedList(scope, key = TaPlaylist::id) { page ->
                api.getPlaylists(page = page, type = tab.type)
            }
        }
    val grids = PlaylistsTab.entries.associateWith { LazyGridState() }

    fun refresh() = lists.getValue(tab).reload()

    /** Subscribes to playlists on YouTube by their links. */
    fun subscribe(links: List<String>, onDone: (String) -> Unit) =
        run(onDone) {
            api.subscribeToPlaylists(links)
            "TubeArchivist is subscribing to the playlists"
        }

    fun create(name: String, onDone: (String) -> Unit) =
        run(onDone) {
            api.createCustomPlaylist(name)
            lists.values.forEach { it.reload() }
            "Made the playlist $name"
        }

    private fun run(onDone: (String) -> Unit, action: suspend () -> String) {
        scope.launch {
            val message =
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorText(e)
                }
            onDone(message)
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("playlists") { scope -> PlaylistsModel(app.api, scope) }
    var dialog by remember { mutableStateOf<PlaylistsDialog?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(
            title = "Playlists",
            onBack = null,
            tabs = PlaylistsTab.entries.map { it.label },
            selectedTab = model.tab.ordinal,
            onTabSelected = { model.tab = PlaylistsTab.entries[it] },
        ) {
            RoundIconButton(
                icon = "ic_plus",
                label = "New playlist",
                onClick = { dialog = PlaylistsDialog.NEW },
            )
            RoundIconButton(
                icon = "ic_youtube",
                label = "Subscribe to playlists from YouTube",
                onClick = { dialog = PlaylistsDialog.SUBSCRIBE },
            )
            RefreshButton(onClick = model::refresh)
        }
        val list = model.lists.getValue(model.tab)
        val state = model.grids.getValue(model.tab)
        LoadMoreEffect(state) { list.loadMore() }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Adaptive(minSize = VIDEO_CARD_MIN_WIDTH),
                contentPadding =
                    PaddingValues(
                        start = PagePadding,
                        end = PagePadding,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                items(list.items, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { navigator.open(Route.Playlist(playlist.id)) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListFooter(list, emptyText = "No playlists here yet")
                }
            }
            GridScrollbar(state)
        }
    }

    when (dialog) {
        PlaylistsDialog.SUBSCRIBE ->
            TextInputDialog(
                title = "Subscribe to playlists",
                label = "Links, one per line",
                confirmLabel = "Subscribe",
                multiLine = true,
                onConfirm = { text ->
                    val links = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (links.isNotEmpty()) model.subscribe(links, navigator::message)
                },
                onDismiss = { dialog = null },
            )
        PlaylistsDialog.NEW ->
            TextInputDialog(
                title = "New playlist",
                label = "Name",
                confirmLabel = "Make",
                onConfirm = { name ->
                    if (name.isNotBlank()) model.create(name.trim(), navigator::message)
                },
                onDismiss = { dialog = null },
            )
        null -> {}
    }
}

private enum class PlaylistsDialog {
    SUBSCRIBE,
    NEW,
}
