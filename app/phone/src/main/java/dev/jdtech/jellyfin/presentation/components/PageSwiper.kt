package dev.jdtech.jellyfin.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Swipes through a row of pages with one drag handler for the whole row. A page can have pages of
 * its own inside, such as the sides of Home or the tabs of Channels: a drag moves those first and
 * the row once they're at their end, the same distance under the finger at every level, so every
 * swipe feels the same.
 *
 * Nothing waits for a page to come to rest: a drag takes over a page that's still moving from where
 * it is, and [turnTo], for the bottom bar or back, wins over a swipe that's still going. The pagers
 * it moves have their own swiping turned off; [pageSwipes] hands it the drags that nothing inside
 * the pages took, so rows that scroll sideways keep their swipes to themselves.
 */
@Stable
class PageSwiper internal constructor(val pages: PagerState, private val scope: CoroutineScope) {
    /** A page's own pages inside it, if it has any. */
    var innerPages: (Int) -> PagerState? = { null }

    /** Called when a swipe or a turn has come to rest on a page. */
    var onSettled: (Int) -> Unit = {}

    // How fast a flick has to be, in pixels a second, to go on to the next page
    internal var flickSpeed = 0f

    // 1 when dragging to the left goes forward, -1 right to left
    internal var forward = 1f

    /**
     * The page the row is heading to while it moves: where it would come to rest if let go, or
     * where a flick or a turn is taking it. Null while it rests.
     */
    var target by mutableStateOf<Int?>(null)
        private set

    // Counts takeovers, so a drag or animation that was taken over leaves the rest alone
    private var gesture = 0
    private var dragging = false
    private var motion: Job? = null
    private var turning: Int? = null
    private val holds = mutableMapOf<PagerState, Job>()

    // Where the row and the pages inside it are, in pages, while they move. Kept here rather than
    // read back from the pagers, which only catch up when they're next laid out.
    private var mainPos = 0f
    private var mainOrigin = 0f
    private val innerPos = mutableMapOf<Int, Float>()
    private val innerOrigin = mutableMapOf<Int, Float>()

    private val moving: Boolean
        get() = dragging || motion?.isActive == true

    /** A drag starts, also while the pages still move. Gives the drag's number. */
    fun startDrag(): Int {
        val id = takeOver()
        dragging = true
        mainOrigin = mainPos
        innerOrigin.clear()
        innerOrigin.putAll(innerPos)
        return id
    }

    /** The finger moved by [dx] pixels. */
    fun dragBy(id: Int, dx: Float) {
        if (id != gesture || !dragging) return
        moveBy(-dx * forward)
        target = if (onPage(mainPos)) mainPos.roundToInt() else restingPage(mainPos, mainOrigin, 0f)
    }

    /** The finger let go, moving at [velocityX] pixels a second. */
    fun release(id: Int, velocityX: Float) {
        if (id != gesture || !dragging) return
        dragging = false
        val velocity = -velocityX * forward
        motion = scope.launch {
            var completed = false
            try {
                settle(velocity)
                completed = true
            } finally {
                if (id == gesture) finish(completed)
            }
        }
    }

    /** Turns the row to [page], for the bottom bar or back, over any swipe that's still going. */
    fun turnTo(page: Int) {
        if (page !in 0 until pages.pageCount) return
        if (turning == page && motion?.isActive == true) return
        if (!moving && pages.currentPage == page && pages.currentPageOffsetFraction == 0f) return
        val id = takeOver()
        turning = page
        target = page
        motion = scope.launch {
            var completed = false
            try {
                animate(mainPos, page.toFloat(), 0f) { moveMainTo(it) }
                completed = true
            } finally {
                if (id == gesture) finish(completed)
            }
        }
    }

    private fun takeOver(): Int {
        if (!moving) {
            mainPos = pages.position()
            innerPos.clear()
        }
        gesture++
        motion?.cancel()
        motion = null
        turning = null
        dragging = false
        hold(pages)
        return gesture
    }

