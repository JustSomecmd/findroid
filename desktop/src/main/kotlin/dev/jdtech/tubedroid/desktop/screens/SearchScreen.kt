package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PlaylistCard
import dev.jdtech.tubedroid.desktop.ui.SectionRow
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

    val listState = LazyListState()
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

    // Laid out as Jellyfin's search: the box on top, then a row for each kind of result
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
            shape = RoundedCornerShape(24.dp),
            modifier =
                Modifier.padding(horizontal = PagePadding, vertical = Spacings.medium)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .focusRequester(model.focus),
        )
        val results = model.results
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(top = Spacings.small, bottom = Spacings.large),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (model.loading) {
                    item {
                        CircularProgressIndicator(modifier = Modifier.padding(start = PagePadding))
                    }
                }
                model.error?.let { error ->
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = PagePadding),
                        )
                    }
                }
                if (results != null) {
                    if (results.videos.isNotEmpty()) {
                        item(key = "videos") {
                            SectionRow("Videos") {
                                items(results.videos, key = { it.youtubeId }) { video ->
                                    VideoCard(
                                        video = video,
                                        onClick = { navigator.open(Route.Video(video.youtubeId)) },
                                        modifier = Modifier.width(ROW_CARD_WIDTH),
                                    )
                                }
                            }
                        }
                    }
                    if (results.channels.isNotEmpty()) {
                        item(key = "channels") {
                            SectionRow("Channels") {
                                items(results.channels, key = { it.id }) { channel ->
                                    ChannelItem(
                                        channel = channel,
                                        onClick = { navigator.open(Route.Channel(channel.id)) },
                                        modifier = Modifier.width(CHANNEL_CARD_WIDTH),
                                    )
                                }
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        item(key = "playlists") {
                            SectionRow("Playlists") {
                                items(results.playlists, key = { it.id }) { playlist ->
                                    PlaylistCard(
                                        playlist = playlist,
                                        onClick = { navigator.open(Route.Playlist(playlist.id)) },
                                        modifier = Modifier.width(ROW_CARD_WIDTH),
                                    )
                                }
                            }
                        }
                    }
                    if (
                        results.channels.isEmpty() &&
                            results.playlists.isEmpty() &&
                            results.videos.isEmpty()
                    ) {
                        item {
                            Text(
                                "Nothing found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = PagePadding),
                            )
                        }
                    }
                }
            }
            ListScrollbar(model.listState)
        }
    }
}
