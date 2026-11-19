package dev.jdtech.tubedroid.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.player.PlayerScreen
import dev.jdtech.tubedroid.desktop.screens.ChannelScreen
import dev.jdtech.tubedroid.desktop.screens.ChannelsScreen
import dev.jdtech.tubedroid.desktop.screens.DownloadsScreen
import dev.jdtech.tubedroid.desktop.screens.HomeScreen
import dev.jdtech.tubedroid.desktop.screens.PlaylistScreen
import dev.jdtech.tubedroid.desktop.screens.SearchScreen
import dev.jdtech.tubedroid.desktop.screens.SettingsScreen
import dev.jdtech.tubedroid.desktop.screens.VideoScreen
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.AppImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The window's sections and their history, the player when a video plays, and messages at the
 * bottom. Each section keeps its own pages, so switching sections and back keeps your place.
 */
class Shell : Navigator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var section by mutableStateOf(Section.HOME)
        private set

    private val stacks = Section.entries.associateWith { mutableStateListOf(PageEntry(it.root)) }

    var player by mutableStateOf<PlayerRequest?>(null)
        private set

    val snackbar = SnackbarHostState()

    val current: PageEntry
        get() = stacks.getValue(section).last()

    val canGoBack: Boolean
        get() = stacks.getValue(section).size > 1

    /** Goes to a section, or back to its first page when it's already showing. */
    fun select(target: Section) {
        if (target == section) {
            val stack = stacks.getValue(section)
            while (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
        } else {
            section = target
        }
    }

    override fun open(route: Route) {
        stacks.getValue(section).add(PageEntry(route))
    }

    override fun back() {
        if (player != null) {
            player = null
            return
        }
        val stack = stacks.getValue(section)
        if (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
    }

    override fun play(request: PlayerRequest) {
        player = request
    }

    fun closePlayer() {
        player = null
    }

    override fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
}

/** Whether the page shown has one before it to go back to. */
val LocalCanGoBack = staticCompositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShellContent(shell: Shell) {
    CompositionLocalProvider(LocalNavigator provides shell) {
        Box(
            modifier =
                Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { event ->
                    // The mouse's back button goes back, as in a browser
                    if (event.button == PointerButton.Back) shell.back()
                }
        ) {
            val request = shell.player
            if (request != null) {
                PlayerScreen(request = request, onClose = shell::closePlayer)
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    SectionRail(shell)
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        val entry = shell.current
                        AnimatedContent(
                            targetState = entry,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "page",
                        ) { page ->
                            CompositionLocalProvider(
                                LocalPageEntry provides page,
                                LocalCanGoBack provides (page === entry && shell.canGoBack),
                            ) {
                                PageContent(page.route)
                            }
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = shell.snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionRail(shell: Shell) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            AppImage(
                name = "ic_ta_logo_mark",
                contentDescription = "Tubedroid",
                modifier = Modifier.padding(top = 12.dp).size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
        },
    ) {
        Section.entries.forEach { section ->
            if (section == Section.SETTINGS) Spacer(Modifier.weight(1f))
            NavigationRailItem(
                selected = shell.section == section,
                onClick = { shell.select(section) },
                icon = { AppIcon(name = section.icon, contentDescription = null) },
                label = { Text(section.label) },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PageContent(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Channels -> ChannelsScreen()
        Route.Downloads -> DownloadsScreen()
        Route.Search -> SearchScreen()
        Route.Settings -> SettingsScreen(firstRun = false, onDone = {})
        is Route.Channel -> ChannelScreen(route.channelId)
        is Route.Video -> VideoScreen(route.videoId, route.playlistId)
        is Route.Playlist -> PlaylistScreen(route.playlistId)
    }
}
