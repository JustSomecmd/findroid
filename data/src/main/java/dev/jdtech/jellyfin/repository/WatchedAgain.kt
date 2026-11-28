package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Takes the watched mark off a video or episode that's played again, at the first save that falls
 * short of its end, as Findroid has always done for a download's own copy. Jellyfin and
 * TubeArchivist both keep an item watched while it's played again: Jellyfin only moves its resume
 * point (`UserDataManager.UpdatePlayState`), and TubeArchivist keeps `watched` once set
 * (`VideoProgressView._check_watched`). So the mark is taken off there before the save, which then
 * gives the item its place to carry on from. A save most of the way through marks it again, as on a
 * first watch, and playing it once more after that (a loop) takes it off again. The archive's mark
 * goes with its own saves, which say `finished` themselves.
 *
 * One for each player, following what it plays.
 */
class WatchedAgain(private val taApi: TaApi) {
    private var itemId: UUID? = null
    /** Jellyfin's mark, as the item was loaded, until it's taken off. */
    private var played = false
    private var videoId: String? = null
    /** TubeArchivist's mark is to be looked at, before the next save. */
    private var taToCheck = false

    /** Follows [itemId], which Jellyfin has as [played] or not. */
    @Synchronized
    fun start(itemId: UUID, played: Boolean) {
        this.itemId = itemId
        this.played = played
        videoId = null
        taToCheck = false
    }

    /** [itemId] is TubeArchivist's [videoId] too, as found once it plays. */
    @Synchronized
    fun taVideo(itemId: UUID, videoId: String) {
        if (this.itemId != itemId || this.videoId == videoId) return
        this.videoId = videoId
        taToCheck = true
    }

    /**
     * Before a save of [itemId] at [positionMs] of [durationMs]: takes the marks off when it falls
     * short of the end. Errors are logged; Jellyfin's is kept on the phone to send later.
     */
    suspend fun beforeSave(
        repository: JellyfinRepository,
        itemId: UUID,
        positionMs: Long,
        durationMs: Long?,
    ) {
        var jellyfin = false
        var ta: String? = null
        synchronized(this) {
            if (this.itemId != itemId) return
            if (durationMs != null && durationMs > 0 && positionMs >= durationMs * WATCHED_PART) {
                // This save marks it watched, so playing it again after takes that off again
                played = true
                taToCheck = videoId != null
                return
            }
            jellyfin = played
            played = false
            ta = videoId?.takeIf { taToCheck }
            taToCheck = false
        }
        if (jellyfin) {
            try {
                repository.markAsUnplayed(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not take the watched mark off $itemId in Jellyfin")
            }
        }
        val videoId = ta ?: return
        try {
            if (taApi.getVideo(videoId).player?.watched == true) {
                taApi.setWatched(videoId, false)
                MediaChanges.watched(videoId = videoId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not take the watched mark off $videoId in TubeArchivist")
        }
    }

    private companion object {
        /** Most of the way through counts as watched, as in Jellyfin and the players. */
        const val WATCHED_PART = 0.9
    }
}
