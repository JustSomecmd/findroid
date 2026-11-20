package dev.jdtech.tubedroid.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.Tooltip
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * The player's controls as Jellyfin's: over the video, with the title and channel at the top and
 * the seek bar and buttons at the bottom, on shadows that fade into the picture. They show when the
 * mouse moves and hide, with the mouse, after a while of playing. Clicking the picture pauses;
 * double-clicking switches full screen. Skip and up next sit at the bottom right on their own.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerOsd(
    session: PlayerSession,
    onClose: () -> Unit,
    onOpenPlaylistPage: () -> Unit,
) {
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shown by remember { mutableStateOf(true) }
    var menus by remember { mutableIntStateOf(0) }
    val controlsHover = remember { MutableInteractionSource() }
    val onControls by controlsHover.collectIsHoveredAsState()
    val keepShowing = session.paused || menus > 0 || onControls || session.loading

    LaunchedEffect(Unit) {
        while (true) {
            shown = keepShowing || System.currentTimeMillis() - lastMove < OSD_HIDE_AFTER_MS
            delay(200)
        }
    }

    Box(
        modifier =
            Modifier.fillMaxSize()
                // Barely there, so the clicks on the picture land here rather than on mpv
                .background(Color.Black.copy(alpha = CLICK_CATCHER_ALPHA))
                .onPointerEvent(PointerEventType.Move) {
                    lastMove = System.currentTimeMillis()
                    shown = true
                }
                .pointerInput(session) {
                    detectTapGestures(
                        onTap = { session.togglePause() },
                        onDoubleTap = { session.toggleFullscreen() },
                    )
                }
                .pointerHoverIcon(if (shown) PointerIcon.Default else hiddenPointer)
    ) {
        if (session.loading && session.error == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
        }
        session.error?.let { error ->
            Text(
                text = error,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(Spacings.medium),
            )
        }

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            OsdTop(
                session = session,
                onClose = onClose,
                modifier =
                    Modifier.hoverable(controlsHover)
                        .background(
                            Brush.verticalGradient(
                                listOf(SHADE.copy(alpha = 0.75f), Color.Transparent)
                            )
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 48.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            OsdExtras(
                session = session,
                modifier = Modifier.padding(end = Spacings.default, bottom = Spacings.medium),
            )
            AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
                OsdBottom(
                    session = session,
                    onMenu = { open -> menus += if (open) 1 else -1 },
                    modifier =
                        Modifier.hoverable(controlsHover)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, SHADE.copy(alpha = 0.85f))
                                )
                            )
                            .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = session.playlistOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlaylistPanel(
                name = session.playlistName,
                videos = session.playlistVideos,
                currentVideoId = session.item?.id,
                loop = session.loop,
                onPlay = session::playFromPlaylist,
                onLoopChange = session::changeLoop,
                onOpenPage = onOpenPlaylistPage,
                modifier = Modifier.hoverable(controlsHover),
            )
        }
    }
}

/**
 * The top of the controls: back, the title, and the channel or show under it, smaller and in the
 * accent, then the playlist button when one plays.
 */
