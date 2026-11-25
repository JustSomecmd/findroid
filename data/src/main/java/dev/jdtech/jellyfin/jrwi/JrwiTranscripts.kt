package dev.jdtech.jellyfin.jrwi

import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // A check of the kept transcripts goes on when the page that started it is left
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _check = MutableStateFlow<JrwiTranscriptsCheck?>(null)

    /** How the last check of the kept transcripts went, or is going; null before the first. */
    val check: StateFlow<JrwiTranscriptsCheck?> = _check.asStateFlow()

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

    /**
     * Checks the transcript of every download on the phone against the archive, one after another,
     * and keeps the lines again where their text or timings changed, or where none were kept yet. A
     * download that kept the post's files gets the transcript's PDF again when the archive's is
     * another size. Nothing happens while a check runs, or without an archiver.
     */
    fun checkKept() {
        if (_check.value?.running == true || !jrwiApi.isConfigured) return
        _check.value = JrwiTranscriptsCheck()
        scope.launch {
            var state = JrwiTranscriptsCheck()
            try {
                val targets = downloads.transcriptTargets()
                state = state.copy(total = targets.size)
                _check.value = state
                for (target in targets) {
                    state = checkOne(target, state).let { it.copy(done = it.done + 1) }
                    _check.value = state
                }
            } finally {
                _check.value = state.copy(running = false)
            }
        }
    }

    private suspend fun checkOne(
        target: JrwiTranscriptTarget,
        state: JrwiTranscriptsCheck,
    ): JrwiTranscriptsCheck {
        val fresh =
            try {
                jrwiApi.getTranscript(target.postId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                // An episode without a transcript has nothing to check
                if (e.code == NOT_FOUND) return state
                Timber.w(e, "Could not check the transcript of ${target.postId}")
                return state.copy(failed = state.failed + 1)
            } catch (e: Exception) {
                Timber.w(e, "Could not check the transcript of ${target.postId}")
                return state.copy(failed = state.failed + 1)
            }
        var next = state
        val old = downloads.savedTranscript(target.postId)
        if (old != fresh) {
            downloads.saveTranscript(target.postId, fresh)
            replaceKept(target.postId, fresh)
            next =
                if (old == null) next.copy(added = next.added + 1)
                else next.copy(updated = next.updated + 1)
        }
        val document = fresh.document
        if (target.withFiles && document != null && keepDocument(target.itemId, document)) {
            next = next.copy(documents = next.documents + 1)
        }
        return next
    }

    /**
     * Saves the transcript's PDF [document] again among the item's kept files when the phone's copy
     * is missing or another size than the archive's. True when it was saved.
     */
    private suspend fun keepDocument(itemId: Long, document: String): Boolean {
        val files =
            try {
                jrwiApi.getItemFiles(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not list the files of $itemId")
                return false
            }
        val decoded = Uri.decode(document).removePrefix("/files/")
        val file = files.firstOrNull { it.url == document || it.path == decoded } ?: return false
        val path = file.path ?: return false
        val url = file.url ?: return false
        if (!file.saved) return false
        val target = downloads.fileFor(itemId, path)
        if (target.isFile && (file.size == null || target.length() == file.size)) return false
        try {
            jrwiApi.openFile(url).use { response ->
                if (!response.isSuccessful) return false
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".part")
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return false
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "Could not keep the transcript's PDF $path")
            return false
        }
        // The list of kept files says the new size too
        downloads.savedFiles(itemId)?.let { kept ->
            val listed = kept.any { it.path == path }
            downloads.saveFiles(
                itemId,
                if (listed) kept.map { if (it.path == path) file else it } else kept + file,
            )
        }
        return true
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

    // Lines read lately and changed since show the new ones next time
    @Synchronized
    private fun replaceKept(postId: Long, transcript: JrwiTranscript) {
        if (kept.containsKey(postId)) kept[postId] = Kept(transcript, SystemClock.elapsedRealtime())
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

/**
 * A check of the transcripts kept with downloads: [done] of [total] downloads so far, and of those
 * [updated] with changed lines or timings, [added] kept for the first time, [documents] whose PDF
 * was saved again, and [failed] that couldn't be checked.
 */
data class JrwiTranscriptsCheck(
    val running: Boolean = true,
    val done: Int = 0,
    val total: Int = 0,
    val updated: Int = 0,
    val added: Int = 0,
    val documents: Int = 0,
    val failed: Int = 0,
)
