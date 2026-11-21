package dev.jdtech.jellyfin.presentation.jrwi

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwiaudio.JrwiAudioPlayer
import dev.jdtech.jellyfin.jrwiaudio.JrwiAudioState
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app's one JRWI audio player, for the pages that show or control it. */
@HiltViewModel
class JrwiAudioViewModel @Inject constructor(val player: JrwiAudioPlayer) : ViewModel()

/**
 * The audio that's loaded, at the bottom of every page but the player's own, as storii has it: the
 * episode's picture, its title and time left, back 15 seconds, play or pause, and close. A tap or a
 * swipe up opens the player.
 */
@Composable
fun JrwiMiniPlayer(
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JrwiAudioViewModel = hiltViewModel(),
) {
    val player = viewModel.player
    val state by player.state.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpen)
    AnimatedVisibility(
        visible = visible && state.active,
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        JrwiTheme {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(JrwiColors.Header)
                        // Under the bottom bar there's no gap; without the bar, above the phone's
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
            ) {
                // How far it is, along the top
                Box(Modifier.fillMaxWidth().height(2.dp).background(JrwiBarTrack)) {
                    Box(
                        Modifier.fillMaxWidth(state.fraction())
                            .fillMaxHeight()
                            .background(JrwiColors.Accent)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable(onClick = onOpen)
                            .pointerInput(Unit) {
                                var dragged = 0f
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragged < -OPEN_SWIPE_DP.dp.toPx()) open()
                                        dragged = 0f
                                    },
                                    onDragCancel = { dragged = 0f },
                                ) { change, amount ->
                                    dragged += amount
                                    change.consume()
                                }
                            }
                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    JrwiImage(
                        path = state.picture,
                        modifier = Modifier.width(64.dp).aspectRatio(16f / 9f).clip(JrwiShape),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            text = state.item?.title.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = JrwiColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = miniLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (state.error != null) MaterialTheme.colorScheme.error
                                else JrwiColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { player.skip(-JrwiAudioPlayer.BACK_SECONDS) }) {
                        JrwiSeekIcon(seconds = JrwiAudioPlayer.BACK_SECONDS, forward = false)
                    }
                    JrwiPlayPauseButton(state = state, onClick = player::toggle, size = 40.dp)
                    IconButton(onClick = { player.stop() }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription = stringResource(CoreR.string.jrwi_player_close),
                            tint = JrwiColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

/** "12:04 · 23 mins left", or that it's loading or couldn't play. */
@Composable
private fun miniLine(state: JrwiAudioState): String =
    when {
        state.error != null -> stringResource(CoreR.string.jrwi_player_error)
        state.loading || state.durationMs <= 0 -> stringResource(CoreR.string.jrwi_player_loading)
        else -> {
            val left = state.leftAtSpeedMs()
            val minutes = ceil(left / 60_000.0).toInt()
            formatDuration(state.positionMs / 1000.0) +
                " · " +
                stringResource(CoreR.string.runtime_minutes_left, minutes)
        }
    }

/**
 * The audio player, a page of its own in the archiver's colours, after storii's: the episode's
 * picture, title and show, a bar to seek along with the time gone and the time left at the speed it
 * plays at (or the whole length, with a tap), back 15 and forward 30 seconds around play, then the
 * speed, the sleep timer, and the video from the same moment when the episode has one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiAudioPlayerScreen(
    onWatchVideo: (JrwiItem) -> Unit,
    onOpenEpisode: (postId: Long) -> Unit,
    navigateBack: () -> Unit,
    viewModel: JrwiAudioViewModel = hiltViewModel(),
) {
    val player = viewModel.player
    val state by player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Closed meanwhile, such as from its notification, the page goes too; not when it was closed
    // here for the video, which opens in its place
    var wasActive by remember { mutableStateOf(state.active) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(state.active) {
        if (wasActive && !state.active && !leaving) navigateBack()
        wasActive = state.active
    }
    var speedOpen by rememberSaveable { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    // The end of the bar shows the time left, or with a tap the whole length
    var showLength by rememberSaveable { mutableStateOf(false) }
    val item = state.item

    JrwiTheme {
        JrwiScaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painterResource(CoreR.drawable.ic_chevron_down),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        val audio = item?.audio
                        if (audio != null) {
                            IconButton(onClick = { onOpenEpisode(audio.postId) }) {
                                Icon(
                                    painterResource(CoreR.drawable.ic_info),
                                    contentDescription =
                                        stringResource(CoreR.string.jrwi_player_episode),
                                )
                            }
                        }
                    },
                    windowInsets = PLAYER_TOP_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = PLAYER_TOP_INSETS,
        ) { innerPadding ->
            if (!state.active) {
                Box(
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(CoreR.string.jrwi_player_nothing),
                        color = JrwiColors.Muted,
                    )
                }
                return@JrwiScaffold
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.padding(innerPadding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
                        .padding(horizontal = 24.dp, vertical = MaterialTheme.spacings.medium),
            ) {
                Box(
                    modifier =
                        Modifier.widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(JrwiShape)
                            .background(JrwiColors.Picture)
                ) {
                    JrwiImage(path = state.picture, modifier = Modifier.fillMaxSize())
                    if (state.loading) {
                        CircularProgressIndicator(
                            color = JrwiColors.Accent,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = item?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = JrwiColors.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(
                            state.showName,
                            if (state.fromPhone) stringResource(CoreR.string.jrwi_player_from_phone)
                            else null,
                        )
                        .joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = JrwiColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.error != null) {
                    Text(
                        text =
                            state.error?.takeIf { it.isNotBlank() }
                                ?: stringResource(CoreR.string.jrwi_player_error),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.spacings.large))
                JrwiSeekBar(
                    state = state,
                    showLength = showLength,
                    onToggleEnd = { showLength = !showLength },
                    onSeek = player::seekTo,
                )
                Spacer(Modifier.height(MaterialTheme.spacings.medium))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { player.skip(-JrwiAudioPlayer.BACK_SECONDS) },
                        modifier = Modifier.size(56.dp),
                    ) {
                        JrwiSeekIcon(
                            seconds = JrwiAudioPlayer.BACK_SECONDS,
                            forward = false,
                            size = 40.dp,
                        )
                    }
                    JrwiPlayPauseButton(state = state, onClick = player::toggle, size = 72.dp)
                    IconButton(
                        onClick = { player.skip(JrwiAudioPlayer.FORWARD_SECONDS) },
                        modifier = Modifier.size(56.dp),
                    ) {
                        JrwiSeekIcon(
                            seconds = JrwiAudioPlayer.FORWARD_SECONDS,
                            forward = true,
                            size = 40.dp,
                        )
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacings.large))
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    JrwiSquareButton(
                        icon = CoreR.drawable.ic_gauge,
                        contentDescription = stringResource(CoreR.string.jrwi_player_speed),
                        onClick = { speedOpen = true },
                        filled = state.speed != 1f,
                        modifier = Modifier.width(64.dp),
                    ) {
                        Text(
                            text = speedLabel(state.speed),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    JrwiSleepButton(state = state, onClick = { sleepOpen = true })
                    if (item?.video != null) {
                        JrwiSquareButton(
                            icon = CoreR.drawable.ic_film,
                            contentDescription =
                                stringResource(CoreR.string.jrwi_player_watch_video),
                            onClick = {
                                leaving = true
                                scope.launch {
                                    player.closeForVideo()
                                    onWatchVideo(item)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (speedOpen) {
            JrwiSpeedSheet(
                speed = state.speed,
                onSpeed = player::setSpeed,
                onDismiss = { speedOpen = false },
            )
        }
        if (sleepOpen) {
            JrwiSleepSheet(
                state = state,
                onSleepIn = {
                    player.sleepIn(it)
                    sleepOpen = false
                },
                onSleepAtEnd = {
                    player.sleepAtEnd()
                    sleepOpen = false
                },
                onAdd = player::addSleep,
                onOff = {
                    player.sleepIn(null)
                    sleepOpen = false
                },
                onDismiss = { sleepOpen = false },
            )
        }
    }
}

/** The bar to seek along, with the time gone under its start and the time left under its end. */
@Composable
private fun JrwiSeekBar(
    state: JrwiAudioState,
    showLength: Boolean,
    onToggleEnd: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    // Where the finger has it while it's held, so the bar doesn't jump back meanwhile
    var held by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Slider(
            value = held ?: state.fraction(),
            onValueChange = { held = it },
            onValueChangeFinished = {
                held?.let { onSeek((it * duration).toLong()) }
                held = null
            },
            enabled = duration > 0,
            colors =
                SliderDefaults.colors(
                    thumbColor = JrwiColors.Accent,
                    activeTrackColor = JrwiColors.Accent,
                    inactiveTrackColor = JrwiBarTrack,
                    disabledThumbColor = JrwiColors.Muted,
                    disabledInactiveTrackColor = JrwiBarTrack,
                ),
        )
        val shownMs = held?.let { (it * duration).toLong() } ?: state.positionMs
        Row(modifier = Modifier.fillMaxWidth()) {
            val labelStyle = MaterialTheme.typography.labelMedium
            Text(
                text = formatDuration(shownMs / 1000.0),
                style = labelStyle,
                color = JrwiColors.Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text =
                    if (duration <= 0) ""
                    else if (showLength) formatDuration(duration / 1000.0)
                    else
                        "−" +
                            formatDuration(
                                max(0L, duration - shownMs) / state.speed.toDouble() / 1000.0
                            ),
                style = labelStyle,
                color = JrwiColors.Muted,
                modifier = Modifier.clip(JrwiShape).clickable(onClick = onToggleEnd),
            )
        }
    }
}

/** Play or pause in the archiver's yellow, square, with a spinner while it loads. */
@Composable
private fun JrwiPlayPauseButton(state: JrwiAudioState, onClick: () -> Unit, size: Dp) {
    Surface(
        onClick = onClick,
        enabled = !state.loading,
        shape = JrwiShape,
        color = JrwiColors.Accent,
        contentColor = JrwiColors.AccentInk,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.loading || (state.buffering && state.playing.not() && !state.ended)) {
                CircularProgressIndicator(
                    color = JrwiColors.AccentInk,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size / 2),
                )
            } else {
                Icon(
                    painter =
                        painterResource(
                            if (state.playing) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription =
                        stringResource(
                            if (state.playing) CoreR.string.jrwi_player_pause else CoreR.string.play
                        ),
                    modifier = Modifier.size(size / 2),
                )
            }
        }
    }
}

