package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.carouselPages
import dev.jdtech.jellyfin.presentation.utils.isWideLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// Three pages side by side when held sideways, as in the TubeArchivist carousel
private fun dynamicPageSize(wide: Boolean) =
    object : PageSize {
        override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int {
            val nPages = carouselPages(availableSpace.toDp(), wide)

            return (availableSpace - (nPages - 1) * pageSpacing) / nPages
        }
    }

@Composable
fun HomeCarousel(
    items: List<FindroidItem>,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val wide = isWideLayout()
    val pageSize = remember(wide) { dynamicPageSize(wide) }
    val pagerIsDragged by pagerState.interactionSource.collectIsDraggedAsState()

    val autoScrollDelay = 5000L

    // Turns on its own only while the page is on screen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (!pagerIsDragged) {
        LaunchedEffect(pagerState, lifecycle) {
            while (true) {
                delay(autoScrollDelay)
                // Waits while the page isn't on screen, without stopping a turn half way
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                val nextPage =
                    if (pagerState.canScrollForward) {
                        pagerState.currentPage + 1
                    } else {
                        0
                    }
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        contentPadding = itemsPadding,
        pageSize = pageSize,
        pageSpacing = MaterialTheme.spacings.medium,
    ) { page ->
        val item = items[page]
        HomeCarouselItem(item = item, onAction = onAction)
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeCarouselPreview() {
    FindroidTheme {
        HomeCarousel(
            items = dummyMovies,
            itemsPadding = PaddingValues(horizontal = 0.dp),
            onAction = {},
        )
    }
}
