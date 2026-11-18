package dev.jdtech.jellyfin.presentation.tube

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaVideoAction
import dev.jdtech.jellyfin.film.presentation.tube.TaVideoViewModel
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.theme.ServerSideTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaVideoScreen(
    videoId: String,
    onVideoClick: (videoId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    navigateBack: () -> Unit,
    /** The playlist the video was opened from, whose next videos then play after it. */
    playlistId: String? = null,
    viewModel: TaVideoViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()

    // Loaded again when coming back from the player, for the new progress
    LifecycleResumeEffect(videoId) {
        viewModel.load(videoId)
        onPauseOrDispose {}
    }

    LaunchedEffect(state.episode) { state.episode?.let { downloaderViewModel.update(it) } }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful,
            is DownloaderEvent.Deleted -> viewModel.reloadEpisode()
        }
    }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    val play: (Boolean) -> Unit = { startFromBeginning ->
        state.episode?.let { episode ->
            // TubeArchivist's position comes first, as it may be newer than Jellyfin's. A saved
            // copy's position is from when it was downloaded, so then the phone's own is used.
            val player = state.video?.player?.takeIf { !state.offline }
            val position = player?.position
            val fromStart = startFromBeginning || player?.watched == true
            startTaPlayback(
                context = context,
                episodeId = episode.id,
                startFromBeginning = fromStart,
                positionSeconds = position,
                playlistId = playlistId,
            )
        }
    }

    // The video and its file and details on the server, to swipe between
    val scope = rememberCoroutineScope()
    val sides = rememberSidePagerState()

    // An action on the server waiting to be confirmed
    var confirming by rememberSaveable { mutableStateOf<TaVideoAction?>(null) }
    // The playlists to save the video to
    var savingToPlaylist by rememberSaveable { mutableStateOf(false) }
    // A deleted video has no page left to show
    ObserveAsEvents(viewModel.deleted) { navigateBack() }

    ServerSideTheme(pagerState = sides) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        // The title is already shown on the page, so the switch stands alone
                        TaSideSwitch(
                            onServer = sides.onServerSide,
                            onToggle = { sides.switchSide(scope) },
                            localIcon = CoreR.drawable.ic_play,
                            localLabel = stringResource(CoreR.string.ta_side_video),
                        )
                        TaOpenInWebButton(path = "/video/$videoId")
                    },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                )
            },
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        ) { innerPadding ->
            val video = state.video
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when {
                    video == null && state.error != null ->
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.align(Alignment.Center)
                                    .padding(MaterialTheme.spacings.default),
                        )
                    video == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else ->
                        TaSidePager(
                            state = sides,
                            phone = {
                                TaVideoContent(
                                    video = video,
                                    state = state,
                                    downloaderState = downloaderState,
                                    onPlay = play,
                                    onToggleWatched = { viewModel.toggleWatched() },
                                    onToggleFavorite = { viewModel.toggleFavorite() },
                                    onDownloaderAction = { downloaderViewModel.onAction(it) },
                                    onVideoClick = onVideoClick,
                                    onChannelClick = onChannelClick,
                                    // Playlists live on the server
                                    onPlaylistClick =
                                        if (state.offline) null
                                        else {
                                            {
                                                viewModel.loadCustomPlaylists()
                                                savingToPlaylist = true
                                            }
                                        },
                                )
                            },
                            server = {
                                TaVideoServerSide(
                                    video = video,
                                    offline = state.offline,
                                    onAction = { confirming = it },
                                )
                            },
                        )
                }
            }
        }
    }

    if (savingToPlaylist) {
        val playlists by viewModel.customPlaylists.collectAsStateWithLifecycle()
        TaSaveToPlaylistDialog(
            playlists = playlists,
            inPlaylists = state.video?.playlist.orEmpty().toSet(),
            onToggle = viewModel::setInPlaylist,
            onCreate = viewModel::createPlaylist,
            onDismiss = { savingToPlaylist = false },
        )
    }

    confirming?.let { action ->
        TaVideoActionDialog(
            action = action,
            count = 1,
            onConfirm = { ignore ->
                confirming = null
                viewModel.runAction(action, ignore)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun TaVideoContent(
    video: TaVideo,
    state: dev.jdtech.jellyfin.film.presentation.tube.TaVideoState,
    downloaderState: dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState,
    onPlay: (startFromBeginning: Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onPlaylistClick: (() -> Unit)?,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val padding = MaterialTheme.spacings.medium
    val episode = state.episode
    // The play button shows TubeArchivist's watched state and position, which playing follows
    val shownEpisode = episode?.let { item ->
        val player = video.player?.takeIf { !state.offline } ?: return@let item
        val position = player.position
        when {
            player.watched -> item.copy(played = true, playbackPositionTicks = 0)
            position != null ->
                item.copy(
                    played = false,
                    playbackPositionTicks = (position * TICKS_PER_SECOND).toLong(),
                )
            else -> item.copy(played = false)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacings.large),
    ) {
        item(key = "thumbnail") {
            Box(
                modifier =
                    Modifier.padding(horizontal = padding).clickable(enabled = episode != null) {
                        onPlay(false)
                    }
            ) {
                TaVideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
                if (episode != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_play),
                            contentDescription = stringResource(CoreR.string.ta_play),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        }
        item(key = "info") {
            Column(modifier = Modifier.padding(horizontal = padding, vertical = padding / 2)) {
                Text(text = video.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                Text(
                    text = videoDetails(video),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.offline) {
                    Text(
                        text = stringResource(CoreR.string.ta_offline_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        video.channel?.let { channel ->
            item(key = "channel") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { onChannelClick(channel.id) }
                            .padding(horizontal = padding, vertical = padding / 2),
                ) {
                    TaAvatar(path = channel.thumbUrl, size = 40.dp)
                    Spacer(Modifier.width(MaterialTheme.spacings.medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        channel.subs?.let { subs ->
                            Text(
                                text =
                                    stringResource(CoreR.string.ta_subscribers, formatCount(subs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "buttons") {
            Column(modifier = Modifier.padding(horizontal = padding, vertical = padding / 2)) {
                when {
                    episode != null && shownEpisode != null ->
                        ItemButtonsBar(
                            item = shownEpisode,
                            downloaderState = downloaderState,
                            onPlayClick = onPlay,
                            onMarkAsPlayedClick = onToggleWatched,
                            onMarkAsFavoriteClick = onToggleFavorite,
                            onTrailerClick = {},
                            onDownloadClick = { storageIndex ->
                                onDownloaderAction(DownloaderAction.Download(episode, storageIndex))
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(episode))
                            },
                            onDownloadDeleteClick = {
                                onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                            },
                            onDownloadPauseClick = {
                                onDownloaderAction(DownloaderAction.PauseDownload(episode))
                            },
                            onDownloadResumeClick = {
                                onDownloaderAction(DownloaderAction.ResumeDownload(episode))
                            },
                            onPlaylistClick = onPlaylistClick,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    state.lookingUp ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(MaterialTheme.spacings.small))
                            Text(
                                text = stringResource(CoreR.string.ta_finding_in_jellyfin),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    else ->
                        Text(
                            text = stringResource(CoreR.string.ta_not_in_jellyfin),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (episode == null && !state.lookingUp) {
                    Row {
                        TextButton(onClick = onToggleWatched) {
                            Text(
                                stringResource(
                                    if (video.player?.watched == true) {
                                        CoreR.string.ta_mark_unwatched
                                    } else {
                                        CoreR.string.ta_mark_watched
                                    }
                                )
                            )
                        }
                        onPlaylistClick?.let { onClick ->
                            TextButton(onClick = onClick) {
                                Text(stringResource(CoreR.string.ta_save_to_playlist))
                            }
                        }
                    }
                }
            }
        }
        video.description
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
                item(key = "description") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = padding, vertical = padding / 2)
                                .clickable { descriptionExpanded = !descriptionExpanded },
                    ) {
                        Text(
                            text = rememberLinkedText(description),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(padding).animateContentSize(),
                        )
                    }
                }
            }
        if (state.similar.isNotEmpty()) {
            item(key = "similar") {
                Column(modifier = Modifier.padding(vertical = padding / 2)) {
                    TaSectionTitle(
                        text = stringResource(CoreR.string.ta_similar_videos),
                        modifier = Modifier.padding(horizontal = padding),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = padding),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    ) {
                        items(state.similar, key = { it.youtubeId }) { similar ->
                            TaVideoSmallCard(
                                video = similar,
                                onClick = { onVideoClick(similar.youtubeId) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "comments_title") {
            val count = state.comments?.let { comments -> comments.sumOf { 1 + it.replies.size } }
            TaSectionTitle(
                text =
                    if (count != null && count > 0) {
                        stringResource(CoreR.string.ta_comments_count, count)
                    } else {
                        stringResource(CoreR.string.ta_comments)
                    },
                modifier = Modifier.padding(horizontal = padding),
            )
        }
        val comments = state.comments
        when {
            comments == null ->
                item(key = "comments_loading") {
                    Box(Modifier.fillMaxWidth().padding(padding)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            comments.isEmpty() ->
                item(key = "comments_empty") {
                    Text(
                        text = stringResource(CoreR.string.ta_no_comments),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = padding),
                    )
                }
            else ->
                items(comments, key = { "c" + it.id }) { comment ->
                    TaCommentItem(
                        comment = comment,
                        modifier = Modifier.padding(horizontal = padding),
                    )
                }
        }
    }
}

@Composable
private fun TaCommentItem(comment: TaComment, modifier: Modifier = Modifier) {
    var showReplies by rememberSaveable(comment.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TaCommentBody(comment = comment)
        if (comment.replies.isNotEmpty()) {
            TextButton(
                onClick = { showReplies = !showReplies },
                modifier = Modifier.padding(start = 40.dp),
            ) {
                Text(
                    stringResource(
                        if (showReplies) CoreR.string.ta_hide_replies
                        else CoreR.string.ta_show_replies,
                        comment.replies.size,
                    )
                )
            }
            if (showReplies) {
                Column(modifier = Modifier.padding(start = 40.dp).animateContentSize()) {
                    comment.replies.forEach { reply -> TaCommentBody(comment = reply) }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacings.extraSmall))
    }
}

@Composable
private fun TaCommentBody(comment: TaComment) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacings.small)) {
        TaAvatar(path = comment.authorThumbnail, size = 32.dp)
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (comment.authorIsUploader) FontWeight.Bold else null,
                    color =
                        if (comment.authorIsUploader) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                comment.timeText?.let {
                    Text(
                        text = " • $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = rememberLinkedText(comment.text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                modifier = Modifier.padding(top = MaterialTheme.spacings.extraSmall),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_thumbs_up),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatCount(comment.likeCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comment.isFavorited) {
                    Text(
                        text = " • " + stringResource(CoreR.string.ta_hearted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun videoDetails(video: TaVideo): String {
    val parts = mutableListOf<String>()
    video.stats?.viewCount?.let {
        parts.add(stringResource(CoreR.string.ta_views, formatCount(it)))
    }
    video.stats?.likeCount?.let {
        parts.add(stringResource(CoreR.string.ta_likes, formatCount(it)))
    }
    fullDate(video.published)?.let { parts.add(it) }
    return parts.joinToString(" • ")
}

private const val TICKS_PER_SECOND = 10_000_000.0

/** The video's side on the server: its file, streams, subtitles and other details. */
@Composable
private fun TaVideoServerSide(
    video: TaVideo,
    offline: Boolean,
    onAction: (TaVideoAction) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = MaterialTheme.spacings.medium,
                end = MaterialTheme.spacings.medium,
                bottom = MaterialTheme.spacings.large,
            ),
    ) {
        if (offline) {
            item(key = "offline") {
                Text(
                    text = stringResource(CoreR.string.ta_offline_copy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        item(key = "file") {
            Column {
                TaInfoHeading(stringResource(CoreR.string.ta_info_file))
                video.mediaUrl?.let { TaInfoRow(stringResource(CoreR.string.ta_info_path), it) }
                video.mediaSize?.let {
                    TaInfoRow(stringResource(CoreR.string.ta_info_size), formatBytes(it))
                }
                video.dateDownloaded?.let {
                    TaInfoRow(
                        stringResource(CoreR.string.ta_info_downloaded),
                        formatEpochSeconds(it),
                    )
                }
                video.type?.let { TaInfoRow(stringResource(CoreR.string.ta_info_type), it) }
                video.player
                    ?.takeIf { it.duration > 0 }
                    ?.let {
                        TaInfoRow(
                            stringResource(CoreR.string.ta_info_duration),
                            formatDuration(it.duration),
                        )
                    }
            }
        }
        if (video.streams.isNotEmpty()) {
            item(key = "streams") {
                Column {
                    TaInfoHeading(stringResource(CoreR.string.ta_info_streams))
                    video.streams.forEach { stream ->
                        val parts = mutableListOf(codecName(stream.codec))
                        if (stream.width != null && stream.height != null) {
                            parts.add("${stream.width}×${stream.height}")
                        }
                        stream.bitrate?.takeIf { it > 0 }?.let { parts.add(formatBitrate(it)) }
                        TaInfoRow(
                            stringResource(
                                if (stream.type == "audio") CoreR.string.ta_info_audio
                                else CoreR.string.ta_info_video
                            ),
                            parts.joinToString(" · "),
                        )
                    }
                }
            }
        }
        if (video.subtitles.isNotEmpty()) {
            item(key = "subtitles") {
                Column {
                    TaInfoHeading(stringResource(CoreR.string.ta_info_subtitles))
                    video.subtitles.forEach { subtitle ->
                        TaInfoRow(
                            subtitle.name ?: subtitle.lang,
                            stringResource(
                                if (subtitle.source == "auto") CoreR.string.ta_info_subtitle_auto
                                else CoreR.string.ta_info_subtitle_user
                            ),
                        )
                    }
                }
            }
        }
        item(key = "details") {
            Column {
                TaInfoHeading(stringResource(CoreR.string.ta_info_details))
                TaInfoRow(stringResource(CoreR.string.ta_info_video_id), video.youtubeId)
                fullDate(video.published)?.let {
                    TaInfoRow(stringResource(CoreR.string.ta_info_published), it)
                }
                formatTaDate(video.lastRefresh)?.let {
                    TaInfoRow(stringResource(CoreR.string.ta_info_last_refreshed), it)
                }
                video.active?.let { active ->
                    TaInfoRow(
                        stringResource(CoreR.string.ta_info_on_youtube),
                        stringResource(
                            if (active) CoreR.string.ta_info_available
                            else CoreR.string.ta_info_removed
                        ),
                    )
                }
                if (video.category.isNotEmpty()) {
                    TaInfoRow(
                        stringResource(CoreR.string.ta_info_category),
                        video.category.joinToString(", "),
                    )
                }
                video.commentCount?.let {
                    TaInfoRow(stringResource(CoreR.string.ta_info_comments_saved), it.toString())
                }
                video.sponsorblock?.let { sponsorBlock ->
                    TaInfoRow(
                        stringResource(CoreR.string.ta_sponsorblock),
                        if (sponsorBlock.isEnabled) {
                            stringResource(
                                CoreR.string.ta_info_segments,
                                sponsorBlock.segments.size,
                            )
                        } else {
                            stringResource(CoreR.string.ta_off)
                        },
                    )
                }
                if (video.playlist.isNotEmpty()) {
                    TaInfoRow(
                        stringResource(CoreR.string.ta_playlists),
                        video.playlist.size.toString(),
                    )
                }
                if (video.tags.isNotEmpty()) {
                    TaInfoRow(
                        stringResource(CoreR.string.ta_info_tags),
                        video.tags.joinToString(", "),
                    )
                }
            }
        }
        item(key = "actions") {
            Column {
                TaSectionHeading(stringResource(CoreR.string.ta_info_actions))
                TaVideoActionButtons(
                    onAction = onAction,
                    enabled = !offline,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                    onClick = {
                        val url = "https://www.youtube.com/watch?v=${video.youtubeId}"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(stringResource(CoreR.string.ta_open_on_youtube))
                }
            }
        }
    }
}

/** avc1.640028 as H.264 (avc1.640028). */
private fun codecName(codec: String): String {
    val name =
        when {
            codec.startsWith("avc") -> "H.264"
            codec.startsWith("hev") || codec.startsWith("hvc") -> "H.265"
            codec.startsWith("vp09") || codec.startsWith("vp9") -> "VP9"
            codec.startsWith("av01") -> "AV1"
            codec.startsWith("mp4a") -> "AAC"
            codec.startsWith("opus") -> "Opus"
            else -> return codec
        }
    return "$name ($codec)"
}

/** Bits per second as 4.5 Mbit/s or 128 kbit/s. */
private fun formatBitrate(bitsPerSecond: Long): String =
    if (bitsPerSecond >= 1_000_000) {
        String.format(java.util.Locale.getDefault(), "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)
    } else {
        "${bitsPerSecond / 1000} kbit/s"
    }

/**
 * The playlists made in TubeArchivist, ticked where the video is in them. Ticking adds the video
 * and unticking takes it out; a new playlist can be made here too, with the video in it.
 */
@Composable
private fun TaSaveToPlaylistDialog(
    playlists: List<TaPlaylist>?,
    inPlaylists: Set<String>,
    onToggle: (TaPlaylist, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.ta_save_to_playlist)) },
        text = {
            Column {
                when {
                    playlists == null ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                        ) {
                            CircularProgressIndicator()
                        }
                    playlists.isEmpty() ->
                        Text(
                            text = stringResource(CoreR.string.ta_no_custom_playlists),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                val checked = playlist.id in inPlaylists
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { onToggle(playlist, !checked) },
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onToggle(playlist, it) },
                                    )
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                }
                // A new playlist, with the video in it
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(CoreR.string.ta_new_playlist)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onCreate(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank() && playlists != null,
                    ) {
                        Text(stringResource(CoreR.string.ta_create))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.ta_done)) }
        },
    )
}
