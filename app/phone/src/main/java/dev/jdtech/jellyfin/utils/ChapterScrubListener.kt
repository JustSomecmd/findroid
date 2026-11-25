package dev.jdtech.jellyfin.utils

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.TimeBar
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter

/**
 * While the seek bar is dragged, a note over it with the name of the chapter the scrubber is in,
 * following the scrubber and kept inside the screen, under the trickplay picture when there is one.
 * Nothing shows for a video with fewer than two chapters, or for a chapter without a name.
 */
class ChapterScrubListener(
    private val note: TextView,
    private val timeBarView: View,
    private val player: Player,
) : TimeBar.OnScrubListener {
    var chapters: List<PlayerChapter> = emptyList()

    override fun onScrubStart(timeBar: TimeBar, position: Long) = onScrubMove(timeBar, position)

    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        val name = chapterName(chapters, position)
        if (name == null) {
            note.visibility = View.GONE
            return
        }
        if (note.text != name) note.text = name
        note.visibility = View.VISIBLE
        val parent = note.parent as? ViewGroup ?: return
        // Its width for this name, before it's laid out
        note.measure(
            View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = note.measuredWidth
        val duration = player.duration.takeIf { it > 0 } ?: return
        val offset = position.toFloat() / duration
        val centre = timeBarView.left + (timeBarView.right - timeBarView.left) * offset
        val minX = parent.paddingLeft.toFloat()
        val maxX = (parent.width - parent.paddingRight - width).toFloat()
        note.x = (centre - width / 2f).coerceIn(minX, maxOf(minX, maxX))
    }

    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
        note.visibility = View.GONE
    }
}

/** The name of the chapter [position] is in, when the video has chapters to tell apart. */
fun chapterName(chapters: List<PlayerChapter>, position: Long): String? {
    if (chapters.size < 2) return null
    return chapters.lastOrNull { it.startPosition <= position }?.name?.takeIf { it.isNotBlank() }
}
