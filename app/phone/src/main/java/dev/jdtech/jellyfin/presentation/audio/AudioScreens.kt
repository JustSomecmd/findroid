package dev.jdtech.jellyfin.presentation.audio

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import dev.jdtech.jellyfin.audio.AudioEpisode
import dev.jdtech.jellyfin.audio.AudioPlayer
import dev.jdtech.jellyfin.audio.AudioSegment
import dev.jdtech.jellyfin.audio.AudioState
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.jrwi.JRWI_BAR_HEIGHT
import dev.jdtech.jellyfin.presentation.jrwi.JrwiBarTrack
import dev.jdtech.jellyfin.presentation.jrwi.JrwiColors
import dev.jdtech.jellyfin.presentation.jrwi.JrwiGhostBorder
import dev.jdtech.jellyfin.presentation.jrwi.JrwiImage
import dev.jdtech.jellyfin.presentation.jrwi.JrwiPurple
import dev.jdtech.jellyfin.presentation.jrwi.JrwiScaffold
import dev.jdtech.jellyfin.presentation.jrwi.JrwiShape
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTheme
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTonal
import dev.jdtech.jellyfin.presentation.jrwi.jrwiGhostButtonColors
import dev.jdtech.jellyfin.presentation.jrwi.jrwiTopBarColors
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app's one audio player, for the pages that show or control it. */
@HiltViewModel class AudioViewModel @Inject constructor(val player: AudioPlayer) : ViewModel()

/**
 * The player's colours and shapes: for JRWI audio the archiver's colours, its yellow buttons with
 * square corners, and JRWI mode's purple on the bars; for a TubeArchivist video the app's own
 * colours and round buttons.
 */
@Immutable
private class PlayerLook(
    val jrwi: Boolean,
    /** The bars, sliders and spinner. */
    val accent: Color,
    val onAccent: Color,
    /** The buttons: filled, and the quieter ones next to them. */
    val button: Color,
    val onButton: Color,
    val tonal: Color,
    val onTonal: Color,
    val text: Color,
    val muted: Color,
    val track: Color,
    /** Behind the mini player. */
    val bar: Color,
    /** Behind the speed and sleep sheets. */
    val sheet: Color,
    val picture: Color,
    val buttonShape: Shape,
    val pictureShape: Shape,
)

private val LocalPlayerLook = staticCompositionLocalOf<PlayerLook> { error("No player look") }

// The archiver's yellow buttons, with JRWI mode's purple, as the bottom bar lights up in, on the
// bars
private val JrwiLook =
    PlayerLook(
        jrwi = true,
        accent = JrwiPurple.Accent,
        onAccent = JrwiPurple.Ink,
        button = JrwiColors.Accent,
        onButton = JrwiColors.AccentInk,
        tonal = JrwiTonal,
        onTonal = JrwiColors.Accent,
        text = JrwiColors.Text,
        muted = JrwiColors.Muted,
        track = JrwiBarTrack,
        bar = JrwiColors.Header,
        sheet = JrwiColors.Header,
        picture = JrwiColors.Picture,
        buttonShape = JrwiShape,
        pictureShape = JrwiShape,
    )

/** In the archiver's look for JRWI audio, or the app's for a video's sound. */
@Composable
private fun PlayerLookTheme(appLook: Boolean, content: @Composable () -> Unit) {
    if (appLook) {
        val colors = MaterialTheme.colorScheme
        val look =
            remember(colors) {
                PlayerLook(
                    jrwi = false,
                    accent = colors.primary,
                    onAccent = colors.onPrimary,
                    button = colors.primary,
                    onButton = colors.onPrimary,
                    tonal = colors.secondaryContainer,
                    onTonal = colors.onSecondaryContainer,
                    text = colors.onSurface,
                    muted = colors.onSurfaceVariant,
                    track = colors.surfaceContainerHighest,
                    bar = colors.surfaceContainer,
                    sheet = colors.surfaceContainerLow,
                    picture = colors.surfaceContainerHighest,
                    buttonShape = CircleShape,
                    pictureShape = RoundedCornerShape(12.dp),
                )
            }
        CompositionLocalProvider(LocalPlayerLook provides look, content = content)
    } else {
        JrwiTheme {
            // What takes the theme's accent, such as the sheets' switches, in the purple too
            MaterialTheme(
                colorScheme =
                    MaterialTheme.colorScheme.copy(
                        primary = JrwiPurple.Accent,
                        onPrimary = JrwiPurple.Ink,
                        primaryContainer = JrwiPurple.Accent,
                        onPrimaryContainer = JrwiPurple.Ink,
                        inversePrimary = JrwiPurple.Accent,
                    ),
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
            ) {
                CompositionLocalProvider(LocalPlayerLook provides JrwiLook, content = content)
            }
        }
    }
}

