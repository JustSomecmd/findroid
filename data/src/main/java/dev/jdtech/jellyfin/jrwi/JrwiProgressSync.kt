package dev.jdtech.jellyfin.jrwi

import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/** An episode's progress as just saved, for the item [itemId] in lists. */
data class JrwiProgressChange(val itemId: Long, val progress: JrwiProgress)

/**
 * Keeps the video and the audio of a JRWI episode on one position and one watched state, the way
 * the app keeps TubeArchivist and Jellyfin in step. The archiver keeps one record per episode and
 * reads and saves it through either version's post, each in its own timeline. The video's progress
 * also lives in Jellyfin (or in TubeArchivist, for videos only on YouTube), so playing the audio
 * hands its position on to there, moved by the offset between the two.
 *
 * Every save is also kept on the phone. One made without a connection waits there, and [sendLater]
 * arranges for it to be sent with the time it was made once the archiver can be reached.
 */
class JrwiProgressSync(
    private val jrwiApi: JrwiApi,
    private val lookup: JrwiJellyfinLookup,
    private val taApi: TaApi,
    private val store: JrwiProgressStore,
    private val sendLater: () -> Unit,
    private val repository: () -> JellyfinRepository,
) {
    private val showNames = ConcurrentHashMap<String, String>()

    private val _changes = MutableSharedFlow<JrwiProgressChange>(extraBufferCapacity = 16)

    /** Progress as it's saved from this app, so lists can update their marks. */
    val changes = _changes.asSharedFlow()

    /**
     * How far the episode was played, in the timeline of its video or, with [audio], its audio.
     * When the archiver can't be reached, what was last saved from this phone. Null without that
     * version, or when neither has anything.
     */
    suspend fun progress(item: JrwiItem, audio: Boolean): JrwiProgress? {
        val postId = (if (audio) item.audio?.postId else item.video?.postId) ?: return null
        return try {
            // A save that's still waiting goes first, so it isn't read over
            sendWaiting(item.id)
            jrwiApi.getProgress(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not get the progress of $postId")
            savedProgress(item, postId)
        }
    }

    /** What was last saved from this phone, moved to the timeline of the post [postId]. */
    private fun savedProgress(item: JrwiItem, postId: Long): JrwiProgress? {
        val saved = store.last(item.id) ?: return null
        if (saved.postId == postId) return saved
        val toAudio = postId == item.audio?.postId
        val position =
            when {
                saved.finished -> 0.0
                toAudio -> item.audioSeconds(saved.position)
                else -> item.videoSeconds(saved.position)
            }
        val duration = if (toAudio) item.audio?.duration else item.video?.duration
        return saved.copy(postId = postId, position = position, duration = duration)
    }

    /**
     * Sends the saves made without a connection, each with the time it was made, so the archiver
     * keeps whichever is newer. False when the archiver still can't be reached, to try again later.
     */
    suspend fun sendPending(): Boolean {
        if (!jrwiApi.isConfigured) return true
        for ((itemId, progress) in store.pending()) {
            try {
                send(itemId, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                // The archiver won't take it, which another try won't change
                Timber.w(e, "The archiver refused saved progress of $itemId")
                store.clearPending(itemId, progress)
            } catch (e: IOException) {
                Timber.w(e, "Could not send saved progress of $itemId")
                return false
            }
        }
        return true
    }

    private suspend fun sendWaiting(itemId: Long) {
        val waiting = store.pending(itemId) ?: return
        try {
            send(itemId, waiting)
        } catch (e: JrwiApiException) {
            Timber.w(e, "The archiver refused saved progress of $itemId")
            store.clearPending(itemId, waiting)
        }
    }

    private suspend fun send(itemId: Long, progress: JrwiProgress) {
        val postId = progress.postId ?: return store.clearPending(itemId, progress)
        val saved =
            jrwiApi.setProgress(
                postId,
                progress.position,
                progress.duration,
                progress.finished,
                updatedAt = progress.updatedAt,
            )
        store.clearPending(itemId, progress)
        _changes.tryEmit(JrwiProgressChange(itemId, saved))
    }

    /**
     * The archiver's item of a TubeArchivist video, when it's one of its YouTube-only videos. An
     * item whose own video is archived plays from Jellyfin instead, so it isn't one.
     */
    suspend fun itemForYoutube(youtubeId: String): JrwiItem? {
        if (!jrwiApi.isConfigured) return null
        return try {
            // Older archivers ignore the filter and list their newest items, which this skips
            val items = jrwiApi.search(youtubeId = youtubeId, pageSize = 5).items
            items.firstOrNull { it.video?.youtubeId == youtubeId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not get the JRWI item of YouTube video $youtubeId")
            null
        }
    }

    /** Jellyfin's copy of the item's archived video, or null when Jellyfin doesn't have it. */
    suspend fun episodeId(item: JrwiItem): UUID? {
        val video = item.video?.takeIf { !it.isYoutube } ?: return null
        return try {
            lookup.findEpisodeId(video.postId, showName(item.show))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not look up ${video.postId} in Jellyfin")
            null
        }
    }

    /** The archiver's item of a Jellyfin episode, when that episode is one of its videos. */
    suspend fun itemForEpisode(itemId: UUID): JrwiItem? {
        if (!jrwiApi.isConfigured) return null
        return try {
            val postId = lookup.postIdForItem(itemId) ?: return null
            jrwiApi.getItem(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not get the JRWI item of $itemId")
            null
        }
    }

    /**
     * The video was played to [videoSeconds]. Saved on the archiver under the video's own post,
     * which also moves the audio, if there is one, to the same moment. [durationSeconds] is the
     * player's length of the video, for when the archiver doesn't know it.
     */
    suspend fun videoPlayed(
        item: JrwiItem,
        videoSeconds: Double,
        finished: Boolean,
        durationSeconds: Double?,
    ) {
        val video = item.video ?: return
        save(
            item,
            video.postId,
            if (finished) 0.0 else videoSeconds,
            video.duration ?: durationSeconds,
            finished,
        )
    }

    /**
     * The audio was played to [audioSeconds]. Saved on the archiver every time; [handOff] also
     * moves the video to the same moment, which happens when it's paused or left rather than every
     * few seconds.
     */
    suspend fun audioPlayed(
        item: JrwiItem,
        audioSeconds: Double,
        finished: Boolean,
        handOff: Boolean,
        durationSeconds: Double?,
    ) {
        val audio = item.audio ?: return
        save(
            item,
            audio.postId,
            if (finished) 0.0 else audioSeconds,
            audio.duration ?: durationSeconds,
            finished,
        )
        if (!handOff && !finished) return
        val video = item.video ?: return
        val videoSeconds = item.videoSeconds(audioSeconds)
        if (video.isYoutube) {
            val youtubeId = video.youtubeId ?: return
            if (!taApi.isConfigured) return
            if (finished) taApi.setWatched(youtubeId, true)
            else taApi.postProgress(youtubeId, videoSeconds)
            return
        }
        val episodeId = episodeId(item) ?: return
        if (finished) {
            repository().markAsPlayed(episodeId)
        } else {
            val length = video.duration ?: item.duration
            val percent = length?.takeIf { it > 0 }?.let { (videoSeconds / it * 100).toInt() } ?: 0
            repository().postPlaybackStop(episodeId, (videoSeconds * TICKS).toLong(), percent)
        }
    }

    /**
     * Keeps where the audio is on the phone only, as a save still to be sent: with the next save
     * that's sent, which then has nothing more to say, or later with the time it was made. For the
     * regular saves while an episode on the phone plays with nothing showing it, so the phone's
     * radio can rest between the saves that go to the archiver.
     */
    fun audioKept(item: JrwiItem, audioSeconds: Double, durationSeconds: Double?) {
        val audio = item.audio ?: return
        val made =
            JrwiProgress(
                audio.postId,
                audioSeconds,
                audio.duration ?: durationSeconds,
                false,
                JrwiProgressStore.now(),
            )
        store.remember(item.id, made)
        store.setPending(item.id, made)
    }

    /** Marks both the video and the audio watched, or both not watched. */
    suspend fun setWatched(item: JrwiItem, watched: Boolean) {
        // The archiver's one record covers both versions, so either post will do
        val video = item.video
        val audio = item.audio
        when {
            video != null -> save(item, video.postId, 0.0, video.duration, watched)
            audio != null -> save(item, audio.postId, 0.0, audio.duration, watched)
        }
        if (video == null) return
        if (video.isYoutube) {
            val youtubeId = video.youtubeId ?: return
            if (taApi.isConfigured) taApi.setWatched(youtubeId, watched)
            return
        }
        val episodeId = episodeId(item) ?: return
        if (watched) repository().markAsPlayed(episodeId)
        else repository().markAsUnplayed(episodeId)
    }

    /**
     * Saves progress on the archiver and on the phone, and tells the lists showing [item]. Without
     * a connection, it waits on the phone to be sent later.
     */
    private suspend fun save(
        item: JrwiItem,
        postId: Long,
        position: Double,
        duration: Double?,
        finished: Boolean,
    ) {
        val made = JrwiProgress(postId, position, duration, finished, JrwiProgressStore.now())
        store.remember(item.id, made)
        val saved =
            try {
                jrwiApi.setProgress(postId, position, duration, finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "No connection to the archiver, so progress of ${item.id} waits")
                store.setPending(item.id, made)
                sendLater()
                made
            }
        // Saved now, so an older one still waiting has nothing more to say
        if (saved !== made) store.clearPending(item.id)
        _changes.tryEmit(JrwiProgressChange(item.id, saved))
    }

    /**
     * Episodes to continue, as the archiver's web app has them: from the archiver when it lists
     * them, or else those started and not finished on this phone, the one played most recently
     * first, without those the archive has as finished since.
     */
    suspend fun continueWatching(limit: Int = CONTINUE_LIMIT): List<JrwiContinue> {
        // Asked of the archiver unless it lists its features without this one
        if (jrwiApi.has(JrwiFeature.CONTINUE) != false) {
            try {
                return jrwiApi.getContinue(limit)
            } catch (e: JrwiApiException) {
                if (e.code != NOT_FOUND) throw e
            }
        }
        val started =
            store
                .allLast()
                .filter { (_, progress) ->
                    val length = progress.duration
                    !progress.finished &&
                        progress.position >= CONTINUE_MIN_SECONDS &&
                        (length == null || progress.position < length)
                }
                .entries
                .sortedByDescending { it.value.updatedAt.orEmpty() }
                .take(limit)
        return coroutineScope {
            started
                .map { (itemId, progress) ->
                    async {
                        try {
                            jrwiApi
                                .getItem(itemId)
                                .takeIf { it.progress?.finished != true }
                                ?.let { JrwiContinue(it, progress) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    /** The name of a show, or null when the archiver can't be reached. */
    suspend fun showNameOrNull(showId: String): String? =
        try {
            showName(showId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** The name of a show, which is also the name of its folder in Jellyfin. */
    private suspend fun showName(showId: String): String =
        showNames[showId] ?: jrwiApi.getShow(showId).name.also { showNames[showId] = it }

    private companion object {
        // Jellyfin counts time in ticks of 100 nanoseconds
        const val TICKS = 10_000_000.0
        const val NOT_FOUND = 404
        const val CONTINUE_LIMIT = 12
        // As the web app: started means 10 seconds or more in
        const val CONTINUE_MIN_SECONDS = 10.0
    }
}