@Composable
internal fun OsdTop(session: PlayerSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val item = session.item
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        OsdButton(icon = "ic_arrow_left", label = "Back", onClick = onClose)
        Spacer(Modifier.width(Spacings.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item?.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (session.hasPlaylist) {
            OsdButton(
                icon = "ic_list_video",
                label = if (session.playlistOpen) "Close the playlist" else "Show the playlist",
                onClick = session::togglePlaylist,
                lit = session.playlistOpen,
            )
        }
    }
}

/**
 * The bottom of the controls, as Jellyfin's: the time, the seek bar with the chapters and the
 * length, then play and seeking in the middle, and subtitles, sound, settings and full screen at
 * the right.
 */
@Composable
internal fun OsdBottom(
    session: PlayerSession,
    onMenu: (open: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeText(formatDuration(session.position))
            SeekBar(
                position = session.position,
                duration = session.duration,
                chapters = session.chapters,
                onSeek = session::seekTo,
                modifier = Modifier.weight(1f).padding(horizontal = Spacings.medium),
            )
            TimeText(formatDuration(session.duration.coerceAtLeast(0.0)))
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                endsAt(session)?.let {
                    Text(
                        text = "Ends at $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                OsdButton(
                    icon = "ic_rewind",
                    label = "10 seconds back",
                    onClick = { session.seekBy(-10.0) },
                )
                OsdButton(
                    icon = if (session.paused) "ic_play" else "ic_pause",
                    label = if (session.paused) "Play" else "Pause",
                    onClick = session::togglePause,
                    size = 56.dp,
                )
                OsdButton(
                    icon = "ic_fast_forward",
                    label = "10 seconds forward",
                    onClick = { session.seekBy(10.0) },
                )
                session.upNext?.let { next ->
                    OsdButton(
                        icon = "ic_skip_forward",
                        label = "Next: $next",
                        onClick = session::playNext,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                if (session.subtitleTracks.isNotEmpty()) {
                    TrackMenu(
                        icon = "ic_closed_caption",
                        label = "Subtitles",
                        options =
                            listOf(
                                MenuOption("Off", session.subtitleId == null) {
                                    session.selectSubtitle(null)
                                }
                            ) +
                                session.subtitleTracks.map { track ->
                                    MenuOption(track.label, track.id == session.subtitleId) {
                                        session.selectSubtitle(track.id)
                                    }
                                },
                        onMenu = onMenu,
                        lit = session.subtitleId != null,
                    )
                }
                if (session.audioTracks.size > 1) {
                    TrackMenu(
                        icon = "ic_headphones",
                        label = "Audio",
                        options =
                            session.audioTracks.map { track ->
                                MenuOption(track.label, track.id == session.audioId) {
                                    session.selectAudio(track.id)
                                }
                            },
                        onMenu = onMenu,
                    )
                }
                TrackMenu(
                    icon = "ic_settings",
                    label = "Settings",
                    options =
                        PlayerSession.SPEEDS.map { speed ->
                            MenuOption(
                                "Speed ${speedLabel(speed)}",
                                kotlin.math.abs(speed - session.speed) < 0.01,
                            ) {
                                session.changeSpeed(speed)
                            }
                        } +
                            MenuOption("Playback info", session.statsShown, divided = true) {
                                session.toggleStats()
                            },
                    onMenu = onMenu,
                )
                OsdButton(
                    icon =
                        when {
                            session.muted || session.volume <= 0 -> "ic_volume_0"
                            session.volume < 50 -> "ic_volume_50"
                            else -> "ic_volume"
                        },
                    label = if (session.muted) "Sound on" else "Mute",
                    onClick = session::toggleMute,
                )
                ThinSlider(
                    value = if (session.muted) 0f else (session.volume / 100).toFloat(),
                    onChange = { session.changeVolume(it * 100.0) },
                    modifier = Modifier.width(96.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                OsdButton(
                    icon = if (session.fullscreen) "ic_minimize" else "ic_maximize",
                    label = if (session.fullscreen) "Leave full screen" else "Full screen",
                    onClick = session::toggleFullscreen,
                )
            }
        }
    }
}

/**
 * What shows at the bottom right whether or not the controls do: skipping a sponsor segment, and
 * the next video as the current one ends, as on Jellyfin.
 */
@Composable
internal fun OsdExtras(session: PlayerSession, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier = modifier,
    ) {
        session.segment?.let { segment ->
            FilledTonalButton(onClick = { session.skip(segment) }) {
                Text("Skip ${PlayerSession.categoryName(segment.category).lowercase()}")
                Spacer(Modifier.width(Spacings.small))
                AppIcon(
                    name = "ic_skip_forward",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (session.showUpNext) {
            val next = session.upNext.orEmpty()
            val seconds = (session.duration - session.position).toInt().coerceAtLeast(0)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                Column(modifier = Modifier.padding(Spacings.medium)) {
                    Text(
                        text = "Up next in $seconds s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = next,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, bottom = Spacings.small),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                        Button(onClick = session::playNext) { Text("Play now") }
                        TextButton(onClick = session::cancelUpNext) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** A button of the controls: a white icon on the dark, which names itself on hover. */
@Composable
internal fun OsdButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    lit: Boolean = false,
) {
    Tooltip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        ) {
            AppIcon(
                name = icon,
                contentDescription = label,
                tint = if (lit) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** One choice of a menu of the controls, ticked when it's the current one. */
internal data class MenuOption(
    val label: String,
    val selected: Boolean,
    val divided: Boolean = false,
    val onClick: () -> Unit,
)

/** A button of the controls that opens a menu, such as the subtitles to choose from. */
@Composable
private fun TrackMenu(
    icon: String,
    label: String,
    options: List<MenuOption>,
    onMenu: (open: Boolean) -> Unit,
    lit: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    fun setOpen(value: Boolean) {
        if (value != open) onMenu(value)
        open = value
    }
    Box {
        OsdButton(icon = icon, label = label, onClick = { setOpen(true) }, lit = lit)
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            options.forEach { option ->
                if (option.divided) HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            fontWeight = if (option.selected) FontWeight.SemiBold else null,
                        )
                    },
                    leadingIcon = {
                        Box(Modifier.size(18.dp)) {
                            if (option.selected) {
                                AppIcon(
                                    name = "ic_check",
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        setOpen(false)
                        option.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The seek bar, thin as Jellyfin's: what's been played in the accent, a tick at each chapter, and
 * the time under the mouse above it. Clicking or dragging seeks.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SeekBar(
    position: Double,
    duration: Double,
    chapters: List<Double>,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val length = duration.coerceAtLeast(0.0)
    BoxWithConstraints(
        modifier =
            modifier
                .height(28.dp)
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Move) { event ->
                    hoverX = event.changes.firstOrNull()?.position?.x
                }
                .onPointerEvent(PointerEventType.Exit) { hoverX = null }
                .pointerInput(length) {
                    detectTapGestures { offset ->
                        if (length > 0) onSeek(length * (offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(length) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging?.let { if (length > 0) onSeek(length * it) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
    ) {
        val fraction =
            dragging ?: if (length > 0) (position / length).toFloat().coerceIn(0f, 1f) else 0f
        val thick = hovered || dragging != null
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barHeight = if (thick) 6.dp.toPx() else 4.dp.toPx()
            val top = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, top),
                size = Size(size.width * fraction, barHeight),
                cornerRadius = radius,
            )
            if (length > 0) {
                chapters
                    .filter { it > 0 && it < length }
                    .forEach { start ->
                        val x = size.width * (start / length).toFloat()
                        drawRect(
                            color = Color.Black.copy(alpha = 0.7f),
                            topLeft = Offset(x - 1.dp.toPx(), top),
                            size = Size(2.dp.toPx(), barHeight),
                        )
                    }
            }
            if (thick) {
                drawCircle(
                    color = accent,
                    radius = 8.dp.toPx(),
                    center = Offset(size.width * fraction, size.height / 2),
                )
            }
        }
        val pointed = dragging?.let { it * constraints.maxWidth } ?: hoverX
        if (pointed != null && length > 0) {
            val seconds = length * (pointed / constraints.maxWidth).coerceIn(0f, 1f)
            val x = with(LocalDensity.current) { pointed.toDp() }
            Text(
                text = formatDuration(seconds),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier.offset(x = x - 22.dp, y = (-26).dp)
                        .background(SHADE.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** A thin bar to set a level with, such as the volume. */
@Composable
internal fun ThinSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            modifier
                .height(28.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onChange((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barHeight = 4.dp.toPx()
            val top = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, top),
                size = Size(size.width * value.coerceIn(0f, 1f), barHeight),
                cornerRadius = radius,
            )
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(size.width * value.coerceIn(0f, 1f), size.height / 2),
            )
        }
    }
}

@Composable
private fun TimeText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

/** When the video ends if it plays on from here at its speed, such as 22:45, as Jellyfin shows. */
private fun endsAt(session: PlayerSession): String? {
    val left = session.duration - session.position
    if (session.duration <= 0 || left <= 0) return null
    val seconds = (left / session.speed.coerceAtLeast(0.1)).toLong()
    return LocalTime.now().plusSeconds(seconds).format(DateTimeFormatter.ofPattern("HH:mm"))
}

internal fun speedLabel(speed: Double): String = "${speed.toString().removeSuffix(".0")}x"

/** The shade under the controls. */
private val SHADE = Color(0xFF000000)

/** Clicks on a see-through window go through it, so the picture is covered this little. */
private const val CLICK_CATCHER_ALPHA = 0.012f

private const val OSD_HIDE_AFTER_MS = 3_000L

/** No pointer, for while the controls are hidden over a playing video. */
private val hiddenPointer: PointerIcon by lazy {
    try {
        PointerIcon(
            Toolkit.getDefaultToolkit()
                .createCustomCursor(
                    BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                    Point(0, 0),
                    "none",
                )
        )
    } catch (e: Exception) {
        PointerIcon.Default
    }
}
