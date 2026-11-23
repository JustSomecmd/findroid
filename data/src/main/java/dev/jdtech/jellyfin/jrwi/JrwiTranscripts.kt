package dev.jdtech.jellyfin.jrwi

import android.os.SystemClock
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Episodes' transcripts for the players (v0.23): from the archiver, or without a connection the
 * copy kept with a download. The last two read are kept for 10 minutes, so opening the lines again,
 * or the other player, doesn't ask again, while lines the archiver has timed again since (on a PC,
 * say) show the next time they're opened after that. A copy on the phone is brought up to date
 * whenever the archiver gives the lines again.
 */
class JrwiTranscripts(private val jrwiApi: JrwiApi, private val downloads: JrwiDownloads) {
    private class Kept(val transcript: JrwiTranscript, val at: Long)

    // The most recently read last
    private val kept = LinkedHashMap<Long, Kept>()

    /**
     * The lines of the transcript, timed in the timeline of the post [postId]. Null when the
     * archiver says the episode has none. Throws when they can't be read and none are kept.
     */
    suspend fun get(postId: Long): JrwiTranscript? {
        keptFor(postId)?.let {
            return it
        }
        val transcript =
            try {
                if (!jrwiApi.isConfigured) throw IOException("The JRWI archiver is not set up")
                // Read off the main thread, as the lines of a long episode take a moment
                withContext(Dispatchers.IO) {
                    jrwiApi.getTranscript(postId).also { transcript ->
                        if (downloads.keepsTranscript(postId)) {
                            downloads.saveTranscript(postId, transcript)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                if (e.code == NOT_FOUND) return null
                saved(postId) ?: throw e
            } catch (e: IOException) {
                Timber.w(e, "Could not read the transcript of $postId")
                saved(postId) ?: throw e
            }
        keep(postId, transcript)
        return transcript
    }

    /**
     * Keeps the transcript of the post [postId] on the phone with its download. Nothing happens
     * when it can't be read now; it's kept the next time it's read with a connection.
     */
    suspend fun keepWithDownload(postId: Long) {
        try {
            val transcript =
                withContext(Dispatchers.IO) {
                    jrwiApi.getTranscript(postId).also { downloads.saveTranscript(postId, it) }
                }
            keep(postId, transcript)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not keep the transcript of $postId")
        }
    }

    private suspend fun saved(postId: Long): JrwiTranscript? =
        withContext(Dispatchers.IO) { downloads.savedTranscript(postId) }

    // Asked for again after a while, as the archiver may have timed them, or timed them again
    @Synchronized
    private fun keptFor(postId: Long): JrwiTranscript? {
        val entry = kept[postId] ?: return null
        val age = SystemClock.elapsedRealtime() - entry.at
        if (age > KEPT_MS) {
            kept.remove(postId)
            return null
        }
        // Read again: the most recent
        kept.remove(postId)
        kept[postId] = entry
        return entry.transcript
    }

    @Synchronized
    private fun keep(postId: Long, transcript: JrwiTranscript) {
        kept.remove(postId)
        kept[postId] = Kept(transcript, SystemClock.elapsedRealtime())
        while (kept.size > KEPT) kept.remove(kept.keys.first())
    }

    private companion object {
        const val NOT_FOUND = 404
        // A three-hour episode's lines are about 250 KB of text
        const val KEPT = 2
        const val KEPT_MS = 10 * 60_000L
    }
}
