package dev.jdtech.jellyfin

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.audio.AudioEpisode
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsScope
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiNavViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelsTab
import dev.jdtech.jellyfin.film.presentation.tube.TaNavViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidFolder
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPlaylist
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.navigation.navigateOrReuse
import dev.jdtech.jellyfin.navigation.resumedAction
import dev.jdtech.jellyfin.presentation.audio.AudioMiniPlayer
import dev.jdtech.jellyfin.presentation.audio.AudioPlayerScreen
import dev.jdtech.jellyfin.presentation.audio.AudioViewModel
import dev.jdtech.jellyfin.presentation.components.pageSwipes
import dev.jdtech.jellyfin.presentation.components.rememberPageSwiper
import dev.jdtech.jellyfin.presentation.film.CollectionScreen
import dev.jdtech.jellyfin.presentation.film.DownloadStorageScreen
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.film.EpisodeScreen
import dev.jdtech.jellyfin.presentation.film.FavoritesScreen
import dev.jdtech.jellyfin.presentation.film.HomeScreen
import dev.jdtech.jellyfin.presentation.film.LibraryScreen
import dev.jdtech.jellyfin.presentation.film.MediaScreen
import dev.jdtech.jellyfin.presentation.film.MovieScreen
import dev.jdtech.jellyfin.presentation.film.PersonScreen
import dev.jdtech.jellyfin.presentation.film.PlaylistScreen
import dev.jdtech.jellyfin.presentation.film.PlaylistsScreen
import dev.jdtech.jellyfin.presentation.film.SeasonScreen
import dev.jdtech.jellyfin.presentation.film.ShowScreen
import dev.jdtech.jellyfin.presentation.jrwi.JRWI_BAR_HEIGHT
import dev.jdtech.jellyfin.presentation.jrwi.JrwiColors
import dev.jdtech.jellyfin.presentation.jrwi.JrwiHomeScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiItemScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiPurple
import dev.jdtech.jellyfin.presentation.jrwi.JrwiSearchScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiSettingsScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiShowScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiShowsScreen
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTheme
import dev.jdtech.jellyfin.presentation.jrwi.LocalJrwiBaseUrl
import dev.jdtech.jellyfin.presentation.settings.AboutScreen
import dev.jdtech.jellyfin.presentation.settings.PlayerButtonsScreen
import dev.jdtech.jellyfin.presentation.settings.SettingsFileEditScreen
import dev.jdtech.jellyfin.presentation.settings.SettingsScreen
import dev.jdtech.jellyfin.presentation.setup.addresses.ServerAddressesScreen
import dev.jdtech.jellyfin.presentation.setup.addserver.AddServerScreen
import dev.jdtech.jellyfin.presentation.setup.login.LoginScreen
import dev.jdtech.jellyfin.presentation.setup.permission.PermissionScreen
import dev.jdtech.jellyfin.presentation.setup.servers.ServersScreen
import dev.jdtech.jellyfin.presentation.setup.users.UsersScreen
import dev.jdtech.jellyfin.presentation.setup.welcome.WelcomeScreen
import dev.jdtech.jellyfin.presentation.tube.LocalInMainPager
import dev.jdtech.jellyfin.presentation.tube.LocalTaBaseUrl
import dev.jdtech.jellyfin.presentation.tube.LocalTubeArchivistMode
import dev.jdtech.jellyfin.presentation.tube.TaChannelScreen
import dev.jdtech.jellyfin.presentation.tube.TaChannelsScreen
import dev.jdtech.jellyfin.presentation.tube.TaDownloadsScreen
import dev.jdtech.jellyfin.presentation.tube.TaHomeScreen
import dev.jdtech.jellyfin.presentation.tube.TaPlaylistScreen
import dev.jdtech.jellyfin.presentation.tube.TaQueueScreen
import dev.jdtech.jellyfin.presentation.tube.TaSearchScreen
import dev.jdtech.jellyfin.presentation.tube.TaSettingsScreen
import dev.jdtech.jellyfin.presentation.tube.TaVideoScreen
import dev.jdtech.jellyfin.presentation.tube.rememberSidePagerState
import dev.jdtech.jellyfin.presentation.tube.requestOwnSide
import dev.jdtech.jellyfin.presentation.tube.showSide
import dev.jdtech.jellyfin.presentation.tube.startTaPlayback
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.setup.presentation.permission.PermissionAction
import dev.jdtech.jellyfin.setup.presentation.permission.PermissionViewModel
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

private val TOP_LEVEL_ROUTES =
    setOf(
        TaHomeRoute,
        TaChannelsRoute,
        JrwiRoute,
        JrwiShowsRoute,
        JrwiDownloadsRoute,
        TaQueueRoute,
        HomeRoute,
        MediaRoute,
        DownloadsRoute,
        PlaylistsRoute,
    )
private val GLOBAL_TRANSITION_SPEC = fadeIn(tween(300)) togetherWith fadeOut(tween(300))

data class TabBarItem(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val route: NavKey,
    val enabled: Boolean = true,
)

val homeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = HomeRoute)

val mediaTab =
    TabBarItem(
        title = CoreR.string.title_media,
        icon = CoreR.drawable.ic_library,
        route = MediaRoute,
    )

val downloadsTab =
    TabBarItem(
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = DownloadsRoute,
    )

val playlistsTab =
    TabBarItem(
        title = CoreR.string.title_playlists,
        icon = CoreR.drawable.ic_list_video,
        route = PlaylistsRoute,
    )

// With TubeArchivist set up it becomes the main part of the app
val taHomeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = TaHomeRoute)

val taChannelsTab =
    TabBarItem(
        title = CoreR.string.ta_channels,
        icon = CoreR.drawable.ic_users,
        route = TaChannelsRoute,
    )

// With the JRWI archiver set up, it has a mode of its own with these tabs, switched to at the start
// of the bottom bar
val jrwiHomeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = JrwiRoute)

val jrwiShowsTab =
    TabBarItem(
        title = CoreR.string.jrwi_shows,
        icon = CoreR.drawable.ic_dice,
        route = JrwiShowsRoute,
    )

val jrwiDownloadsTab =
    TabBarItem(
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = JrwiDownloadsRoute,
    )

/** JRWI mode's tabs, left to right, which are also its main pages. */
private val JRWI_TABS = listOf(JrwiRoute, JrwiShowsRoute, JrwiDownloadsRoute)

