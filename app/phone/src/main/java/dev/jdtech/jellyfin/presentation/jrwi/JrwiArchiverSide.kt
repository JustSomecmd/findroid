package dev.jdtech.jellyfin.presentation.jrwi

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiServerState
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiServerViewModel
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiPodcast
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TA_CARD_MIN_WIDTH
import dev.jdtech.jellyfin.presentation.tube.TaSidePager
import dev.jdtech.jellyfin.presentation.tube.TaSideTitle
import dev.jdtech.jellyfin.presentation.tube.onServerSide
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged
import dev.jdtech.jellyfin.presentation.tube.switchSide
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * JRWI mode's Downloads: the JRWI episodes on the phone ([phone]), and on the server side the
 * archiver at work, as its admin page has it, side by side under one bar as TubeArchivist's
 * Downloads has the phone's and the server's queue. The bar takes the server side's purple along
 * with a swipe, as Home's does, and has a button to the admin page itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiDownloadsScreen(
    sides: PagerState,
    onItemClick: (Long) -> Unit,
    phone: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val adminUrl = jrwiUrl(JRWI_ADMIN_PATH)
    JrwiTheme {
        JrwiScaffold(
            modifier = Modifier.fillMaxSize().recalculateWindowInsetsUnlessPaged(),
            topBar = {
                JrwiSideTheme(sides = sides, serverFirst = false) {
                    // As tall as Home's header, so the bar stays put between the tabs
                    TopAppBar(
                        title = {
                            TaSideTitle(
                                title = stringResource(CoreR.string.title_download),
                                onServer = sides.onServerSide,
                                onToggle = { sides.switchSide(scope) },
                            )
                        },
                        // The archiver's admin page, which this page follows
                        actions = {
                            if (adminUrl != null) {
                                IconButton(onClick = { openUrl(context, adminUrl) }) {
                                    Icon(
                                        painterResource(CoreR.drawable.ic_external_link),
                                        contentDescription =
                                            stringResource(CoreR.string.jrwi_open_in_archiver),
                                    )
                                }
                            }
                        },
                        expandedHeight = JRWI_BAR_HEIGHT,
                        windowInsets = TOP_BAR_INSETS,
                        colors = jrwiTopBarColors(),
                    )
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { innerPadding ->
            TaSidePager(
                state = sides,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                phone = phone,
                server = {
                    // Only while it's shown, so the archiver is asked only then
                    JrwiSheetTheme {
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                        ) {
                            JrwiArchiverContent(
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

/**
 * The archiver at work, as its admin page shows it: what it's doing, Check now, Full check and
 * Re-read saved pages (Stop while one runs), what happened lately, the podcast feed it reads, and
 * the posts it couldn't fully save. While it's shown, the status is read every few seconds during a
 * check, so it can be followed, and once a minute otherwise; the rest once a check has finished, or
 * on a pull.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JrwiArchiverContent(
    onItemClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JrwiServerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh(details = true)
            var waited = 0L
            while (true) {
                delay(SERVER_REFRESH_MILLIS)
                waited += SERVER_REFRESH_MILLIS
                if (state.status?.running == true || waited >= SERVER_IDLE_REFRESH_MILLIS) {
                    viewModel.refresh(details = true)
                    waited = 0L
                }
            }
        }
    }
    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, jrwiMessageText(context, message), Toast.LENGTH_LONG).show()
    }
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { scope.launch { viewModel.refresh(force = true, details = true) } },
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
            val status = state.status ?: return@LazyVerticalGrid
            item(key = "status") { JrwiStatusCard(status, state.lastRun) }
            item(key = "actions") {
                JrwiArchiverActions(
                    state = state,
                    onCheck = { viewModel.check() },
                    onFullCheck = { viewModel.check(full = true) },
                    onReread = { viewModel.reread() },
                    onStop = { viewModel.stop() },
                )
            }
            item(key = "events") { JrwiEventsCard(state.events, onItemClick) }
            item(key = "podcast") {
                JrwiPodcastCard(
                    supported = state.hasPodcast,
                    podcast = state.podcast,
                    onItemClick = onItemClick,
                    onRead = { viewModel.readPodcast() },
                )
            }
            item(key = "problems") { JrwiProblemsCard(state.problems, onItemClick) }
        }
    }
}

/**
 * The admin page's controls, each with its icon and what it does: Check now and Full check, or Stop
 * while a check runs, and Re-read saved pages, which waits for the check to end.
 */
@Composable
private fun JrwiArchiverActions(
    state: JrwiServerState,
    onCheck: () -> Unit,
    onFullCheck: () -> Unit,
    onReread: () -> Unit,
    onStop: () -> Unit,
) {
    val running = state.status?.running == true
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        if (running) {
            if (state.canStop) {
                JrwiAction(
                    icon = CoreR.drawable.ic_jrwi_stop,
                    iconTint = JrwiColors.Bad,
                    title = stringResource(CoreR.string.jrwi_stop),
                    text = stringResource(CoreR.string.jrwi_action_stop),
                    onClick = onStop,
                )
            }
        } else {
            JrwiAction(
                icon = CoreR.drawable.ic_jrwi_check,
                title = stringResource(CoreR.string.jrwi_check_now),
                text = stringResource(CoreR.string.jrwi_action_check),
                onClick = onCheck,
            )
            JrwiAction(
                icon = CoreR.drawable.ic_jrwi_full_check,
                title = stringResource(CoreR.string.jrwi_full_check),
                text = stringResource(CoreR.string.jrwi_action_full_check),
                onClick = onFullCheck,
            )
        }
        JrwiAction(
            icon = CoreR.drawable.ic_jrwi_reread,
            title = stringResource(CoreR.string.jrwi_reread),
            text =
                stringResource(
                    if (state.canReread) CoreR.string.jrwi_action_reread
                    else CoreR.string.jrwi_action_reread_unsupported
                ),
            enabled = state.canReread && !running,
            onClick = onReread,
        )
    }
}

/** One of the admin page's action tiles: its icon, its name in bold, and what it does. */
@Composable
private fun JrwiAction(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = JrwiShape,
        color = JrwiPurple.Card,
        contentColor = JrwiColors.Text,
        border = BorderStroke(1.dp, JrwiPurple.Line),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
        ) {
            val alpha = if (enabled) 1f else DISABLED_ALPHA
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint.copy(alpha = iconTint.alpha * alpha),
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = JrwiColors.Text.copy(alpha = alpha),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = JrwiPurple.Muted.copy(alpha = alpha),
                )
            }
        }
    }
}

/** What happened on the archiver in the last week, newest first, as its admin page lists it. */
@Composable
private fun JrwiEventsCard(events: List<JrwiEvent>?, onItemClick: (Long) -> Unit) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_events)) {
        when {
            events == null ->
                Text(
                    text = stringResource(CoreR.string.jrwi_events_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            events.isEmpty() ->
                Text(
                    text = stringResource(CoreR.string.jrwi_events_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        events.orEmpty().forEach { event ->
            val postId = event.postId
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .let { if (postId != null) it.clickable { onItemClick(postId) } else it }
                        .padding(vertical = 6.dp)
            ) {
                Text(
                    text = dateAndTime(event.at) ?: event.at,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = eventText(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (postId != null) MaterialTheme.colorScheme.primary else JrwiColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An event in words: a new post by its title, the others by what happened. */
@Composable
private fun eventText(event: JrwiEvent): String {
    val about = event.title?.takeIf { it.isNotBlank() } ?: event.message.orEmpty()
    return when (event.type) {
        "new_item" -> about
        "video_handed_over" -> stringResource(CoreR.string.jrwi_event_handed_over, about)
        "check_failed" -> stringResource(CoreR.string.jrwi_event_check_failed, about)
        "login_failed" -> stringResource(CoreR.string.jrwi_event_login_failed, about)
        else ->
            listOf(event.type.replace('_', ' '), about)
                .filter { it.isNotBlank() }
                .joinToString(": ")
    }
}

/**
 * The site's private podcast feed as the archiver reads it: when it was read and what it had, the
 * last check a new episode in it started, its newest episodes, and Read it now.
 */
@Composable
private fun JrwiPodcastCard(
    supported: Boolean,
    podcast: JrwiPodcast?,
    onItemClick: (Long) -> Unit,
    onRead: () -> Unit,
) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_podcast)) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            !supported ->
                Text(
                    text = stringResource(CoreR.string.jrwi_podcast_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            podcast == null ->
                Text(
                    text = stringResource(CoreR.string.jrwi_events_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            !podcast.setUp ->
                Text(
                    text = stringResource(CoreR.string.jrwi_podcast_not_set_up),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            else -> JrwiPodcastDetails(podcast, onItemClick)
        }
        if (supported && podcast?.setUp == true) {
            OutlinedButton(
                shape = JrwiShape,
                border = JrwiGhostBorder,
                colors = jrwiGhostButtonColors(),
                onClick = onRead,
                modifier = Modifier.padding(top = MaterialTheme.spacings.small),
            ) {
                Text(stringResource(CoreR.string.jrwi_podcast_read_now))
            }
        }
    }
}

@Composable
private fun JrwiPodcastDetails(podcast: JrwiPodcast, onItemClick: (Long) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val read = dateAndTime(podcast.lastRead)
        Text(
            text =
                when {
                    read == null -> stringResource(CoreR.string.jrwi_podcast_not_read)
                    !podcast.ok ->
                        stringResource(
                            CoreR.string.jrwi_podcast_read_failed,
                            read,
                            podcast.error.orEmpty(),
                        )
                    else -> stringResource(CoreR.string.jrwi_podcast_read, read, podcast.count)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (podcast.ok) JrwiColors.Text else MaterialTheme.colorScheme.error,
        )
        Text(
            text =
                podcast.everyMinutes?.let { stringResource(CoreR.string.jrwi_podcast_every, it) }
                    ?: stringResource(CoreR.string.jrwi_podcast_off),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        podcast.lastTrigger?.let { trigger ->
            Text(
                text =
                    stringResource(
                        CoreR.string.jrwi_podcast_trigger,
                        dateAndTime(trigger.at) ?: trigger.at,
                        trigger.titles.joinToString(", "),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (podcast.count > 0) {
            Text(
                text =
                    stringResource(
                        CoreR.string.jrwi_podcast_matched,
                        podcast.matched,
                        podcast.count,
                        podcast.withTime,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (podcast.latest.isNotEmpty()) {
            Text(
                text = stringResource(CoreR.string.jrwi_podcast_newest),
                style = MaterialTheme.typography.labelLarge,
                color = JrwiColors.Text,
                modifier = Modifier.padding(top = MaterialTheme.spacings.small),
            )
            podcast.latest.forEach { episode ->
                val postId = episode.postId
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .let {
                                if (postId != null) it.clickable { onItemClick(postId) } else it
                            }
                            .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (postId != null) MaterialTheme.colorScheme.primary
                            else JrwiColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dateAndTime(episode.published)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}

/** A time from the archiver as the phone shows dates and times, in its own time zone. */
private fun dateAndTime(iso: String?): String? =
    try {
        iso?.let {
            OffsetDateTime.parse(it)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                )
        }
    } catch (_: Exception) {
        null
    }

private const val DISABLED_ALPHA = 0.45f
