package dev.jdtech.jellyfin.presentation.tube

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.PlayerActivity
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