@Composable
fun NavigationRoot(
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
) {
    val setupComplete = hasServers && hasCurrentServer && hasCurrentUser

    val taNavViewModel: TaNavViewModel = hiltViewModel()
    val taConfigured by taNavViewModel.configured.collectAsStateWithLifecycle()
    val taBaseUrl by taNavViewModel.baseUrl.collectAsStateWithLifecycle()
    // The TubeArchivist interface whenever TubeArchivist is set up; Jellyfin's own without it
    val taInterface = taConfigured
    val tubeArchivistMode = taInterface && !LocalOfflineMode.current

    val jrwiNavViewModel: JrwiNavViewModel = hiltViewModel()
    val jrwiBaseUrl by jrwiNavViewModel.baseUrl.collectAsStateWithLifecycle()
    // The archiver is only reached online
    val jrwiShown = jrwiBaseUrl != null && !LocalOfflineMode.current

    val setupStartRoute: NavKey =
        when {
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> WelcomeRoute
        }

    // Navigation state for both modes is hoisted so that it survives switching between them.
    val setupBackStack = rememberNavBackStack(setupStartRoute)
    val navigationState =
        rememberNavigationState(
            startRoute = if (tubeArchivistMode) TaHomeRoute else HomeRoute,
            topLevelRoutes = TOP_LEVEL_ROUTES,
        )
    val navigator = remember(navigationState) { Navigator(navigationState) }
    // Back from JRWI mode's Shows or Downloads goes to its Home, and from there to TubeArchivist's
    navigator.backTo = { route ->
        if (route == JrwiShowsRoute || route == JrwiDownloadsRoute) JrwiRoute else null
    }
    // A JRWI episode's video opens on the episode's page, as on the downloads page while it's still
    // downloading; downloaded TubeArchivist videos open on their TubeArchivist page, also offline
    navigator.episodeRedirect = { itemId ->
        taNavViewModel.jrwiItemId(itemId)?.let { JrwiItemRoute(it) }
            ?: if (taInterface) taNavViewModel.downloadedVideoId(itemId)?.let { TaVideoRoute(it) }
            else null
    }

    // The entry decorators hold the per-entry SavedState and ViewModel stores. They must be
    // hoisted as well, otherwise the retained state would be discarded when switching modes.
    val saveableStateHolderDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val entryDecorators =
        remember(saveableStateHolderDecorator, viewModelStoreDecorator) {
            listOf(saveableStateHolderDecorator, viewModelStoreDecorator, OpaquePageDecorator)
        }

    // Exposed as a state object (rather than a plain value) so the entry content closures, which
    // nav3 caches per back stack snapshot, always read the current value.
    val searchExpanded = remember { mutableStateOf(false) }

    // Live setup condition: keep the back stacks in a consistent state across mode switches. The
    // stacks are reconciled during composition (rather than in a LaunchedEffect, which only runs
    // after the first frame has been composed) so the first frame of the new mode already shows
    // the right screen.
    val appliedSetupMode = remember { AppliedSetupMode(setupComplete) }
    if (appliedSetupMode.setupComplete != setupComplete) {
        if (setupComplete) {
            // Setup completed: discard the setup flow and land on Home.
            navigator.navigateHome()
        } else {
            // Setup became incomplete: restart the setup flow at the appropriate screen.
            setupBackStack.clear()
            setupBackStack.add(setupStartRoute)
        }
        appliedSetupMode.setupComplete = setupComplete
    }

    // The activity-scoped permission ViewModel is shared by the interstitial and the setup screen.
    val permissionViewModel: PermissionViewModel = hiltViewModel()
    val permissionState by permissionViewModel.state.collectAsStateWithLifecycle()

    if (setupComplete) {
        // Upgrader interstitial: shown before the home screen until the user decides or
        // dismisses it for this session.
        if (permissionState.showInterstitial) {
            PermissionScreen(
                viewModel = permissionViewModel,
                onDecided = {},
                onBackPressed = { permissionViewModel.onAction(PermissionAction.OnDismiss) },
            )
        } else {
            CompositionLocalProvider(
                LocalTaBaseUrl provides taBaseUrl,
                LocalTubeArchivistMode provides tubeArchivistMode,
                LocalJrwiBaseUrl provides jrwiBaseUrl,
            ) {
                MainNavigation(
                    navigationState = navigationState,
                    navigator = navigator,
                    entryDecorators = entryDecorators,
                    isOfflineMode = LocalOfflineMode.current,
                    tubeArchivistMode = tubeArchivistMode,
                    jrwiShown = jrwiShown,
                    searchExpanded = searchExpanded,
                    permissionViewModel = permissionViewModel,
                    permissionApplicable = permissionState.currentRequest != null,
                )
            }
        }
    } else {
        SetupNavigation(
            backStack = setupBackStack,
            entryDecorators = entryDecorators,
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionState.currentRequest != null,
        )
    }
}

/**
 * Remembers the setup mode that the back stacks were last reconciled for. Deliberately not
 * observable state: it is written during composition, which must not schedule recompositions.
 */
private class AppliedSetupMode(var setupComplete: Boolean)