    private fun finish(completed: Boolean) {
        releaseHolds()
        motion = null
        turning = null
        innerPos.clear()
        innerOrigin.clear()
        if (completed) onSettled(mainPos.roundToInt())
        target = null
    }

    // Marks a pager as scrolling while it's moved from here, as its own swiping would, so what
    // waits for it to settle still waits, and nothing else moves it meanwhile
    private fun hold(state: PagerState) {
        if (holds[state]?.isActive == true) return
        holds[state] =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
    }

    private fun releaseHolds() {
        holds.values.forEach { it.cancel() }
        holds.clear()
    }

    /** Moves by [px] forward: the open page's own pages first, then the row. */
    private fun moveBy(px: Float) {
        var left = px
        var steps = 0
        // At most into the next page's own pages, as a drag is never longer than a page
        while (abs(left) > MIN_PX && steps++ < MAX_STEPS) {
            val moved = moveStep(left)
            if (moved == 0f) return
            left -= moved
        }
    }

    /** Moves whichever level can go [px]'s way, and gives back how far it went. */
    private fun moveStep(px: Float): Float {
        val page = mainPos.roundToInt()
        val inner = if (onPage(mainPos)) innerPages(page) else null
        if (inner != null && canMoveInner(page, inner, px)) return moveInner(page, inner, px)
        return moveMain(px)
    }

    private fun canMoveInner(page: Int, inner: PagerState, px: Float): Boolean {
        if (inner.pageWidth() <= 0f) return false
        val pos = innerPosition(page, inner)
        return if (px > 0) pos < inner.pageCount - 1 else pos > 0
    }

    /** Moves a page's own pages by up to [px], and gives back how far they went. */
    private fun moveInner(page: Int, inner: PagerState, px: Float): Float {
        val width = inner.pageWidth()
        hold(inner)
        val from = innerPosition(page, inner)
        innerOrigin.getOrPut(page) { from }
        val to = (from + px / width).coerceIn(0f, (inner.pageCount - 1).toFloat())
        val used = inner.dispatchRawDelta((to - from) * width)
        innerPos[page] = snap(from + used / width)
        return used
    }

    /** Moves the row by up to [px], no further than the next page, and gives back how far. */
    private fun moveMain(px: Float): Float {
        val width = pages.pageWidth()
        if (width <= 0f) return 0f
        val from = mainPos
        val resting = onPage(from)
        // Stops at the page it reaches, so the rest of the drag goes into that page's own pages
        val lower = if (resting && px < 0) from - 1f else floor(from)
        val upper = if (resting && px > 0) from + 1f else ceil(from)
        val to = (from + px / width).coerceIn(lower, upper).coerceIn(0f, lastPage())
        if (resting && to != from) {
            // The page coming in shows its near end, set while it's still out of sight
            val next = if (to > from) from.roundToInt() + 1 else from.roundToInt() - 1
            innerPages(next)?.let { inner ->
                val end = if (to > from) 0 else inner.pageCount - 1
                inner.requestScrollToPage(end)
                innerPos[next] = end.toFloat()
                innerOrigin[next] = end.toFloat()
            }
        }
        val used = pages.dispatchRawDelta((to - from) * width)
        mainPos = snap(from + used / width)
        return used
    }

    /** Lets go: whichever level is between two pages comes to rest on one. */
    private suspend fun settle(velocity: Float) {
        if (!onPage(mainPos)) {
            val to = restingPage(mainPos, mainOrigin, velocity)
            target = to
            animate(mainPos, to.toFloat(), velocity / pages.pageWidth()) { moveMainTo(it) }
            return
        }
        val page = mainPos.roundToInt()
        target = page
        val inner = innerPages(page) ?: return
        val pos = innerPos[page] ?: return
        if (onPage(pos)) return
        val to = restingPage(pos, innerOrigin[page] ?: pos, velocity)
        animate(pos, to.toFloat(), velocity / inner.pageWidth()) { moveInnerTo(page, inner, it) }
    }

