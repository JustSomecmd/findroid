package dev.jdtech.jellyfin.jrwi

import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
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
 */
class JrwiProgressSync(
    private val jrwiApi: JrwiApi,
    private val lookup: JrwiJellyfinLookup,
    private val taApi: TaApi,
    private val repository: () -> JellyfinRepository,
) {
    private val showNames = ConcurrentHashMap<String, String>()

    private val _changes = MutableSharedFlow<JrwiProgressChange>(extraBufferCapacity = 16)

    /** Progress as it's saved from this app, so lists can update their marks. */
    val changes = _changes.asSharedFlow()

    /**
     * How far the episode was played, in the timeline of its video or, with [audio], its audio.
     * Null without that version, or when the archiver can't be reached.
     */
    suspend fun progress(item: JrwiItem, audio: Boolean): JrwiProgress? {
        val postId = (if (audio) item.audio?.postId else item.video?.postId) ?: return null
        return try {
            jrwiApi.getProgress(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not get the progress of $postId")
            null
        }
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

    /** Saves progress on the archiver and tells the lists showing [item]. */
    private suspend fun save(
        item: JrwiItem,
        postId: Long,
        position: Double,
        duration: Double?,
        finished: Boolean,
    ) {
        val saved = jrwiApi.setProgress(postId, position, duration, finished)
        _changes.tryEmit(JrwiProgressChange(item.id, saved))
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
    }
}