@Composable
private fun MainNavigation(
    navigationState: NavigationState,
    navigator: Navigator,
    entryDecorators: List<NavEntryDecorator<NavKey>>,
    isOfflineMode: Boolean,
    tubeArchivistMode: Boolean,
    jrwiShown: Boolean,
    searchExpanded: MutableState<Boolean>,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    val topLevelRoute = navigationState.topLevelRoute
    // JRWI mode while one of its tabs is open; TubeArchivist's (or Jellyfin's) otherwise, which the
    // app starts in
    val jrwiMode = jrwiShown && topLevelRoute in JRWI_TABS
    val navigationItems =
        when {
            isOfflineMode -> listOf(homeTab, downloadsTab)
            jrwiMode -> listOf(jrwiHomeTab, jrwiShowsTab, jrwiDownloadsTab)
            tubeArchivistMode -> listOf(taHomeTab, taChannelsTab, downloadsTab)
            else -> listOf(homeTab, mediaTab, downloadsTab, playlistsTab)
        }
    val tabRoutes = navigationItems.map { it.route }
    // The tab each mode was left on, to come back to when it's switched to again
    val lastTabs = remember { LastModeTabs() }
    LaunchedEffect(topLevelRoute) {
        if (topLevelRoute in JRWI_TABS) lastTabs.jrwi = topLevelRoute
        else if (topLevelRoute in tabRoutes) lastTabs.other = topLevelRoute
    }
    // Out of JRWI mode once the archiver is gone, as when it's removed in Settings
    LaunchedEffect(jrwiShown) {
        if (!jrwiShown && navigationState.topLevelRoute in JRWI_TABS) {
            navigator.navigate(tabRoutesFor(tubeArchivistMode).first())
        }
    }

    val atTabRoot = navigationState.currentBackStack().last() == topLevelRoute
    val showBottomBar = atTabRoot && topLevelRoute in tabRoutes && !searchExpanded.value
    // The main page a swipe is heading to, which the bottom bar shows before it's reached
    val swipeTarget = remember { mutableStateOf<NavKey?>(null) }
    val barRoute = swipeTarget.value ?: topLevelRoute
    val mainPagerTurns = remember { MainPagerTurns() }
    val tabSides = remember { TabSides() }
    val lastTap = remember { LastTap() }

    val audioViewModel: AudioViewModel = hiltViewModel()
    val audioPlayer = audioViewModel.player
    // The audio's notification opens the player
    val openPlayer by audioPlayer.openRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayer) {
        if (openPlayer) {
            audioPlayer.openHandled()
            if (navigationState.currentBackStack().last() != AudioPlayerRoute) {
                navigator.navigate(AudioPlayerRoute)
            }
        }
    }
    val miniPlayerShown = navigationState.currentBackStack().last() != AudioPlayerRoute
    val doubleTapMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis

    // The search bar belongs to the Media tab: collapse it when the active top level route
    // changes away from Media (e.g. via the home button on a detail screen) so that the bottom
    // bar is shown again.
    LaunchedEffect(topLevelRoute) {
        if (topLevelRoute != MediaRoute) {
            searchExpanded.value = false
        }
    }

    val navigationSuiteScaffoldState = rememberNavigationSuiteScaffoldState()

    // A page asked for from outside, such as the player's playlist, opens once the app shows
    val pendingRoute by PendingNavigation.route.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) PendingNavigation.take()?.let { navigator.navigate(it) }
    }

    LaunchedEffect(showBottomBar) {
        if (showBottomBar) {
            navigationSuiteScaffoldState.show()
        } else {
            navigationSuiteScaffoldState.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val customNavSuiteType =
        with(windowAdaptiveInfo) {
            if (
                windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
                )
            ) {
                NavigationSuiteType.NavigationRail
            } else {
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(this)
            }
        }

    val entryProvider = entryProvider {
        mainEntries(
            navigator = navigator,
            onSearchClick = {
                searchExpanded.value = true
                navigator.navigate(MediaRoute)
            },
            searchExpanded = searchExpanded,
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionApplicable,
            jrwiShown = jrwiShown,
        )
    }

    // Flips between the modes, to the tab each was left on
    val switchMode: (Boolean) -> Unit = { toJrwi ->
        val route =
            if (toJrwi) lastTabs.jrwi ?: JrwiRoute
            else
                lastTabs.other?.takeIf { it !in JRWI_TABS }
                    ?: tabRoutesFor(tubeArchivistMode).first()
        navigator.navigate(route)
    }
    // In JRWI mode the bar is in the archiver's colours, and lights up in its purple
    val jrwiColors = jrwiItemColors()
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            // The switch between the modes comes first, at the far left (or the top of the rail)
            if (jrwiShown && !isOfflineMode) {
                item(
                    selected = false,
                    onClick = { switchMode(!jrwiMode) },
                    // Without a label, so the switch has the item's height to itself
                    icon = { ModeSwitch(jrwi = jrwiMode, onSwitch = switchMode) },
                    colors = if (jrwiMode) jrwiColors else null,
                )
            }
            navigationItems.forEach { item ->
                item(
                    selected = barRoute == item.route,
                    onClick = {
                        val doubleTap = lastTap.isSecond(item.route, doubleTapMillis)
                        when {
                            // A double tap opens the tab on its server side
                            doubleTap -> tabSides.show(item.route, server = true)
                            // A tap on the open tab, once it's at rest, turns it to its other side
                            item.route == topLevelRoute && atTabRoot && swipeTarget.value == null ->
                                tabSides.show(item.route, server = null)
                        }
                        if (item.route == MediaRoute && topLevelRoute == MediaRoute) {
                            searchExpanded.value = true
                        }

                        // Straight away, over a swipe still going
                        mainPagerTurns.turnTo?.invoke(item.route)
                        navigator.navigate(item.route)
                    },
                    icon = {
                        val icon =
                            @Composable {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = stringResource(item.title),
                                )
                            }
                        // JRWI mode's highlight has small corners, as its pages
                        if (jrwiMode) {
                            JrwiNavHighlight(
                                selected = barRoute == item.route,
                                rail = customNavSuiteType == NavigationSuiteType.NavigationRail,
                                content = icon,
                            )
                        } else {
                            icon()
                        }
                    },
                    enabled = item.enabled,
                    label = { Text(text = stringResource(item.title)) },
                    colors = if (jrwiMode) jrwiColors else null,
                )
            }
        },
        layoutType = customNavSuiteType,
        navigationSuiteColors =
            if (jrwiMode) {
                NavigationSuiteDefaults.colors(
                    navigationBarContainerColor = JrwiColors.Header,
                    navigationBarContentColor = JrwiColors.Text,
                    navigationRailContainerColor = JrwiColors.Header,
                    navigationRailContentColor = JrwiColors.Text,
                    navigationDrawerContainerColor = JrwiColors.Header,
                    navigationDrawerContentColor = JrwiColors.Text,
                )
            } else {
                NavigationSuiteDefaults.colors()
            },
        state = navigationSuiteScaffoldState,
    ) {
        CompositionLocalProvider(LocalTabSides provides tabSides) {
            Column(modifier = Modifier.fillMaxSize()) {
                // The mini player takes the bottom, so the pages leave no room for the phone's bar
                val audioShown by
                    remember(audioPlayer) {
                            audioPlayer.state.map { it.active }.distinctUntilChanged()
                        }
                        .collectAsStateWithLifecycle(initialValue = audioPlayer.state.value.active)
                val pagesModifier =
                    if (miniPlayerShown && audioShown) {
                        Modifier.consumeWindowInsets(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
                    } else {
                        Modifier
                    }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().then(pagesModifier)) {
                    val navDisplay: @Composable () -> Unit = {
                        NavDisplay(
                            entries =
                                navigationState.toDecoratedEntries(entryDecorators, entryProvider),
                            onBack = { navigator.goBack() },
                            transitionSpec = { GLOBAL_TRANSITION_SPEC },
                            popTransitionSpec = { GLOBAL_TRANSITION_SPEC },
                            predictivePopTransitionSpec = { _ -> GLOBAL_TRANSITION_SPEC },
                        )
                    }
                    if (tubeArchivistMode || jrwiMode) {
                        // The mode's tabs sit side by side in one pager under the other pages, and
                        // their own entries in the navigation are left empty so the pager shows
                        // through. A new pager for each mode, on the tab it's switched to.
                        val mainPages = if (jrwiMode) JRWI_TABS else taMainPages()
                        Box(modifier = Modifier.fillMaxSize()) {
                            key(jrwiMode) {
                                TaMainPager(
                                    navigationState = navigationState,
                                    navigator = navigator,
                                    mainPages = mainPages,
                                    active = atTabRoot && topLevelRoute in mainPages,
                                    swipeTarget = swipeTarget,
                                    turns = mainPagerTurns,
                                    hideJrwiDownloads = jrwiShown,
                                )
                            }
                            CompositionLocalProvider(LocalTaMainPagerShown provides true) {
                                navDisplay()
                            }
                        }
                    } else {
                        navDisplay()
                    }
                }
                AudioMiniPlayer(
                    visible = miniPlayerShown,
                    onOpen = { navigator.navigate(AudioPlayerRoute) },
                    viewModel = audioViewModel,
                )
            }
        }
    }
}

