package dev.jdtech.tubedroid.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.sun.jna.Native
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.AppSettings
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.LocalWindowState
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import java.awt.Canvas
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A SponsorBlock segment that can be skipped, in seconds. */
internal data class SkipSegment(val start: Double, val end: Double, val category: String)

/** For saving progress after the player has gone, such as when it closes. */
private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The video playing, mpv's state as last read, and what happens around it: saving progress where
 * the video came from, SponsorBlock, and going on to the next video or episode.
 */
internal class PlayerSession(
    private val source: PlaybackSource,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    var player by mutableStateOf<MpvPlayer?>(null)
    var item by mutableStateOf<PlayItem?>(null)
        private set

    /** The title of what plays next, if anything does. */
    var upNext by mutableStateOf<String?>(null)
        private set

    /** Whether the playlist starts over after its last video, as on YouTube. */
    var loop by mutableStateOf(false)
        private set

    /** Whether a playlist is playing, which can loop and be shown beside the video. */
    val hasPlaylist: Boolean
        get() = item != null && source.isPlaylist

    /** Whether the playlist is open beside the video. */
    var playlistOpen by mutableStateOf(false)
        private set

    /** The playlist's name and videos, kept here so the playlist redraws as they load. */
    var playlistName by mutableStateOf("")
        private set

    var playlistVideos by mutableStateOf<List<TaVideo>>(emptyList())
        private set

    /** Opens or closes the playlist, loading it again as it opens. */
    fun togglePlaylist() {
        playlistOpen = !playlistOpen
        if (!playlistOpen) return
        scope.launch {
            try {
                source.refreshPlaylist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The copy from when the video started will do
            }
            showPlaylist()
        }
    }

    private fun showPlaylist() {
        playlistName = source.playlistName
        playlistVideos = source.playlistVideos
    }

    /** Plays one of the playlist's videos, picked from the playlist. */
    fun playFromPlaylist(videoId: String) {
        val current = item ?: return
        if (current.id == videoId) return
        reportProgress(position, paused = true)
        scope.launch {
            try {
                val picked = source.playlistItem(videoId) ?: return@launch
                item = picked
                started = null
                startIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
        }
    }

    /** Turns starting the playlist over after its last video on or off. */
    fun changeLoop(on: Boolean) {
        loop = on
        source.loop = on
        item?.let(::loadUpNext)
    }

    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var position by mutableStateOf(0.0)
        private set

    var duration by mutableStateOf(0.0)
        private set

    var fullscreen by mutableStateOf(false)
    var segment by mutableStateOf<SkipSegment?>(null)
        private set

    var ended by mutableStateOf(false)
        private set

    private var skippedSegment: SkipSegment? = null
    private var lastReport = 0L
    private var lastReported = -1.0
    private var started: String? = null

    suspend fun load() {
        try {
            val first = source.first()
            showPlaylist()
            item = first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
            loading = false
        }
    }

    /** Starts the item once both it and mpv are ready. */
    fun startIfReady() {
        val player = player ?: return
        val item = item ?: return
        if (started == item.id) return
        started = item.id
        skippedSegment = null
        segment = null
        ended = false
        upNext = null
        position = item.resumeSeconds ?: 0.0
        lastReported = -1.0
        lastReport = System.currentTimeMillis()
        loading = true
        error = null
        player.set(
            "start",
            item.resumeSeconds?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "none",
        )
        player.set("force-media-title", item.title)
        // One header as it is, commas and all, rather than a list split at the commas
        player.command("change-list", "http-header-fields", "clr", "")
        item.header?.let { player.command("change-list", "http-header-fields", "append", it) }
        player.command("loadfile", item.url, "replace")
        loadUpNext(item)
    }

    /** Finds what plays after [item], for the button that plays it and for autoplay. */
    private fun loadUpNext(item: PlayItem) {
        scope.launch {
            upNext =
                try {
                    source.nextTitle(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
        }
    }

    /** Adds the subtitle files next to the video, once it has loaded, and says it started. */
    fun onFileLoaded() {
        loading = false
        val player = player ?: return
        val item = item ?: return
        item.subtitles.forEach { subtitle ->
            player.command("sub-add", subtitle.url, "auto", subtitle.title, subtitle.language ?: "")
        }
        val at = item.resumeSeconds ?: 0.0
        backgroundScope.launch { runCatching { source.started(item, at) } }
    }

    fun onLoadFailed(message: String) {
        loading = false
        error = "Couldn't play this: $message"
    }

    /** Reads mpv's state, then acts on it. Called a few times a second. */
    fun tick(onFullscreen: (Boolean) -> Unit) {
        val player = player ?: return
        player.getDouble("time-pos")?.let { position = it }
        player.getDouble("duration")?.let { duration = it }
        val wantsFullscreen = player.getFlag("fullscreen")
        if (wantsFullscreen != fullscreen) {
            fullscreen = wantsFullscreen
            onFullscreen(wantsFullscreen)
        }
        // Until the new file has loaded, mpv's state can still be the last one's
        if (loading) return
        checkSegment(player)
        val paused = player.getFlag("pause")
        val now = System.currentTimeMillis()
        if (!paused && now - lastReport > PROGRESS_INTERVAL_MS) {
            lastReport = now
            reportProgress(position, paused = false)
        }
        val atEnd = player.getFlag("eof-reached")
        if (atEnd && !ended) {
            ended = true
            onEnded()
        } else if (!atEnd && ended) {
            // Gone back from the end
            ended = false
        }
    }

    private fun checkSegment(player: MpvPlayer) {
        val segments = item?.segments.orEmpty()
        val current = segments.firstOrNull { position >= it.start && position < it.end - 0.5 }
        if (current == null) {
            segment = null
            return
        }
        if (current == skippedSegment) return
        if (settings.sponsorBlock == AppSettings.SPONSORBLOCK_AUTO) {
            skippedSegment = current
            skip(current)
            player.command("show-text", "Skipped: ${categoryName(current.category)}", "2000")
        } else if (segment != current) {
            segment = current
            // So it can be skipped from the keyboard, also in full screen
            player.command("keybind", "ENTER", "seek ${current.end} absolute")
            player.command(
                "show-text",
                "${categoryName(current.category)}: press Enter to skip",
                "4000",
            )
        }
    }

    fun skip(target: SkipSegment? = segment) {
        val skipping = target ?: return
        skippedSegment = skipping
        segment = null
        player?.command("seek", skipping.end.toString(), "absolute")
        player?.command("keybind", "ENTER", "ignore")
    }

    private fun onEnded() {
        val current = item ?: return
        val length = duration.takeIf { it > 0 } ?: position
        reportProgress(length, paused = true)
        backgroundScope.launch { runCatching { source.ended(current) } }
        if (settings.autoplay && upNext != null) {
            scope.launch {
                delay(1500)
                if (ended && item == current) playNext()
            }
        }
    }

    fun playNext() {
        val current = item ?: return
        reportProgress(position, paused = true)
        scope.launch {
            try {
                val next = source.next(current) ?: return@launch
                item = next
                // A looping playlist of one video plays the same one again
                started = null
                startIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            }
        }
    }

    private fun reportProgress(seconds: Double, paused: Boolean) {
        val current = item ?: return
        if (seconds <= 0 || seconds == lastReported) return
        lastReported = seconds
        backgroundScope.launch { runCatching { source.progress(current, seconds, paused) } }
    }

    /** Saves where it got to and stops mpv. Called before its window goes. */
    fun release() {
        val player = player ?: return
        this.player = null
        val last = player.getDouble("time-pos")
        val current = item
        if (last != null && current != null) {
            backgroundScope.launch { runCatching { source.stopped(current, last) } }
        }
        player.destroy()
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 10_000L

        fun categoryName(category: String): String =
            when (category) {
                "sponsor" -> "Sponsor"
                "selfpromo" -> "Self promotion"
                "interaction" -> "Interaction reminder"
                "intro" -> "Intro"
                "outro" -> "Outro"
                "preview" -> "Preview"
                "music_offtopic" -> "Non-music section"
                "filler" -> "Filler"
                else -> category.replaceFirstChar { it.uppercase() }
            }
    }
}

/**
 * Plays a video with mpv, drawn inside the window. mpv's own controls show on the video, its
 * keyboard shortcuts work once the video is clicked, and its double click goes full screen, as in
 * Jellyfin Media Player.
 */
@Composable
fun PlayerScreen(request: PlayerRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val windowState = LocalWindowState.current
    val scope = rememberCoroutineScope()
    val session =
        remember(request) {
            val source =
                when (request) {
                    is PlayerRequest.Ta ->
                        TaPlaybackSource(app.api, app.settings, app.history, request)
                    is PlayerRequest.Jellyfin -> JellyfinPlaybackSource(app.jellyfin, request)
                }
            PlayerSession(source, app.settings, scope)
        }
    val close by rememberUpdatedState(onClose)
    val lib = remember { MpvLoader.load() }
    val focus = remember { FocusRequester() }
    var placementBefore by remember { mutableStateOf(windowState.placement) }

    fun setFullscreen(on: Boolean) {
        if (on) {
            if (windowState.placement != WindowPlacement.Fullscreen)
                placementBefore = windowState.placement
            windowState.placement = WindowPlacement.Fullscreen
        } else if (windowState.placement == WindowPlacement.Fullscreen) {
            windowState.placement =
                placementBefore.takeIf { it != WindowPlacement.Fullscreen }
                    ?: WindowPlacement.Floating
        }
    }

    LaunchedEffect(session) { session.load() }
    LaunchedEffect(session, session.player, session.item) { session.startIfReady() }
    LaunchedEffect(session, session.player) {
        if (session.player == null) return@LaunchedEffect
        while (isActive) {
            session.tick(onFullscreen = ::setFullscreen)
            delay(250)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { setFullscreen(false) } }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (
                        event.type != KeyEventType.KeyDown ||
                            event.isAltPressed ||
                            event.isCtrlPressed
                    ) {
                        return@onKeyEvent false
                    }
                    val player = session.player ?: return@onKeyEvent false
                    when (event.key) {
                        Key.Spacebar,
                        Key.K -> player.command("cycle", "pause")
                        Key.DirectionLeft -> player.command("seek", "-5")
                        Key.DirectionRight -> player.command("seek", "5")
                        Key.J -> player.command("seek", "-10")
                        Key.L -> player.command("seek", "10")
                        Key.DirectionUp -> player.command("add", "volume", "5")
                        Key.DirectionDown -> player.command("add", "volume", "-5")
                        Key.M -> player.command("cycle", "mute")
                        Key.F -> player.command("cycle", "fullscreen")
                        Key.Enter -> session.skip()
                        Key.Escape ->
                            if (session.fullscreen) player.set("fullscreen", "no") else close()
                        else -> return@onKeyEvent false
                    }
                    true
                }
    ) {
        if (!session.fullscreen) {
            PlayerBar(session = session, onClose = close)
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (lib == null) {
                    Message(MpvLoader.loadError ?: "Couldn't load mpv")
                } else {
                    SwingPanel(
                        background = Color.Black,
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            MpvCanvas(
                                onReady = { canvas ->
                                    try {
                                        session.player =
                                            MpvPlayer.create(
                                                lib = lib,
                                                wid = Native.getComponentID(canvas),
                                                options = mpvOptions(app.settings),
                                                onEvent = { event ->
                                                    scope.launch(Dispatchers.Main) {
                                                        when (event) {
                                                            MpvEvent.FileLoaded ->
                                                                session.onFileLoaded()
                                                            is MpvEvent.LoadFailed ->
                                                                session.onLoadFailed(event.message)
                                                            MpvEvent.Shutdown -> close()
                                                        }
                                                    }
                                                },
                                            )
                                    } catch (e: Exception) {
                                        session.error = e.message ?: e.toString()
                                    }
                                },
                                onGone = { session.release() },
                            )
                        },
                    )
                }
            }
            // Beside the video rather than over it, as mpv draws in a window of its own
            AnimatedVisibility(
                visible = session.playlistOpen && !session.fullscreen,
                enter = expandHorizontally(expandFrom = Alignment.Start),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                PlaylistPanel(
                    name = session.playlistName,
                    videos = session.playlistVideos,
                    currentVideoId = session.item?.id,
                    loop = session.loop,
                    onPlay = session::playFromPlaylist,
                    onLoopChange = session::changeLoop,
                    onOpenPage = {
                        val playlistId = (request as? PlayerRequest.Ta)?.playlistId
                        close()
                        if (playlistId != null) navigator.open(Route.Playlist(playlistId))
                    },
                )
            }
        }
    }
}

