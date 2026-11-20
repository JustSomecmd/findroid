package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.tubearchivist.loadPlaylist
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.DetailButton
import dev.jdtech.tubedroid.desktop.ui.DetailHeader
import dev.jdtech.tubedroid.desktop.ui.DetailMoreButton
import dev.jdtech.tubedroid.desktop.ui.ItemAction
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaImage
import dev.jdtech.tubedroid.desktop.ui.VideoRow
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatPlaytime
import dev.jdtech.tubedroid.desktop.ui.openInBrowser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        PageBar(title = null, onBack = if (canGoBack) navigator::back else null) {
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
                contentPadding = PaddingValues(bottom = Spacings.large),
                verticalArrangement = Arrangement.spacedBy(Spacings.extraSmall),
            ) {
                item(key = "header") {
                    PlaylistHeader(model = model, playlist = playlist, playlistId = playlistId)
                }
                item(key = "videos_title") {
                    SectionTitle(
                        text = "Videos",
                        modifier =
                            Modifier.padding(
                                start = PagePadding,
                                top = Spacings.large,
                                bottom = Spacings.small,
                            ),
                    )
                }
                itemsIndexed(model.videos, key = { _, video -> video.youtubeId }) { index, video ->
                    VideoRow(
                        video = video,
                        index = index + 1,
                        onClick = { navigator.open(Route.Video(video.youtubeId, playlistId)) },
                        modifier =
                            Modifier.padding(horizontal = PagePadding).widthIn(max = 1100.dp),
                    )
                }
                if (model.videos.isEmpty()) {
                    item {
                        Text(
                            text = "No downloaded videos in this playlist",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PagePadding),
                        )
                    }
                }
            }
            ListScrollbar(model.listState)
        }
    }
}

/**
 * The playlist as Jellyfin shows one: its picture as backdrop and poster, its name and numbers, and
 * round buttons to play it, download it, subscribe to it and more.
 */
@Composable
private fun PlaylistHeader(model: PlaylistModel, playlist: TaPlaylist, playlistId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val next = model.nextVideo
    val length = model.videos.sumOf { it.player?.duration ?: 0.0 }
    DetailHeader(
        title = playlist.name,
        info =
            listOfNotNull(
                playlist.channelName,
                "${model.videos.size} videos",
                length.takeIf { it > 0 }?.let { formatPlaytime(it.toLong()) },
                if (playlist.type == "custom") "Made here" else "From YouTube",
            ),
        backdrop = {
            TaImage(path = playlist.thumbUrl, modifier = Modifier.fillMaxSize().blur(16.dp))
        },
        poster = {
            TaImage(
                path = playlist.thumbUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        },
        posterWidth = 400.dp,
        buttons = {
            val carryOn = next != null && next != model.videos.firstOrNull()
            DetailButton(
                icon = "ic_play",
                label = if (carryOn) "Carry on with ${next?.title}" else "Play all",
                onClick = {
                    next?.let { navigator.play(PlayerRequest.Ta(it.youtubeId, playlistId)) }
                },
                main = true,
                enabled = next != null,
            )
            if (carryOn) {
                DetailButton(
                    icon = "ic_rotate_ccw",
                    label = "Play from the start",
                    onClick = {
                        model.videos.firstOrNull()?.let {
                            navigator.play(
                                PlayerRequest.Ta(it.youtubeId, playlistId, fromStart = true)
                            )
                        }
                    },
                )
            }
            DetailButton(
                icon = "ic_download",
                label = "Download all to this PC",
                onClick = {
                    model.videos.forEach { video -> app.downloads.startById(video.youtubeId) }
                    navigator.message("Downloading ${model.videos.size} videos to this PC")
                },
                enabled = model.videos.isNotEmpty(),
            )
            if (playlist.type != "custom") {
                DetailButton(
                    icon = if (playlist.subscribed) "ic_heart_filled" else "ic_heart",
                    label = if (playlist.subscribed) "Subscribed: unsubscribe" else "Subscribe",
                    onClick = { model.setSubscribed(!playlist.subscribed, navigator::message) },
                    on = playlist.subscribed,
                )
            }
            DetailMoreButton(
                actions =
                    listOf(
                        ItemAction("Open in TubeArchivist") {
                            app.api.mediaUrl("/playlist/$playlistId/")?.let(::openInBrowser)
                        }
                    )
            )
        },
        details = {
            playlist.description
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                }
        },
    )
}