/** The tab each mode was last on: JRWI's, and TubeArchivist's or Jellyfin's. */
private class LastModeTabs {
    var jrwi: NavKey? = null
    var other: NavKey? = null
}

/** The tabs of TubeArchivist's interface, or else Jellyfin's, for switching back from JRWI. */
private fun tabRoutesFor(tubeArchivistMode: Boolean): List<NavKey> =
    if (tubeArchivistMode) taMainPages() else listOf(HomeRoute, MediaRoute, DownloadsRoute)

/**
 * JRWI mode's tabs in the bottom bar, rail or drawer: on the archiver's dark purple, lighting up in
 * the purple of its server side when open. The bar's and rail's own highlight is always fully
 * round, so it's left out and [JrwiNavHighlight] draws a square-cornered one.
 */
@Composable
private fun jrwiItemColors() =
    NavigationSuiteDefaults.itemColors(
        navigationBarItemColors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = JrwiPurple.Ink,
                selectedTextColor = JrwiPurple.Accent,
                indicatorColor = Color.Transparent,
                unselectedIconColor = JrwiPurple.Muted,
                unselectedTextColor = JrwiPurple.Muted,
            ),
        navigationRailItemColors =
            NavigationRailItemDefaults.colors(
                selectedIconColor = JrwiPurple.Ink,
                selectedTextColor = JrwiPurple.Accent,
                indicatorColor = Color.Transparent,
                unselectedIconColor = JrwiPurple.Muted,
                unselectedTextColor = JrwiPurple.Muted,
            ),
        navigationDrawerItemColors =
            NavigationDrawerItemDefaults.colors(
                selectedContainerColor = JrwiPurple.Accent,
                selectedIconColor = JrwiPurple.Ink,
                selectedTextColor = JrwiPurple.Ink,
                unselectedIconColor = JrwiPurple.Muted,
                unselectedTextColor = JrwiPurple.Muted,
            ),
    )

/** The main pages, left to right, in the TubeArchivist interface. */
private fun taMainPages(): List<NavKey> = listOf(TaHomeRoute, TaChannelsRoute, DownloadsRoute)

/** True where the main pager shows the main pages, so their navigation entries stay empty. */
private val LocalTaMainPagerShown = staticCompositionLocalOf { false }

/** Marks the entries of the main pages, which stay see-through where the main pager shows them. */
private const val MAIN_PAGE_KEY = "mainPage"
private val MAIN_PAGE_ENTRY = mapOf<String, Any>(MAIN_PAGE_KEY to true)

/**
 * Gives each page over the main pager a background of its own, so the main pages under it never
 * show through, also while a page is loading or has nothing to show.
 */
private val OpaquePageDecorator =
    NavEntryDecorator<NavKey> { entry ->
        val opaque = LocalTaMainPagerShown.current && entry.metadata[MAIN_PAGE_KEY] != true
        Box(
            modifier =
                if (opaque) {
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier.fillMaxSize()
                }
        ) {
            entry.Content()
        }
    }

/** Lets the bottom bar turn the main pager straight away, also to the tab that's still open. */
private class MainPagerTurns {
    var turnTo: ((NavKey) -> Unit)? = null
}

/**
 * Which side the tabs with a server side show: TubeArchivist's Home and Downloads, and JRWI's Home,
 * whose sides are pages of the main pager. A tab opens on its own side, a tap on it while it's open
 * turns it to its other side, and a double tap opens it on its server side.
 */
private class TabSides {
    private val requests = MutableSharedFlow<SideRequest>(extraBufferCapacity = 4)

    /** Turns [route] to its server side, to its own side, or with null to the side it isn't on. */
    fun show(route: NavKey, server: Boolean?) {
        requests.tryEmit(SideRequest(route, server))
    }

    /** The sides asked of [route], for the tabs whose sides are in the main pager. */
    fun requestsFor(route: NavKey): Flow<Boolean?> =
        requests.filter { it.route == route }.map { it.server }
}

private data class SideRequest(val route: NavKey, val server: Boolean?)

private val LocalTabSides = staticCompositionLocalOf<TabSides> { error("No tab sides") }

/** The bottom bar's last tap, to tell a double tap on one tab from two single taps. */
private class LastTap {
    private var route: NavKey? = null
    private var at = 0L

    /** Whether a tap on [tapped] now is the second of a double tap, which then starts over. */
    fun isSecond(tapped: NavKey, timeoutMillis: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        val second = route == tapped && now - at <= timeoutMillis
        route = if (second) null else tapped
        at = now
        return second
    }
}

/**
 * A mode's tabs side by side, to swipe between: TubeArchivist's Home, Channels and Downloads, or
 * JRWI's Home, Shows and Downloads. The two sides of Home, the Channels tabs and the two sides of
 * Downloads turn first, and the pager moves on once they're at their end, so swiping goes the
 * server's stats, Home, Subscribed, All channels, Playlists, the phone's downloads, then the
 * server's queue. The stats are left of Home, so they don't come between Home and Channels; JRWI's
 * archiver is left of its Home the same way. One [PageSwiper] moves all of them, so every swipe
 * feels the same. With [hideJrwiDownloads], TubeArchivist's Downloads leaves out JRWI's, which are
 * in JRWI mode's.
 */