/** The bar above the video: back, the title, and what's next. Hidden in full screen. */
@Composable
private fun PlayerBar(session: PlayerSession, onClose: () -> Unit) {
    val item = session.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = Spacings.small, vertical = 6.dp),
    ) {
        IconButton(onClick = onClose) {
            AppIcon(name = "ic_arrow_left", contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details =
                listOfNotNull(
                        item?.subtitle,
                        session.duration
                            .takeIf { it > 0 }
                            ?.let { formatDuration(session.position) + " / " + formatDuration(it) },
                    )
                    .joinToString(" • ")
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (session.loading && session.error == null) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        session.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        session.segment?.let { segment ->
            FilledTonalButton(onClick = { session.skip(segment) }) {
                AppIcon(
                    name = "ic_skip_forward",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                Text("Skip ${PlayerSession.categoryName(segment.category).lowercase()}")
            }
        }
        session.upNext?.let { next ->
            TextButton(onClick = session::playNext) {
                Text(
                    text = "Next: $next",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(220.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                AppIcon(
                    name = "ic_skip_forward",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (session.hasPlaylist) {
            WithTooltip(if (session.playlistOpen) "Close the playlist" else "Show the playlist") {
                FilledTonalButton(
                    onClick = session::togglePlaylist,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor =
                                if (session.playlistOpen) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                        ),
                ) {
                    AppIcon(
                        name = "ic_list_video",
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacings.small))
                    Text("Playlist")
                }
            }
        }
        IconButton(onClick = { session.player?.set("fullscreen", "yes") }) {
            AppIcon(name = "ic_maximize", contentDescription = "Full screen")
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(Spacings.large),
    )
}

/**
 * mpv's settings, as on its command line. A user's own mpv.conf in the config folder comes on top.
 */
private fun mpvOptions(settings: AppSettings): List<Pair<String, String>> = buildList {
    add("config-dir" to settings.mpvConfigFolder.path)
    add("config" to "yes")
    add("terminal" to "no")
    add("osc" to "yes")
    add("input-default-bindings" to "yes")
    add("input-vo-keyboard" to "yes")
    add("keep-open" to "yes")
    add("idle" to "yes")
    add("ytdl" to "no")
    add("hwdec" to settings.hardwareDecoding)
    add("osd-font-size" to "38")
    add("cursor-autohide" to "1000")
}

/** The native window mpv draws into. Starts mpv once it exists and stops it before it goes. */
private class MpvCanvas(private val onReady: (Canvas) -> Unit, private val onGone: () -> Unit) :
    Canvas() {
    private var started = false

    init {
        background = java.awt.Color.BLACK
    }

    override fun addNotify() {
        super.addNotify()
        if (!started) {
            started = true
            onReady(this)
        }
    }

    override fun removeNotify() {
        if (started) {
            started = false
            onGone()
        }
        super.removeNotify()
    }
}
