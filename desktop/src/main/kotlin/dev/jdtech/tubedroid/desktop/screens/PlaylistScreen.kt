package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.VideoRow
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Loads a playlist with its videos in the playlist's order. */
suspend fun TaApi.loadPlaylist(playlistId: String): Pair<TaPlaylist, List<TaVideo>> {
    val playlist = getPlaylist(playlistId)
    val videos = mutableListOf<TaVideo>()
    var page = 1
    while (true) {
        val result = getVideos(page = page, playlist = playlistId)
        videos += result.data
        if (!result.hasMore || page >= MAX_PAGES) break
        page++
    }
    val loaded = videos.associateBy { it.youtubeId }
    val ordered =
        playlist.entries
            .filter { it.downloaded }
            .map { entry ->
                loaded[entry.youtubeId] ?: TaVideo(youtubeId = entry.youtubeId, title = entry.title)
            }
            .ifEmpty { videos }
    return playlist to ordered
}

private const val MAX_PAGES = 20

class PlaylistModel(
    private val api: TaApi,
    private val scope: CoroutineScope,
    val playlistId: String,
) {
    var playlist by mutableStateOf<TaPlaylist?>(null)
        private set

    var videos by mutableStateOf<List<TaVideo>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val listState = LazyListState()

    init {
        load()
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val (loadedPlaylist, loadedVideos) = api.loadPlaylist(playlistId)
                playlist = loadedPlaylist
                videos = loadedVideos
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun setSubscribed(subscribed: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                api.setPlaylistSubscribed(playlistId, subscribed)
                playlist = playlist?.copy(subscribed = subscribed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    /** The first video not watched yet, to carry on with, or the first one. */
    val nextVideo: TaVideo?
        get() = videos.firstOrNull { it.player?.watched != true } ?: videos.firstOrNull()
}

@Composable
fun PlaylistScreen(playlistId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("playlist") { scope -> PlaylistModel(app.api, scope, playlistId) }
    val canGoBack = LocalCanGoBack.current

    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = model.playlist?.name, onBack = if (canGoBack) navigator::back else null) {
            OpenInWebButton("/playlist/$playlistId/")
            RefreshButton(onClick = model::load)
        }
        val playlist = model.playlist
        if (playlist == null) {
            PageState(loading = model.loading, error = model.error, onRetry = model::load)
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = model.listState,
                contentPadding = PaddingValues(Spacings.default),
                verticalArrangement = Arrangement.spacedBy(Spacings.extraSmall),
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier.widthIn(max = 900.dp).padding(bottom = Spacings.medium)
                    ) {
                        Text(text = playlist.name, style = MaterialTheme.typography.headlineSmall)
                        val about =
                            listOfNotNull(
                                playlist.channelName,
                                "${model.videos.size} videos",
                                if (playlist.type == "custom") "Your own playlist" else null,
                            )
                        Text(
                            text = about.joinToString(" • "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        playlist.description
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                Spacer(Modifier.height(Spacings.small))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                )
                            }
                        Spacer(Modifier.height(Spacings.medium))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                            val next = model.nextVideo
                            Button(
                                onClick = {
                                    next?.let {
                                        navigator.play(PlayerRequest(it.youtubeId, playlistId))
                                    }
                                },
                                enabled = next != null,
                            ) {
                                AppIcon(
                                    name = "ic_play",
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Spacings.small))
                                Text(
                                    if (next != null && next != model.videos.firstOrNull())
                                        "Carry on"
                                    else "Play all"
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    model.videos.forEach { video ->
                                        app.downloads.startById(video.youtubeId)
                                    }
                                    navigator.message(
                                        "Downloading ${model.videos.size} videos to this PC"
                                    )
                                },
                                enabled = model.videos.isNotEmpty(),
                            ) {
                                AppIcon(
                                    name = "ic_download",
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Spacings.small))
                                Text("Download all to this PC")
                            }
                            if (playlist.type != "custom") {
                                if (playlist.subscribed) {
                                    OutlinedButton(
                                        onClick = { model.setSubscribed(false, navigator::message) }
                                    ) {
                                        Text("Unsubscribe")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { model.setSubscribed(true, navigator::message) }
                                    ) {
                                        Text("Subscribe")
                                    }
                                }
                            }
                        }
                    }
                }
                itemsIndexed(model.videos, key = { _, video -> video.youtubeId }) { index, video ->
                    VideoRow(
                        video = video,
                        index = index + 1,
                        onClick = { navigator.open(Route.Video(video.youtubeId, playlistId)) },
                        modifier = Modifier.widthIn(max = 1100.dp),
                    )
                }
                if (model.videos.isEmpty()) {
                    item {
                        Text(
                            text = "No downloaded videos in this playlist",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacings.medium),
                        )
                    }
                }
            }
            ListScrollbar(model.listState)
        }
    }
}