@Composable
private fun TaMainPager(
    navigationState: NavigationState,
    navigator: Navigator,
    mainPages: List<NavKey>,
    active: Boolean,
    swipeTarget: MutableState<NavKey?>,
    turns: MainPagerTurns,
    hideJrwiDownloads: Boolean,
) {
    val topLevelRoute = navigationState.topLevelRoute
    // Read in the effects below, which keep running as the JRWI tab comes and goes
    val currentPages by rememberUpdatedState(mainPages)
    val pages =
        rememberPagerState(initialPage = mainPages.indexOf(topLevelRoute).coerceAtLeast(0)) {
            mainPages.size
        }
    val homeSides = rememberSidePagerState(serverFirst = true)
    val channelTabs = rememberPagerState { TaChannelsTab.entries.size }
    val downloadSides = rememberSidePagerState()
    val jrwiHomeSides = rememberSidePagerState(serverFirst = true)
    val swiper = rememberPageSwiper(pages)
    swiper.innerPages = { page ->
        when (currentPages.getOrNull(page)) {
            TaHomeRoute -> homeSides
            TaChannelsRoute -> channelTabs
            DownloadsRoute -> downloadSides
            JrwiRoute -> jrwiHomeSides
            else -> null
        }
    }
    // A swipe opens the tab it comes to rest on
    swiper.onSettled = { page ->
        val route = currentPages.getOrNull(page)
        val current = navigationState.topLevelRoute
        if (route != null && current in currentPages && current != route) navigator.navigate(route)
    }

    // The bottom bar's taps turn Home and Downloads to a side
    val tabSides = LocalTabSides.current
    val sideScope = rememberCoroutineScope()
    LaunchedEffect(tabSides) {
        launch {
            tabSides.requestsFor(TaHomeRoute).collect {
                homeSides.showSide(sideScope, server = it, serverFirst = true)
            }
        }
        launch {
            tabSides.requestsFor(DownloadsRoute).collect {
                downloadSides.showSide(sideScope, server = it)
            }
        }
        launch {
            tabSides.requestsFor(JrwiRoute).collect {
                jrwiHomeSides.showSide(sideScope, server = it, serverFirst = true)
            }
        }
    }
    // A tab that's been left is on its own side when it's next opened, out of sight meanwhile.
    // Swipes already bring a tab in on its near side, which is its own side.
    LaunchedEffect(pages) {
        snapshotFlow { currentPages.getOrNull(pages.settledPage) }
            .collect { route ->
                if (route != TaHomeRoute) homeSides.requestOwnSide(serverFirst = true)
                if (route != DownloadsRoute) downloadSides.requestOwnSide()
                if (route != JrwiRoute) jrwiHomeSides.requestOwnSide(serverFirst = true)
            }
    }

    // The bottom bar turns the pager as it's tapped, and back once it's gone back
    DisposableEffect(turns, swiper) {
        val turnTo: (NavKey) -> Unit = { route ->
            val page = currentPages.indexOf(route)
            if (page >= 0) swiper.turnTo(page)
        }
        turns.turnTo = turnTo
        // Left to the next mode's pager when that one's already in
        onDispose { if (turns.turnTo === turnTo) turns.turnTo = null }
    }
    LaunchedEffect(topLevelRoute, mainPages) {
        val page = mainPages.indexOf(topLevelRoute)
        if (page >= 0) swiper.turnTo(page)
    }

    // The bottom bar lights up the page a swipe is heading to straight away: where it would come
    // to rest if let go, or where a flick will land. It goes back to the open tab once that's
    // caught up.
    LaunchedEffect(swiper) {
        snapshotFlow { swiper.target?.let { currentPages.getOrNull(it) } }
            .collect { swipeTarget.value = it }
    }
    // Leaves the bottom bar to the open tab when the pager goes, as with the JRWI tab removed
    DisposableEffect(Unit) { onDispose { swipeTarget.value = null } }

    val lifecycleOwner = rememberGatedLifecycleOwner(active)
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalInMainPager provides true,
    ) {
        HorizontalPager(
            state = pages,
            // Worked out once here, as the pages move while they slide
            modifier = Modifier.fillMaxSize().recalculateWindowInsets().pageSwipes(swiper, active),
            // Swiped by the swiper alone, which also moves the pages' own pages
            userScrollEnabled = false,
        ) { page ->
            when (mainPages.getOrNull(page)) {
                TaHomeRoute ->
                    TaHomeScreen(
                        onVideoClick =
                            resumedAction<String> { navigator.navigate(TaVideoRoute(it)) },
                        onChannelClick =
                            resumedAction<String> { navigator.navigateOrReuse(TaChannelRoute(it)) },
                        onPlaylistClick =
                            resumedAction<String> { navigator.navigate(TaPlaylistRoute(it)) },
                        onSearchClick = dropUnlessResumed { navigator.navigate(TaSearchRoute) },
                        onSettingsClick =
                            dropUnlessResumed {
                                navigator.navigate(
                                    SettingsRoute(indexes = listOf(CoreR.string.title_settings))
                                )
                            },
                        onManageServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
                        onTubeArchivistSettings =
                            dropUnlessResumed { navigator.navigate(TaSettingsRoute) },
                        sides = homeSides,
                    )
                TaChannelsRoute ->
                    TaChannelsScreen(
                        onChannelClick =
                            resumedAction<String> { navigator.navigateOrReuse(TaChannelRoute(it)) },
                        onPlaylistClick =
                            resumedAction<String> { navigator.navigate(TaPlaylistRoute(it)) },
                        tabsPagerState = channelTabs,
                    )
                JrwiRoute ->
                    JrwiHomeScreen(
                        onItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
                        onShowClick =
                            resumedAction<String> { navigator.navigate(JrwiShowRoute(it)) },
                        onSearchClick = dropUnlessResumed { navigator.navigate(JrwiSearchRoute) },
                        onSettingsClick =
                            dropUnlessResumed {
                                navigator.navigate(
                                    SettingsRoute(indexes = listOf(CoreR.string.title_settings))
                                )
                            },
                        onSetUp = dropUnlessResumed { navigator.navigate(JrwiSettingsRoute) },
                        sides = jrwiHomeSides,
                    )
                JrwiShowsRoute ->
                    JrwiShowsScreen(
                        onShowClick =
                            resumedAction<String> { navigator.navigate(JrwiShowRoute(it)) },
                        onSetUp = dropUnlessResumed { navigator.navigate(JrwiSettingsRoute) },
                    )
                JrwiDownloadsRoute ->
                    JrwiDownloads(
                        onItemClick =
                            resumedAction<FindroidItem> { item -> navigateToItem(navigator, item) },
                        onJrwiItemClick =
                            resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
                    )
                else ->
                    TaDownloadsScreen(
                        onItemClick =
                            resumedAction<FindroidItem> { item -> navigateToItem(navigator, item) },
                        onChannelClick =
                            resumedAction<String> { navigator.navigateOrReuse(TaChannelRoute(it)) },
                        onJrwiItemClick =
                            resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
                        sides = downloadSides,
                        downloadsScope =
                            if (hideJrwiDownloads) DownloadsScope.NOT_JRWI else DownloadsScope.ALL,
                    )
            }
        }
    }
}

/** A flag that can be changed without causing anything to draw again. */

/**
 * A lifecycle that follows the app's, but stops short of resumed while [active] is false. The main
 * pages then act as when their screen is covered by another, and update when it's uncovered.
 */
@Composable
private fun rememberGatedLifecycleOwner(active: Boolean): LifecycleOwner {
    val parent = LocalLifecycleOwner.current
    val owner = remember { GatedLifecycleOwner() }
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(parent) {
        val observer = LifecycleEventObserver { _, _ ->
            owner.follow(parent.lifecycle.currentState, currentActive)
        }
        parent.lifecycle.addObserver(observer)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.follow(Lifecycle.State.DESTROYED, false)
        }
    }
    LaunchedEffect(active) { owner.follow(parent.lifecycle.currentState, active) }
    return owner
}

