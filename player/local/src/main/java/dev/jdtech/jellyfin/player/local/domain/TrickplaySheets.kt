package dev.jdtech.jellyfin.player.local.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A video's scrubbing thumbnails, kept the way the server sends them: as sheets of many thumbnails
 * each, still compressed. A sheet is read into its thumbnails when the scrubber first gets to one
 * of them, and only the last [MAX_READ] sheets read stay that way.
 *
 * Read, a thumbnail takes its width times its height times four bytes, so at Jellyfin's usual
 * settings (320 by 180, one every ten seconds, a hundred to a sheet) every hour of video is 83 MB
 * of them. Reading them all when a video starts, as was done before, held that for as long as the
 * video played, scrubbed or not.
 *
 * Everything here happens on the main thread, which [scope] has to run on, apart from fetching a
 * sheet and reading it.
 *
 * @param scope Cancelled when the video is no longer the one playing.
 * @param fetch Gets the sheet with that number, or null when it can't be had right now.
 */
internal class TrickplaySheets(
    private val info: TrickplayInfo,
    private val scope: CoroutineScope,
    private val fetch: suspend (sheet: Int) -> ByteArray?,
) : Trickplay {
    override val interval: Int = info.interval

    private val perSheet = (info.tileWidth * info.tileHeight).coerceAtLeast(1)
    private val sheetCount =
        if (info.thumbnailCount > 0) (info.thumbnailCount + perSheet - 1) / perSheet else 0

    // The sheets as they were fetched, and one fetch at a time: whichever the scrubber waits for
    // goes before the rest
    private val sheets = arrayOfNulls<ByteArray>(sheetCount)
    private val fetching = Mutex()

    // The thumbnails of the sheets read, the one used longest ago first. A thumbnail is null where
    // its sheet turned out not to hold it
    private val read = LinkedHashMap<Int, List<Bitmap?>>(4, 0.75f, true)
    private var reading: Job? = null

    // When each sheet last couldn't be fetched or read, in SystemClock.elapsedRealtime, or 0
    private val failedAt = LongArray(sheetCount)

    // The sheet the scrubber is waiting for, and what to call when it's read
    private var wanted = NONE
    private var whenRead: (() -> Unit)? = null

    /** Fetches the sheets one after another, so each is there by the time the scrubber asks. */
    fun fetchAll() {
        scope.launch { for (sheet in 0 until sheetCount) fetched(sheet) }
    }

    override fun image(index: Int, whenRead: () -> Unit): Bitmap? {
        val sheet = if (index >= 0) index / perSheet else NONE
        val thumbnails = if (sheet in 0 until sheetCount) read[sheet] else null
        if (thumbnails != null || sheet !in 0 until sheetCount || failedLately(sheet)) {
            // Nothing to wait for, so whatever was waited for before no longer is
            wanted = NONE
            this.whenRead = null
            return thumbnails?.getOrNull(index % perSheet)
        }
        wanted = sheet
        this.whenRead = whenRead
        if (reading?.isActive != true) readSheet(sheet)
        return null
    }

    /**
     * Reads a sheet into its thumbnails. One at a time: when the scrubber has moved on to another
     * sheet by the time it's done, that one is read next, and whatever it passed over isn't.
     */
    private fun readSheet(sheet: Int) {
        reading = scope.launch {
            val bytes = fetched(sheet)
            val thumbnails = bytes?.let { withContext(Dispatchers.Default) { cut(it) } }
            if (thumbnails == null) {
                failedAt[sheet] = SystemClock.elapsedRealtime()
            } else {
                read[sheet] = thumbnails
                while (read.size > MAX_READ) read.remove(read.keys.first())
            }
            // Done reading, so that whatever is asked for from here on is read
            reading = null
            val next = wanted
            if (next == sheet) {
                val call = whenRead
                wanted = NONE
                whenRead = null
                if (thumbnails != null && call != null) {
                    try {
                        call()
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                }
            } else if (next != NONE && next !in read && !failedLately(next)) {
                readSheet(next)
            }
        }
    }

    /** A sheet that couldn't be had is left alone for a while, then tried again when asked for. */
    private fun failedLately(sheet: Int): Boolean {
        val at = failedAt[sheet]
        return at != 0L && SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS
    }

    private suspend fun fetched(sheet: Int): ByteArray? =
        sheets[sheet]
            ?: fetching.withLock { sheets[sheet] ?: fetch(sheet)?.also { sheets[sheet] = it } }

    /**
     * The thumbnails on a sheet, in the order they're numbered: row after row. Null when it can't
     * be read.
     */
    private fun cut(bytes: ByteArray): List<Bitmap?>? {
        try {
            val sheet = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val thumbnails =
                List(perSheet) { tile ->
                    val x = tile % info.tileWidth * info.width
                    val y = tile / info.tileWidth * info.height
                    // The last sheet may end below its last row of thumbnails
                    if (x + info.width <= sheet.width && y + info.height <= sheet.height) {
                        Bitmap.createBitmap(sheet, x, y, info.width, info.height)
                    } else {
                        null
                    }
                }
            // A sheet of one thumbnail is that thumbnail itself
            if (thumbnails.none { it === sheet }) sheet.recycle()
            return thumbnails
        } catch (e: Exception) {
            Timber.e(e, "Couldn't read a sheet of thumbnails")
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "No memory to read a sheet of thumbnails")
        }
        return null
    }

    private companion object {
        const val NONE = -1

        // Two, so that scrubbing back and forth over where one sheet ends and the next begins
        // doesn't read them over and over
        const val MAX_READ = 2

        const val RETRY_AFTER_MS = 10_000L
    }
}