/**
 * The audio that's loaded, at the bottom of every page but the player's own, as storii has it: the
 * episode's picture, its title and time left, back as far as the video player goes, play or pause,
 * and close. A tap or a swipe up opens the player.
 */
@Composable
fun AudioMiniPlayer(
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioViewModel = hiltViewModel(),
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
        PlayerLookTheme(appLook = state.appLook) {
            val look = LocalPlayerLook.current
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(look.bar)
                        // Under the bottom bar there's no gap; without the bar, above the phone's
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
            ) {
                // How far it is, along the top
                Box(Modifier.fillMaxWidth().height(2.dp).background(look.track)) {
                    Box(
                        Modifier.fillMaxWidth(state.fraction())
                            .fillMaxHeight()
                            .background(look.accent)
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
                        modifier =
                            Modifier.width(64.dp)
                                .aspectRatio(16f / 9f)
                                .clip(if (look.jrwi) JrwiShape else RoundedCornerShape(6.dp)),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            text = state.episode?.title.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = look.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = miniLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (state.error != null) MaterialTheme.colorScheme.error
                                else look.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = player::skipBack) {
                        SeekIcon(seconds = (player.backMs / 1000).toInt(), forward = false)
                    }
                    PlayPauseButton(state = state, onClick = player::toggle, size = 40.dp)
                    IconButton(onClick = { player.stop() }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_x),
                            contentDescription = stringResource(CoreR.string.jrwi_player_close),
                            tint = look.muted,
                        )
                    }
                }
            }
        }
    }
}