private class GatedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun follow(parentState: Lifecycle.State, active: Boolean) {
        val target =
            if (active || parentState < Lifecycle.State.STARTED) parentState
            else Lifecycle.State.STARTED
        // A lifecycle that never started can't be ended, and one that ended can't come back
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (
            target == Lifecycle.State.DESTROYED &&
                registry.currentState == Lifecycle.State.INITIALIZED
        ) {
            return
        }
        registry.currentState = target
    }
}

@Composable
private fun SetupNavigation(
    backStack: NavBackStack<NavKey>,
    entryDecorators: List<NavEntryDecorator<NavKey>>,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    val navigate: (NavKey) -> Unit = backStack::add
    val goBack: () -> Unit = { backStack.removeLastOrNull() }
    val navigateOrReuse: (NavKey) -> Unit = backStack::navigateOrReuse

    val entryProvider = entryProvider {
        setupEntries(
            navigate = navigate,
            goBack = goBack,
            navigateOrReuse = navigateOrReuse,
            showBack = backStack.size > 1,
            onSetupComplete = {},
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionApplicable,
        )
    }

    NavDisplay(
        backStack = backStack,
        onBack = goBack,
        entryDecorators = entryDecorators,
        entryProvider = entryProvider,
        transitionSpec = { GLOBAL_TRANSITION_SPEC },
        popTransitionSpec = { GLOBAL_TRANSITION_SPEC },
        predictivePopTransitionSpec = { _ -> GLOBAL_TRANSITION_SPEC },
    )
}

/**
 * Registers the setup flow entries.
 *
 * The same entries serve both the dedicated setup mode and the setup flow reached from the main UI
 * (e.g. via "manage servers" in Settings). [onSetupComplete] captures the difference: in setup mode
 * completing the flow flips the live condition to the main UI (a no-op), while in the main UI it
 * navigates home. It is invoked through the nearest entry's RESUMED guard.
 */
private fun EntryProviderScope<NavKey>.setupEntries(
    navigate: (NavKey) -> Unit,
    goBack: () -> Unit,
    navigateOrReuse: (NavKey) -> Unit,
    showBack: Boolean,
    onSetupComplete: () -> Unit,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    entry<WelcomeRoute> {
        WelcomeScreen(
            onContinueClick =
                dropUnlessResumed {
                    navigate(if (permissionApplicable) PermissionRoute else ServersRoute)
                }
        )
    }
    entry<PermissionRoute> {
        PermissionScreen(
            viewModel = permissionViewModel,
            onDecided =
                dropUnlessResumed {
                    // Pop the permission entry so back from Servers returns to Welcome.
                    goBack()
                    navigate(ServersRoute)
                },
        )
    }
    entry<ServersRoute> {
        ServersScreen(
            navigateToUsers = dropUnlessResumed { navigate(UsersRoute) },
            navigateToAddresses =
                resumedAction { serverId -> navigate(ServerAddressesRoute(serverId)) },
            onAddClick = dropUnlessResumed { navigate(AddServerRoute) },
            onBackClick = dropUnlessResumed { goBack() },
            showBack = showBack,
        )
    }
    entry<AddServerRoute> {
        AddServerScreen(
            onSuccess = dropUnlessResumed { navigate(UsersRoute) },
            onBackClick = dropUnlessResumed { goBack() },
        )
    }
    entry<ServerAddressesRoute> { key ->
        ServerAddressesScreen(
            serverId = key.serverId,
            navigateBack = dropUnlessResumed { goBack() },
        )
    }
    entry<UsersRoute> {
        UsersScreen(
            navigateToHome = dropUnlessResumed { onSetupComplete() },
            onChangeServerClick = dropUnlessResumed { navigateOrReuse(ServersRoute) },
            onAddClick = dropUnlessResumed { navigate(LoginRoute()) },
            onBackClick = dropUnlessResumed { goBack() },
            onPublicUserClick =
                resumedAction { username -> navigate(LoginRoute(username = username)) },
            showBack = showBack,
        )
    }
    entry<LoginRoute> { key ->
        LoginScreen(
            onSuccess = dropUnlessResumed { onSetupComplete() },
            onChangeServerClick = dropUnlessResumed { navigateOrReuse(ServersRoute) },
            onBackClick = dropUnlessResumed { goBack() },
            prefilledUsername = key.username,
        )
    }
}