/** A round arrow with how many seconds it goes, turned the other way to go forward. */
@Composable
private fun JrwiSeekIcon(seconds: Int, forward: Boolean, size: Dp = 24.dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_rotate_ccw),
            contentDescription =
                stringResource(
                    if (forward) CoreR.string.jrwi_player_forward
                    else CoreR.string.jrwi_player_back,
                    seconds,
                ),
            tint = JrwiColors.Text,
            modifier = Modifier.size(size).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            text = seconds.toString(),
            color = JrwiColors.Text,
            fontSize = (size.value * 0.27f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The sleep timer's button: a moon, or the time left on it once it's set. */
@Composable
private fun JrwiSleepButton(state: JrwiAudioState, onClick: () -> Unit) {
    val sleep = state.sleep
    val endsAt = sleep?.endsAtMs
    val now by rememberTicker(running = endsAt != null)
    JrwiSquareButton(
        icon = if (sleep != null) CoreR.drawable.ic_moon_filled else CoreR.drawable.ic_moon,
        contentDescription = stringResource(CoreR.string.jrwi_player_sleep),
        onClick = onClick,
        filled = sleep != null,
        modifier = Modifier.width(64.dp),
    ) {
        if (endsAt != null) {
            Text(
                text = formatDuration(max(0L, endsAt - now) / 1000.0),
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Icon(
                painter =
                    painterResource(
                        if (sleep != null) CoreR.drawable.ic_moon_filled else CoreR.drawable.ic_moon
                    ),
                contentDescription = stringResource(CoreR.string.jrwi_player_sleep),
            )
        }
    }
}

/** The phone's clock for counting down, read every second while [running]. */
@Composable
private fun rememberTicker(running: Boolean) =
    produceState(SystemClock.elapsedRealtime(), running) {
        value = SystemClock.elapsedRealtime()
        while (running) {
            delay(TICK_MILLIS)
            value = SystemClock.elapsedRealtime()
        }
    }

/** The speed: a step down or up, a bar, and the usual speeds to pick, as storii has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JrwiSpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = JrwiColors.Header) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.jrwi_player_speed),
                style = MaterialTheme.typography.titleMedium,
                color = JrwiColors.Text,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                JrwiSquareButton(
                    icon = CoreR.drawable.ic_minus_fat,
                    contentDescription = null,
                    onClick = { onSpeed(roundSpeed(speed - SPEED_STEP)) },
                    enabled = speed > JrwiAudioPlayer.MIN_SPEED,
                )
                Text(
                    text = speedLabel(speed),
                    style = MaterialTheme.typography.headlineMedium,
                    color = JrwiColors.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(110.dp),
                )
                JrwiSquareButton(
                    icon = CoreR.drawable.ic_plus,
                    contentDescription = null,
                    onClick = { onSpeed(roundSpeed(speed + SPEED_STEP)) },
                    enabled = speed < JrwiAudioPlayer.MAX_SPEED,
                )
            }
            // Moved with the finger, and set once it's let go
            var held by remember { mutableFloatStateOf(-1f) }
            Slider(
                value = if (held >= 0f) held else speed,
                onValueChange = { held = roundSpeed(it) },
                onValueChangeFinished = {
                    if (held >= 0f) onSpeed(held)
                    held = -1f
                },
                valueRange = JrwiAudioPlayer.MIN_SPEED..JrwiAudioPlayer.MAX_SPEED,
                steps =
                    ((JrwiAudioPlayer.MAX_SPEED - JrwiAudioPlayer.MIN_SPEED) / SPEED_STEP)
                        .roundToInt() - 1,
                colors =
                    SliderDefaults.colors(
                        thumbColor = JrwiColors.Accent,
                        activeTrackColor = JrwiColors.Accent,
                        inactiveTrackColor = JrwiBarTrack,
                        activeTickColor = JrwiColors.AccentInk.copy(alpha = 0.3f),
                        inactiveTickColor = JrwiColors.Muted.copy(alpha = 0.3f),
                    ),
            )
            FlowRow(
                horizontalArrangement =
                    Arrangement.spacedBy(
                        MaterialTheme.spacings.small,
                        Alignment.CenterHorizontally,
                    ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SPEED_PRESETS.forEach { preset ->
                    JrwiChoice(
                        text = speedLabel(preset),
                        selected = speed == preset,
                        onClick = { onSpeed(preset) },
                    )
                }
            }
        }
    }
}

/**
 * The sleep timer: a few lengths to pick or the end of the episode, or once it's set, the time left
 * on it, moving it by a few minutes, and turning it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JrwiSleepSheet(
    state: JrwiAudioState,
    onSleepIn: (Int) -> Unit,
    onSleepAtEnd: () -> Unit,
    onAdd: (Int) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sleep = state.sleep
    val endsAt = sleep?.endsAtMs
    val now by rememberTicker(running = endsAt != null)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = JrwiColors.Header) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.jrwi_player_sleep),
                style = MaterialTheme.typography.titleMedium,
                color = JrwiColors.Text,
            )
            when {
                endsAt != null -> {
                    Text(
                        text = formatDuration(max(0L, endsAt - now) / 1000.0),
                        style = MaterialTheme.typography.displaySmall,
                        color = JrwiColors.Text,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        SLEEP_STEPS.forEach { minutes ->
                            TextButton(onClick = { onAdd(minutes) }) {
                                Text(
                                    text =
                                        (if (minutes > 0) "+" else "−") +
                                            stringResource(
                                                CoreR.string.jrwi_player_minutes,
                                                abs(minutes),
                                            ),
                                    color = JrwiColors.Accent,
                                )
                            }
                        }
                    }
                    JrwiOffButton(onOff)
                }
                sleep?.atEnd == true -> {
                    Text(
                        text = stringResource(CoreR.string.jrwi_player_sleep_at_end),
                        color = JrwiColors.BodyText,
                    )
                    JrwiOffButton(onOff)
                }
                else -> {
                    FlowRow(
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                MaterialTheme.spacings.small,
                                Alignment.CenterHorizontally,
                            ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SLEEP_PRESETS.forEach { minutes ->
                            JrwiChoice(
                                text = stringResource(CoreR.string.jrwi_player_minutes, minutes),
                                selected = false,
                                onClick = { onSleepIn(minutes) },
                            )
                        }
                        JrwiChoice(
                            text = stringResource(CoreR.string.jrwi_player_sleep_end),
                            selected = false,
                            onClick = onSleepAtEnd,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JrwiOffButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = JrwiShape,
        border = JrwiGhostBorder,
        colors = jrwiGhostButtonColors(),
    ) {
        Text(stringResource(CoreR.string.jrwi_player_sleep_off))
    }
}

/** One of a few choices, as the site's pills: yellow when it's the one picked. */
@Composable
private fun JrwiChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = JrwiShape,
        color = if (selected) JrwiColors.Accent else JrwiTonal,
        contentColor = if (selected) JrwiColors.AccentInk else JrwiColors.Accent,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** How far through it is, from 0 to 1. */
private fun JrwiAudioState.fraction(): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** What's left, in the time it takes at the speed it plays at. */
private fun JrwiAudioState.leftAtSpeedMs(): Long =
    (max(0L, durationMs - positionMs) / speed.toDouble()).toLong()

/** Such as 1.25×. */
private fun speedLabel(speed: Float): String =
    "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "×"

private fun roundSpeed(speed: Float): Float =
    ((speed / SPEED_STEP).roundToInt() * SPEED_STEP)
        .coerceIn(JrwiAudioPlayer.MIN_SPEED, JrwiAudioPlayer.MAX_SPEED)
        .let { (it * 100).roundToInt() / 100f }

private val PLAYER_TOP_INSETS
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

private const val SPEED_STEP = 0.05f
private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)
private val SLEEP_STEPS = listOf(-5, 5, 15)
private const val TICK_MILLIS = 1000L
private const val OPEN_SWIPE_DP = 32