/** "12:04 · 23 mins left", or that it's loading or couldn't play. */
@Composable
private fun miniLine(state: AudioState): String =
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
 * The audio player, a page of its own, after storii's: the episode's picture, title and show or
 * channel, a bar to seek along with the time gone and the time left at the speed it plays at (or
 * the whole length, with a tap), back and forward as far as the video player goes around play, then
 * the speed, the sleep timer, the playlist's next video, and the video from the same moment. It
 * sits in the middle of the page below the top bar. JRWI audio is in the archiver's look, a video's
 * sound in the app's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    onWatchVideo: (episode: AudioEpisode, positionMs: Long?) -> Unit,
    onOpenEpisode: (AudioEpisode) -> Unit,
    navigateBack: () -> Unit,
    viewModel: AudioViewModel = hiltViewModel(),
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
    val episode = state.episode

    PlayerLookTheme(appLook = state.appLook) {
        val look = LocalPlayerLook.current
        PlayerScaffold(
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
                        if (episode != null) {
                            IconButton(onClick = { onOpenEpisode(episode) }) {
                                Icon(
                                    painterResource(CoreR.drawable.ic_info),
                                    contentDescription =
                                        stringResource(
                                            if (episode is AudioEpisode.Video)
                                                CoreR.string.audio_player_video_page
                                            else CoreR.string.jrwi_player_episode
                                        ),
                                )
                            }
                        }
                    },
                    // As tall as JRWI Home's header
                    expandedHeight = JRWI_BAR_HEIGHT,
                    windowInsets = PLAYER_TOP_INSETS,
                    colors =
                        if (look.jrwi) jrwiTopBarColors()
                        else
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                )
            }
        ) { innerPadding ->
            if (!state.active) {
                Box(
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(CoreR.string.jrwi_player_nothing),
                        color = look.muted,
                    )
                }
                return@PlayerScaffold
            }
            // Under the top bar with the padding it had before it was centred, the bar taller so
            // it sits a little lower than then; scrolling when it doesn't fit
            BoxWithConstraints(
                modifier =
                    Modifier.padding(innerPadding)
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                    modifier =
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = 24.dp, vertical = MaterialTheme.spacings.medium),
                ) {
                    Box(
                        modifier =
                            Modifier.widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(look.pictureShape)
                                .background(look.picture)
                    ) {
                        JrwiImage(path = state.picture, modifier = Modifier.fillMaxSize())
                        if (state.loading) {
                            CircularProgressIndicator(
                                color = look.accent,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = episode?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = look.text,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle =
                        listOfNotNull(
                                state.subtitle,
                                if (state.fromPhone)
                                    stringResource(CoreR.string.jrwi_player_from_phone)
                                else null,
                            )
                            .joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = look.muted,
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
                    SeekBar(
                        state = state,
                        showLength = showLength,
                        onToggleEnd = { showLength = !showLength },
                        onSeek = player::seekTo,
                    )
                    // A segment, when the video player's settings ask for a button rather than
                    // skipping
                    state.segment?.let { segment ->
                        Button(
                            onClick = player::skipSegment,
                            shape = look.buttonShape,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = look.button,
                                    contentColor = look.onButton,
                                ),
                            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                        ) {
                            Text(stringResource(skipLabel(segment)))
                        }
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = player::skipBack, modifier = Modifier.size(56.dp)) {
                            SeekIcon(
                                seconds = (player.backMs / 1000).toInt(),
                                forward = false,
                                size = 40.dp,
                            )
                        }
                        PlayPauseButton(state = state, onClick = player::toggle, size = 72.dp)
                        IconButton(onClick = player::skipForward, modifier = Modifier.size(56.dp)) {
                            SeekIcon(
                                seconds = (player.forwardMs / 1000).toInt(),
                                forward = true,
                                size = 40.dp,
                            )
                        }
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.large))
                    // All as wide, so the row sits centred under Play
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                    ) {
                        PlayerButton(
                            icon = CoreR.drawable.ic_gauge,
                            contentDescription = stringResource(CoreR.string.jrwi_player_speed),
                            onClick = { speedOpen = true },
                            filled = state.speed != 1f,
                            modifier = Modifier.width(ROW_BUTTON_WIDTH),
                        ) {
                            Text(
                                text = speedLabel(state.speed),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        SleepButton(state = state, onClick = { sleepOpen = true })
                        if (state.hasNext) {
                            PlayerButton(
                                icon = CoreR.drawable.ic_skip_forward,
                                contentDescription = stringResource(CoreR.string.audio_player_next),
                                onClick = player::skipToNext,
                                modifier = Modifier.width(ROW_BUTTON_WIDTH),
                            )
                        }
                        val hasVideo =
                            when (episode) {
                                is AudioEpisode.Jrwi -> episode.item.video != null
                                is AudioEpisode.Video -> true
                                null -> false
                            }
                        if (episode != null && hasVideo) {
                            PlayerButton(
                                icon = CoreR.drawable.ic_film,
                                contentDescription =
                                    stringResource(CoreR.string.jrwi_player_watch_video),
                                onClick = {
                                    leaving = true
                                    scope.launch {
                                        val position = player.closeForVideo()
                                        onWatchVideo(episode, position)
                                    }
                                },
                                modifier = Modifier.width(ROW_BUTTON_WIDTH),
                            )
                        }
                    }
                }
            }
        }
        if (speedOpen) {
            SpeedSheet(
                speed = state.speed,
                onSpeed = player::setSpeed,
                onDismiss = { speedOpen = false },
            )
        }
        if (sleepOpen) {
            SleepSheet(
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

/** The page: the archiver's navy with its glow, or the app's background. */
@Composable
private fun PlayerScaffold(
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalPlayerLook.current.jrwi) {
        JrwiScaffold(topBar = topBar, contentWindowInsets = PLAYER_TOP_INSETS, content = content)
    } else {
        Scaffold(
            topBar = topBar,
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = PLAYER_TOP_INSETS,
            content = content,
        )
    }
}

/** The video player's words for skipping each kind of segment, SponsorBlock's or Jellyfin's. */
private fun skipLabel(segment: AudioSegment): Int =
    when (segment.category ?: segment.type) {
        "sponsor" -> PlayerR.string.player_controls_skip_sponsor
        "selfpromo" -> PlayerR.string.player_controls_skip_selfpromo
        "interaction" -> PlayerR.string.player_controls_skip_interaction
        "intro" -> PlayerR.string.player_controls_skip_intro
        "outro" -> PlayerR.string.player_controls_skip_outro
        "preview" -> PlayerR.string.player_controls_skip_preview
        "music_offtopic" -> PlayerR.string.player_controls_skip_music_offtopic
        "filler" -> PlayerR.string.player_controls_skip_filler
        // Jellyfin's media segments
        "INTRO" -> PlayerR.string.player_controls_skip_intro
        "OUTRO" -> PlayerR.string.player_controls_skip_outro
        "RECAP" -> PlayerR.string.player_controls_skip_recap
        "COMMERCIAL" -> PlayerR.string.player_controls_skip_commercial
        "PREVIEW" -> PlayerR.string.player_controls_skip_preview
        else -> PlayerR.string.player_controls_skip_unknown
    }

/** The bar to seek along, with the time gone under its start and the time left under its end. */
@Composable
private fun SeekBar(
    state: AudioState,
    showLength: Boolean,
    onToggleEnd: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val look = LocalPlayerLook.current
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
                    thumbColor = look.accent,
                    activeTrackColor = look.accent,
                    inactiveTrackColor = look.track,
                    disabledThumbColor = look.muted,
                    disabledInactiveTrackColor = look.track,
                ),
        )
        val shownMs = held?.let { (it * duration).toLong() } ?: state.positionMs
        Row(modifier = Modifier.fillMaxWidth()) {
            val labelStyle = MaterialTheme.typography.labelMedium
            Text(text = formatDuration(shownMs / 1000.0), style = labelStyle, color = look.muted)
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
                color = look.muted,
                modifier = Modifier.clip(look.buttonShape).clickable(onClick = onToggleEnd),
            )
        }
    }
}