private fun EntryProviderScope<NavKey>.mainEntries(
    navigator: Navigator,
    onSearchClick: () -> Unit,
    searchExpanded: MutableState<Boolean>,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
    jrwiShown: Boolean,
) {
    setupEntries(
        navigate = navigator::navigate,
        goBack = navigator::goBack,
        navigateOrReuse = navigator::navigateOrReuse,
        showBack = true,
        onSetupComplete = { navigator.navigateHome() },
        permissionViewModel = permissionViewModel,
        permissionApplicable = permissionApplicable,
    )
    tubeArchivistEntries(navigator)
    entry<HomeRoute> {
        HomeScreen(
            onLibraryClick =
                resumedAction { library ->
                    navigator.navigate(
                        LibraryRoute(
                            libraryId = library.id.toString(),
                            libraryName = library.name,
                            libraryType = library.type,
                        )
                    )
                },
            onSearchClick = dropUnlessResumed { onSearchClick() },
            onSettingsClick =
                dropUnlessResumed {
                    navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_settings)))
                },
            onManageServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
        )
    }
    entry<MediaRoute> {
        MediaScreen(
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            onFavoritesClick = dropUnlessResumed { navigator.navigate(FavoritesRoute) },
            searchExpanded = searchExpanded.value,
            onSearchExpand = { searchExpanded.value = it },
        )
    }
    entry<DownloadsRoute>(metadata = MAIN_PAGE_ENTRY) {
        val onItemClick = resumedAction<FindroidItem> { item -> navigateToItem(navigator, item) }
        val onJrwiItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) }
        // JRWI's are in its own mode's Downloads, while it has one
        val scope = if (jrwiShown) DownloadsScope.NOT_JRWI else DownloadsScope.ALL
        // With TubeArchivist, the downloads on this phone and the download queue on the server are
        // two sides to swipe between, or to switch with the title
        if (LocalTubeArchivistMode.current) {
            if (!LocalTaMainPagerShown.current) {
                TaDownloadsScreen(
                    onItemClick = onItemClick,
                    onChannelClick =
                        resumedAction<String> { navigator.navigateOrReuse(TaChannelRoute(it)) },
                    onJrwiItemClick = onJrwiItemClick,
                    downloadsScope = scope,
                )
            }
        } else {
            DownloadsScreen(
                onItemClick = onItemClick,
                onJrwiItemClick = onJrwiItemClick,
                scope = scope,
            )
        }
    }
    entry<DownloadStorageRoute> {
        DownloadStorageScreen(
            navigateBack = dropUnlessResumed { navigator.goBack() },
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
        )
    }
    entry<PlaylistsRoute> {
        PlaylistsScreen(onItemClick = resumedAction { item -> navigateToItem(navigator, item) })
    }
    entry<PlaylistRoute> { key ->
        PlaylistScreen(
            playlistId = UUID.fromString(key.playlistId),
            playlistName = key.playlistName,
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<LibraryRoute> { key ->
        LibraryScreen(
            libraryId = UUID.fromString(key.libraryId),
            libraryName = key.libraryName,
            libraryType = key.libraryType,
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<CollectionRoute> { key ->
        CollectionScreen(
            collectionId = UUID.fromString(key.collectionId),
            collectionName = key.collectionName,
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<FavoritesRoute> {
        FavoritesScreen(
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<MovieRoute> { key ->
        MovieScreen(
            movieId = UUID.fromString(key.movieId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
        )
    }
    entry<ShowRoute> { key ->
        ShowScreen(
            showId = UUID.fromString(key.showId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                    )
                },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
        )
    }
    entry<SeasonRoute> { key ->
        SeasonScreen(
            seasonId = UUID.fromString(key.seasonId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                    )
                },
            navigateToSeries =
                resumedAction { seriesId ->
                    navigator.navigateOrReuse(ShowRoute(showId = seriesId.toString()))
                },
        )
    }
    entry<EpisodeRoute> { key ->
        EpisodeScreen(
            episodeId = UUID.fromString(key.episodeId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
            navigateToSeason =
                resumedAction { seasonId ->
                    navigator.navigateOrReuse(SeasonRoute(seasonId = seasonId.toString()))
                },
            navigateToShow =
                resumedAction { showId ->
                    navigator.navigateOrReuse(ShowRoute(showId = showId.toString()))
                },
        )
    }
    entry<PersonRoute> { key ->
        PersonScreen(
            personId = UUID.fromString(key.personId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                    )
                },
        )
    }
    entry<SettingsRoute> { key ->
        SettingsScreen(
            indexes = key.indexes,
            navigateToSettings =
                resumedAction { indexes -> navigator.navigate(SettingsRoute(indexes = indexes)) },
            navigateToSettingsFileEdit =
                resumedAction { filePath ->
                    navigator.navigate(SettingsFileEditRoute(filePath = filePath))
                },
            navigateToPlayerButtons =
                dropUnlessResumed { navigator.navigate(SettingsPlayerButtonsRoute) },
            navigateToStorage = dropUnlessResumed { navigator.navigate(DownloadStorageRoute) },
            navigateToTubeArchivist = dropUnlessResumed { navigator.navigate(TaSettingsRoute) },
            navigateToJrwi = dropUnlessResumed { navigator.navigate(JrwiSettingsRoute) },
            navigateToServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
            navigateToUsers = dropUnlessResumed { navigator.navigate(UsersRoute) },
            navigateToAbout = dropUnlessResumed { navigator.navigate(AboutRoute) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<SettingsFileEditRoute> { key ->
        SettingsFileEditScreen(
            filePath = key.filePath,
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<SettingsPlayerButtonsRoute> {
        PlayerButtonsScreen(navigateBack = dropUnlessResumed { navigator.goBack() })
    }
    entry<AboutRoute> { AboutScreen(navigateBack = dropUnlessResumed { navigator.goBack() }) }
}

/** JRWI mode's Downloads: the JRWI episodes' audio and videos on the phone, in its colours. */
@Composable
private fun JrwiDownloads(onItemClick: (FindroidItem) -> Unit, onJrwiItemClick: (Long) -> Unit) {
    JrwiTheme {
        DownloadsScreen(
            onItemClick = onItemClick,
            onJrwiItemClick = onJrwiItemClick,
            // Apart from TubeArchivist's Downloads, which lists the others
            viewModel = hiltViewModel(key = JRWI_DOWNLOADS_KEY),
            scope = DownloadsScope.JRWI,
            topBarHeight = JRWI_BAR_HEIGHT,
        )
    }
}

private const val JRWI_DOWNLOADS_KEY = "jrwiDownloads"

private fun navigateToItem(navigator: Navigator, item: FindroidItem) {
    when (item) {
        is FindroidBoxSet ->
            navigator.navigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is FindroidMovie -> navigator.navigate(MovieRoute(movieId = item.id.toString()))
        is FindroidShow -> navigator.navigate(ShowRoute(showId = item.id.toString()))
        is FindroidSeason -> navigator.navigate(SeasonRoute(seasonId = item.id.toString()))
        is FindroidEpisode ->
            navigator.navigate(
                navigator.episodeRedirect(item.id) ?: EpisodeRoute(episodeId = item.id.toString())
            )
        is FindroidPlaylist ->
            navigator.navigate(
                PlaylistRoute(playlistId = item.id.toString(), playlistName = item.name)
            )
        is FindroidCollection ->
            navigator.navigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = item.type,
                )
            )
        is FindroidFolder ->
            navigator.navigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = CollectionType.Folders,
                )
            )
        else -> Unit
    }
}

private fun EntryProviderScope<NavKey>.tubeArchivistEntries(navigator: Navigator) {
    val openVideo: (String) -> Unit = { videoId -> navigator.navigate(TaVideoRoute(videoId)) }
    val openChannel: (String) -> Unit = { channelId ->
        navigator.navigateOrReuse(TaChannelRoute(channelId))
    }
    val openPlaylist: (String) -> Unit = { playlistId ->
        navigator.navigate(TaPlaylistRoute(playlistId))
    }
    entry<TaHomeRoute>(metadata = MAIN_PAGE_ENTRY) {
        // Shown by the main pager underneath instead
        if (LocalTaMainPagerShown.current) return@entry
        TaHomeScreen(
            onVideoClick = resumedAction<String> { openVideo(it) },
            onChannelClick = resumedAction<String> { openChannel(it) },
            onPlaylistClick = resumedAction<String> { openPlaylist(it) },
            onSearchClick = dropUnlessResumed { navigator.navigate(TaSearchRoute) },
            onSettingsClick =
                dropUnlessResumed {
                    navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_settings)))
                },
            onManageServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
            onTubeArchivistSettings = dropUnlessResumed { navigator.navigate(TaSettingsRoute) },
        )
    }
    entry<TaChannelsRoute>(metadata = MAIN_PAGE_ENTRY) {
        if (LocalTaMainPagerShown.current) return@entry
        TaChannelsScreen(
            onChannelClick = resumedAction<String> { openChannel(it) },
            onPlaylistClick = resumedAction<String> { openPlaylist(it) },
        )
    }
    entry<TaQueueRoute> {
        TaQueueScreen(onChannelClick = resumedAction<String> { openChannel(it) })
    }
    entry<TaSearchRoute> {
        TaSearchScreen(
            onVideoClick = resumedAction<String> { openVideo(it) },
            onChannelClick = resumedAction<String> { openChannel(it) },
            onPlaylistClick = resumedAction<String> { openPlaylist(it) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<TaSettingsRoute> {
        TaSettingsScreen(navigateBack = dropUnlessResumed { navigator.goBack() })
    }
    // JRWI mode's tabs are in the main pager, which shows through their empty entries
    entry<JrwiRoute>(metadata = MAIN_PAGE_ENTRY) {
        if (LocalTaMainPagerShown.current) return@entry
        JrwiHomeScreen(
            onItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
            onShowClick = resumedAction<String> { navigator.navigate(JrwiShowRoute(it)) },
            onSearchClick = dropUnlessResumed { navigator.navigate(JrwiSearchRoute) },
            onSettingsClick =
                dropUnlessResumed {
                    navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_settings)))
                },
            onSetUp = dropUnlessResumed { navigator.navigate(JrwiSettingsRoute) },
            sides = rememberSidePagerState(serverFirst = true),
        )
    }
    entry<JrwiSearchRoute> {
        JrwiSearchScreen(
            onItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<JrwiShowsRoute>(metadata = MAIN_PAGE_ENTRY) {
        if (LocalTaMainPagerShown.current) return@entry
        JrwiShowsScreen(
            onShowClick = resumedAction<String> { navigator.navigate(JrwiShowRoute(it)) },
            onSetUp = dropUnlessResumed { navigator.navigate(JrwiSettingsRoute) },
        )
    }
    entry<JrwiDownloadsRoute>(metadata = MAIN_PAGE_ENTRY) {
        if (LocalTaMainPagerShown.current) return@entry
        JrwiDownloads(
            onItemClick = resumedAction<FindroidItem> { item -> navigateToItem(navigator, item) },
            onJrwiItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
        )
    }
    entry<JrwiShowRoute> { key ->
        JrwiShowScreen(
            showId = key.showId,
            onItemClick = resumedAction<Long> { navigator.navigate(JrwiItemRoute(it)) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<JrwiItemRoute> { key ->
        JrwiItemScreen(
            postId = key.postId,
            onOpenYoutube = resumedAction<String> { openVideo(it) },
            // As a TubeArchivist video's channel: back to the show's page when it's under this one
            onShowClick = resumedAction<String> { navigator.navigateOrReuse(JrwiShowRoute(it)) },
            onOpenAudioPlayer = { navigator.navigate(AudioPlayerRoute) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
            playVideoOnOpen = key.playVideo,
        )
    }
    entry<AudioPlayerRoute> {
        val context = LocalContext.current
        val audioViewModel: AudioViewModel = hiltViewModel()
        AudioPlayerScreen(
            onWatchVideo = { episode, positionMs ->
                navigator.goBack()
                when (episode) {
                    // The episode's page takes the player's place, and plays the video
                    is AudioEpisode.Jrwi ->
                        navigator.navigate(JrwiItemRoute(episode.item.id, playVideo = true))
                    // The video player, from the same moment
                    is AudioEpisode.Video ->
                        startTaPlayback(
                            context = context,
                            episodeId = episode.itemId,
                            startFromBeginning = false,
                            positionSeconds = positionMs?.div(1000.0),
                            playlistId = episode.playlistId,
                            showVideo = true,
                            itemKind = episode.kind,
                        )
                }
            },
            onOpenEpisode =
                resumedAction<AudioEpisode> { episode ->
                    when (episode) {
                        is AudioEpisode.Jrwi ->
                            episode.item.audio?.let { navigator.navigate(JrwiItemRoute(it.postId)) }
                        // As the video player opens it: the TubeArchivist video, the JRWI episode,
                        // or the Jellyfin episode or movie
                        is AudioEpisode.Video -> {
                            val jrwiId = audioViewModel.player.state.value.linkedJrwiId
                            navigator.navigate(
                                when {
                                    episode.videoId != null ->
                                        TaVideoRoute(episode.videoId, episode.playlistId)
                                    jrwiId != null -> JrwiItemRoute(jrwiId)
                                    episode.kind == BaseItemKind.MOVIE ->
                                        MovieRoute(movieId = episode.itemId.toString())
                                    else -> EpisodeRoute(episodeId = episode.itemId.toString())
                                }
                            )
                        }
                    }
                },
            navigateBack = dropUnlessResumed { navigator.goBack() },
            viewModel = audioViewModel,
        )
    }
    entry<JrwiSettingsRoute> {
        JrwiSettingsScreen(navigateBack = dropUnlessResumed { navigator.goBack() })
    }
    entry<TaVideoRoute> { key ->
        TaVideoScreen(
            videoId = key.videoId,
            playlistId = key.playlistId,
            onVideoClick = resumedAction<String> { openVideo(it) },
            onChannelClick = resumedAction<String> { openChannel(it) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<TaChannelRoute> { key ->
        TaChannelScreen(
            channelId = key.channelId,
            onVideoClick = resumedAction<String> { openVideo(it) },
            onPlaylistClick = resumedAction<String> { openPlaylist(it) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<TaPlaylistRoute> { key ->
        TaPlaylistScreen(
            playlistId = key.playlistId,
            // Played from here, the playlist's next videos follow
            onVideoClick =
                resumedAction<String> { navigator.navigate(TaVideoRoute(it, key.playlistId)) },
            onChannelClick = resumedAction<String> { openChannel(it) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
}

/**
 * The open tab's highlight in JRWI mode: the bar's or rail's own size (64 or 56 by 32), with small
 * corners, growing from the middle as the bar's own does. Drawn behind the icon, past its edges.
 */
@Composable
private fun JrwiNavHighlight(selected: Boolean, rail: Boolean, content: @Composable () -> Unit) {
    val shown by animateFloatAsState(if (selected) 1f else 0f, label = "highlight")
    val width = if (rail) RAIL_HIGHLIGHT_WIDTH else BAR_HIGHLIGHT_WIDTH
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.drawBehind {
                if (shown <= 0f) return@drawBehind
                val w = width.toPx() * shown
                val h = HIGHLIGHT_HEIGHT.toPx()
                drawRoundRect(
                    color = JrwiPurple.Accent.copy(alpha = shown),
                    topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(HIGHLIGHT_CORNER.toPx()),
                )
            },
    ) {
        content()
    }
}

private val BAR_HIGHLIGHT_WIDTH = 64.dp
private val RAIL_HIGHLIGHT_WIDTH = 56.dp
private val HIGHLIGHT_HEIGHT = 32.dp
private val HIGHLIGHT_CORNER = 6.dp
