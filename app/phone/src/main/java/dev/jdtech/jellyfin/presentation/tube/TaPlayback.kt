package dev.jdtech.jellyfin.presentation.tube

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.EXTRA_JRWI_AUDIO
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.player.local.presentation.jrwiAudioMediaId
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Plays a TubeArchivist video through its Jellyfin episode. [positionSeconds] is where to start
 * when not from the beginning; with [playlistId] the playlist's other videos play after it.
 */
fun startTaPlayback(
    context: Context,
    episodeId: UUID,
    startFromBeginning: Boolean,
    positionSeconds: Double?,
    playlistId: String? = null,
) {
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra("itemId", episodeId.toString())
    intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
    intent.putExtra("startFromBeginning", startFromBeginning)
    if (!startFromBeginning && positionSeconds != null) {
        intent.putExtra("position", (positionSeconds * 1000).toLong())
    }
    playlistId?.let { intent.putExtra("taPlaylistId", it) }
    context.startActivity(intent)
}

/**
 * Plays the audio of a JRWI episode from the archiver, where it was left off in either the audio or
 * the video.
 */
fun startJrwiAudio(context: Context, postId: Long, startFromBeginning: Boolean) {
    val intent = Intent(context, PlayerActivity::class.java)
    // The player needs an ID; this one is never looked up in Jellyfin
    intent.putExtra("itemId", jrwiAudioMediaId(postId).toString())
    intent.putExtra("itemKind", "")
    intent.putExtra("startFromBeginning", startFromBeginning)
    intent.putExtra(EXTRA_JRWI_AUDIO, postId)
    context.startActivity(intent)
}