/**
 * Play or pause in the buttons' colour, square or round as the look has it, with a spinner while it
 * loads.
 */
@Composable
private fun PlayPauseButton(state: AudioState, onClick: () -> Unit, size: Dp) {
    val look = LocalPlayerLook.current
    Surface(
        onClick = onClick,
        enabled = !state.loading,
        shape = look.buttonShape,
        color = look.button,
        contentColor = look.onButton,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.loading || (state.buffering && !state.playing && !state.ended)) {
                CircularProgressIndicator(
                    color = look.onButton,
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

/**
 * One of the player's buttons: [filled] in the accent for one that's on, such as a set sleep timer,
 * or else tonal. Square for JRWI audio, round for a TubeArchivist video.
 */
@Composable
private fun PlayerButton(
    icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val look = LocalPlayerLook.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = look.buttonShape,
        color = if (filled) look.button else look.tonal,
        contentColor = if (filled) look.onButton else look.onTonal,
        modifier = modifier.size(BUTTON_SIZE).alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (content != null) content()
            else Icon(painterResource(icon), contentDescription = contentDescription)
        }
    }
}

/** A round arrow with how many seconds it goes, turned the other way to go forward. */
@Composable
private fun SeekIcon(seconds: Int, forward: Boolean, size: Dp = 24.dp) {
    val color = LocalPlayerLook.current.text
    Box(contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_rotate_ccw),
            contentDescription =
                stringResource(
                    if (forward) CoreR.string.jrwi_player_forward
                    else CoreR.string.jrwi_player_back,
                    seconds,
                ),
            tint = color,
            modifier = Modifier.size(size).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            text = seconds.toString(),
            color = color,
            fontSize = (size.value * 0.27f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The sleep timer's button: a moon, or the time left on it once it's set. */
@Composable
private fun SleepButton(state: AudioState, onClick: () -> Unit) {
    val sleep = state.sleep
    val endsAt = sleep?.endsAtMs
    val now by rememberTicker(running = endsAt != null)
    PlayerButton(
        icon = if (sleep != null) CoreR.drawable.ic_moon_filled else CoreR.drawable.ic_moon,
        contentDescription = stringResource(CoreR.string.jrwi_player_sleep),
        onClick = onClick,
        filled = sleep != null,
        modifier = Modifier.width(ROW_BUTTON_WIDTH),
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
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val look = LocalPlayerLook.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = look.sheet) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.jrwi_player_speed),
                style = MaterialTheme.typography.titleMedium,
                color = look.text,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                PlayerButton(
                    icon = CoreR.drawable.ic_minus_fat,
                    contentDescription = null,
                    onClick = { onSpeed(roundSpeed(speed - SPEED_STEP)) },
                    enabled = speed > AudioPlayer.MIN_SPEED,
                )
                Text(
                    text = speedLabel(speed),
                    style = MaterialTheme.typography.headlineMedium,
                    color = look.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(110.dp),
                )
                PlayerButton(
                    icon = CoreR.drawable.ic_plus,
                    contentDescription = null,
                    onClick = { onSpeed(roundSpeed(speed + SPEED_STEP)) },
                    enabled = speed < AudioPlayer.MAX_SPEED,
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
                valueRange = AudioPlayer.MIN_SPEED..AudioPlayer.MAX_SPEED,
                steps =
                    ((AudioPlayer.MAX_SPEED - AudioPlayer.MIN_SPEED) / SPEED_STEP).roundToInt() - 1,
                colors =
                    SliderDefaults.colors(
                        thumbColor = look.accent,
                        activeTrackColor = look.accent,
                        inactiveTrackColor = look.track,
                        activeTickColor = look.onAccent.copy(alpha = 0.3f),
                        inactiveTickColor = look.muted.copy(alpha = 0.3f),
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
                    Choice(
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
private fun SleepSheet(
    state: AudioState,
    onSleepIn: (Int) -> Unit,
    onSleepAtEnd: () -> Unit,
    onAdd: (Int) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val look = LocalPlayerLook.current
    val sleep = state.sleep
    val endsAt = sleep?.endsAtMs
    val now by rememberTicker(running = endsAt != null)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = look.sheet) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.jrwi_player_sleep),
                style = MaterialTheme.typography.titleMedium,
                color = look.text,
            )
            when {
                endsAt != null -> {
                    Text(
                        text = formatDuration(max(0L, endsAt - now) / 1000.0),
                        style = MaterialTheme.typography.displaySmall,
                        color = look.text,
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
                                    color = look.button,
                                )
                            }
                        }
                    }
                    OffButton(onOff)
                }
                sleep?.atEnd == true -> {
                    Text(
                        text = stringResource(CoreR.string.jrwi_player_sleep_at_end),
                        color = look.text,
                    )
                    OffButton(onOff)
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
                            Choice(
                                text = stringResource(CoreR.string.jrwi_player_minutes, minutes),
                                selected = false,
                                onClick = { onSleepIn(minutes) },
                            )
                        }
                        Choice(
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

/** Turns the sleep timer off: the archiver's outlined button, or the app's. */
@Composable
private fun OffButton(onClick: () -> Unit) {
    if (LocalPlayerLook.current.jrwi) {
        OutlinedButton(
            onClick = onClick,
            shape = JrwiShape,
            border = JrwiGhostBorder,
            colors = jrwiGhostButtonColors(),
        ) {
            Text(stringResource(CoreR.string.jrwi_player_sleep_off))
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(stringResource(CoreR.string.jrwi_player_sleep_off))
        }
    }
}

/** One of a few choices, as pills: in the accent when it's the one picked. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val look = LocalPlayerLook.current
    Surface(
        onClick = onClick,
        shape = look.buttonShape,
        color = if (selected) look.button else look.tonal,
        contentColor = if (selected) look.onButton else look.onTonal,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** How far through it is, from 0 to 1. */
private fun AudioState.fraction(): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** What's left, in the time it takes at the speed it plays at. */
private fun AudioState.leftAtSpeedMs(): Long =
    (max(0L, durationMs - positionMs) / speed.toDouble()).toLong()

/** Such as 1.25×. */
private fun speedLabel(speed: Float): String =
    "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "×"

private fun roundSpeed(speed: Float): Float =
    ((speed / SPEED_STEP).roundToInt() * SPEED_STEP)
        .coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED)
        .let { (it * 100).roundToInt() / 100f }

private val PLAYER_TOP_INSETS
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

private val BUTTON_SIZE = 48.dp
// The buttons under Play, wide enough for the speed and the sleep timer's time left
private val ROW_BUTTON_WIDTH = 64.dp
private const val DISABLED_ALPHA = 0.38f
private const val SPEED_STEP = 0.05f
private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)
private val SLEEP_STEPS = listOf(-5, 5, 15)
private const val TICK_MILLIS = 1000L
private const val OPEN_SWIPE_DP = 32
