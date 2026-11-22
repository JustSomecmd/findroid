package dev.jdtech.jellyfin.player.core.domain.models

import android.graphics.Bitmap

/** The thumbnails shown while scrubbing through a video: one for every [interval] milliseconds. */
interface Trickplay {
    val interval: Int

    /**
     * The thumbnail at [index], or null when it isn't at hand. Thumbnails are read from their sheet
     * when one of them is first asked for, so that's usually why: [whenRead] is then called once it
     * can be had, unless another was asked for in the meantime. Null and no call when there's none
     * to be had.
     *
     * For the main thread, where [whenRead] is called too.
     */
    fun image(index: Int, whenRead: () -> Unit): Bitmap?
}
