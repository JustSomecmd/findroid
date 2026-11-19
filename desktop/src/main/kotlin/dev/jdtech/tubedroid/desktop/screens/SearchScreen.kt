package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaSearchResults
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ChannelItem
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PlaylistCard
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchModel(private val api: TaApi, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set

    var results by mutableStateOf<TaSearchResults?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val gridState = LazyGridState()
    val focus = FocusRequester()
    private var job: Job? = null

    /** Searches a moment after typing stops. */
    fun onQueryChange(text: String) {
        query = text
        job?.cancel()
        if (text.isBlank()) {
            results = null
            return
        }
        job = scope.launch {
            delay(350)
            loading = true
            error = null
            try {
                results = api.search(text.trim())
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

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("search") { scope -> SearchModel(app.api, scope) }
    LaunchedEffect(Unit) { runCatching { model.focus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Search", onBack = null)
        OutlinedTextField(
            value = model.query,
            onValueChange = model::onQueryChange,
            placeholder = { Text("Videos, channels and playlists") },
            leadingIcon = { AppIcon(name = "ic_search", contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.onQueryChange(model.query) }),
            modifier =
                Modifier.padding(horizontal = Spacings.default, vertical = Spacings.medium)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .focusRequester(model.focus),
        )
        val results = model.results
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = model.gridState,
                columns = GridCells.Adaptive(minSize = VIDEO_CARD_MIN_WIDTH),
                contentPadding =
                    PaddingValues(
                        start = Spacings.default,
                        end = Spacings.default,
                        bottom = Spacings.default,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
            ) {
                if (model.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) { CircularProgressIndicator() }
                }
                model.error?.let { error ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(text = error, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (results != null) {
                    if (results.channels.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Channels") }
                        items(results.channels, key = { "c" + it.id }) { channel ->
                            ChannelItem(
                                channel = channel,
                                onClick = { navigator.open(Route.Channel(channel.id)) },
                            )
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Playlists") }
                        items(results.playlists, key = { "p" + it.id }) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                onClick = { navigator.open(Route.Playlist(playlist.id)) },
                            )
                        }
                    }
                    if (results.videos.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Videos") }
                        items(results.videos, key = { "v" + it.youtubeId }) { video ->
                            VideoCard(
                                video = video,
                                onClick = { navigator.open(Route.Video(video.youtubeId)) },
                            )
                        }
                    }
                    if (
                        results.channels.isEmpty() &&
                            results.playlists.isEmpty() &&
                            results.videos.isEmpty()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Nothing found",
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
