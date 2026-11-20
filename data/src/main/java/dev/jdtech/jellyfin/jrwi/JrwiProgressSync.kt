package dev.jdtech.jellyfin.jrwi

import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Keeps the video and the audio of a JRWI episode on one position and one watched state, the way
 * the app keeps TubeArchivist and Jellyfin in step. The video's progress lives in Jellyfin (or in
 * TubeArchivist, for videos only on YouTube) and the audio's on the archiver; whatever is played
 * hands its progress to the other, moved by the offset between the two.
 */
class JrwiProgressSync(
    private val jrwiApi: JrwiApi,
    private val lookup: JrwiJellyfinLookup,
    private val taApi: TaApi,
    private val repository: () -> JellyfinRepository,
) {
    private val showNames = ConcurrentHashMap<String, String>()

    /** How far the audio was listened to, which the video shares; null without audio. */
    suspend fun progress(item: JrwiItem): JrwiProgress? {
        val audio = item.audio ?: return null
        return try {
            jrwiApi.getProgress(audio.postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not get the progress of ${audio.postId}")
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

    /** The video was played to [videoSeconds]: the audio is moved to the same moment. */
    suspend fun videoPlayed(item: JrwiItem, videoSeconds: Double, finished: Boolean) {
        val audio = item.audio ?: return
        val position = if (finished) 0.0 else item.audioSeconds(videoSeconds)
        jrwiApi.setProgress(audio.postId, position, audio.duration, finished)
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
    ) {
        val audio = item.audio ?: return
        jrwiApi.setProgress(
            audio.postId,
            if (finished) 0.0 else audioSeconds,
            audio.duration,
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
        item.audio?.let { jrwiApi.setProgress(it.postId, 0.0, it.duration, watched) }
        val video = item.video ?: return
        if (video.isYoutube) {
            val youtubeId = video.youtubeId ?: return
            if (taApi.isConfigured) taApi.setWatched(youtubeId, watched)
            return
        }
        val episodeId = episodeId(item) ?: return
        if (watched) repository().markAsPlayed(episodeId)
        else repository().markAsUnplayed(episodeId)
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
