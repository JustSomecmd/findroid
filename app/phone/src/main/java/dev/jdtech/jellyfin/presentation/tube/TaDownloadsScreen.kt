package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsScope
import dev.jdtech.jellyfin.film.presentation.tube.TaQueueViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.theme.ServerSideTheme

/**
 * Downloads with TubeArchivist: the downloads on this phone and the download queue on the server,
 * side by side under one top bar, like the other pages with a phone and a server side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaDownloadsScreen(
    onItemClick: (FindroidItem) -> Unit,
    /** Opens the channel of a video in the server's queue. */
    onChannelClick: (channelId: String) -> Unit = {},
    /** Opens the page of a JRWI episode whose audio is on the phone. */
    onJrwiItemClick: (itemId: Long) -> Unit = {},
    /** The phone and server sides, when the main pager needs to turn them. */
    sides: PagerState = rememberSidePagerState(),
    /** Which downloads on the phone it lists: without JRWI's when JRWI has its own mode. */
    downloadsScope: DownloadsScope = DownloadsScope.ALL,
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val queue: TaQueueViewModel = hiltViewModel()
    // Up to date each time the server side is turned to
    LaunchedEffect(sides.onServerSide) { if (sides.onServerSide) queue.refresh() }

    ServerSideTheme(pagerState = sides) {
        Scaffold(
            modifier =
                Modifier.fillMaxSize()
                    .recalculateWindowInsetsUnlessPaged()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        TaSideTitle(
                            title = stringResource(CoreR.string.title_download),
                            onServer = sides.onServerSide,
                            onToggle = { sides.switchSide(scope) },
                        )
                    },
                    actions = {
                        // The queue's buttons, only while the queue is in view
                        AnimatedVisibility(
                            visible = sides.onServerSide,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Row { TaQueueActions() }
                        }
                    },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                    colors = alwaysShownTopAppBarColors(),
                )
            },
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        ) { innerPadding ->
            TaSidePager(
                state = sides,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                phone = {
                    DownloadsScreen(
                        onItemClick = onItemClick,
                        onJrwiItemClick = onJrwiItemClick,
                        embedded = true,
                        scope = downloadsScope,
                    )
                },
                server = { TaQueueScreen(embedded = true, onChannelClick = onChannelClick) },
            )
        }
    }
}