    /**
     * Where something between two pages comes to rest: the way a flick goes, or else the next page
     * once it's been dragged [SWIPE_THRESHOLD] of the way from the page it started on.
     */
    private fun restingPage(pos: Float, origin: Float, velocity: Float): Int {
        val lower = floor(pos)
        val upper = ceil(pos)
        val page =
            when {
                lower == upper -> pos
                velocity > flickSpeed -> upper
                velocity < -flickSpeed -> lower
                origin <= lower -> if (pos - lower > SWIPE_THRESHOLD) upper else lower
                origin >= upper -> if (upper - pos > SWIPE_THRESHOLD) lower else upper
                else -> round(pos)
            }
        return page.roundToInt()
    }

    private fun moveMainTo(pos: Float) {
        val width = pages.pageWidth()
        if (width <= 0f) return
        val used = pages.dispatchRawDelta((pos - mainPos) * width)
        mainPos = snap(mainPos + used / width)
    }

    private fun moveInnerTo(page: Int, inner: PagerState, pos: Float) {
        val width = inner.pageWidth()
        if (width <= 0f) return
        val from = innerPosition(page, inner)
        val used = inner.dispatchRawDelta((pos - from) * width)
        innerPos[page] = snap(from + used / width)
    }

    private suspend fun animate(from: Float, to: Float, velocity: Float, apply: (Float) -> Unit) {
        val position = Animatable(from)
        val spec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f)
        val initialVelocity = velocity.takeIf { it.isFinite() } ?: 0f
        position.animateTo(to, spec, initialVelocity) { apply(value) }
        // Exactly on the page
        apply(to)
    }

    private fun innerPosition(page: Int, inner: PagerState): Float =
        innerPos.getOrPut(page) { inner.position() }

    private fun lastPage(): Float = (pages.pageCount - 1).coerceAtLeast(0).toFloat()

    private companion object {
        // How far a slow drag has to go, as part of a page, to turn to the next page
        const val SWIPE_THRESHOLD = 0.3f
        const val MIN_PX = 0.01f
        const val MAX_STEPS = 4
    }
}

private fun PagerState.position(): Float = currentPage + currentPageOffsetFraction

private fun PagerState.pageWidth(): Float = (layoutInfo.pageSize + layoutInfo.pageSpacing).toFloat()

private fun onPage(pos: Float): Boolean = pos == round(pos)

// Close enough to a page to be on it, as dividing pixels by the page width never lands exactly
private fun snap(pos: Float): Float = if (abs(pos - round(pos)) < 0.0005f) round(pos) else pos

/** A [PageSwiper] for [pages]. */
@Composable
fun rememberPageSwiper(pages: PagerState): PageSwiper {
    val scope = rememberCoroutineScope()
    val swiper = remember(pages, scope) { PageSwiper(pages, scope) }
    swiper.flickSpeed = with(LocalDensity.current) { FLICK_SPEED.toPx() }
    swiper.forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    return swiper
}

// How fast a flick has to be to turn to the next page, as for Compose's own pagers
private val FLICK_SPEED = 400.dp

/**
 * Hands [swiper] the sideways drags that start on this, once nothing inside has taken them: a row
 * that scrolls sideways keeps its own swipes, also at its ends.
 */
fun Modifier.pageSwipes(swiper: PageSwiper, enabled: Boolean = true): Modifier =
    pointerInput(swiper, enabled) {
        if (!enabled) return@pointerInput
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var overSlop = 0f
            val start =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    change.consume()
                    overSlop = over
                }
            if (start == null) return@awaitEachGesture
            val id = swiper.startDrag()
            tracker.resetTracking()
            tracker.addPosition(start.uptimeMillis, start.position)
            swiper.dragBy(id, overSlop)
            val ended =
                horizontalDrag(start.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    swiper.dragBy(id, change.positionChange().x)
                    change.consume()
                }
            swiper.release(id, if (ended) tracker.calculateVelocity().x else 0f)
        }
    }
