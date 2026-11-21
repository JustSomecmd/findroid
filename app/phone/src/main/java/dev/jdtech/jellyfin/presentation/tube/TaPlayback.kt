package dev.jdtech.jellyfin.presentation.tube

import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.EXTRA_SHOW_VIDEO
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.audio.AudioPlayerEntryPoint
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Plays a TubeArchivist video through its Jellyfin episode. [positionSeconds] is where to start
 * when not from the beginning; with [playlistId] the playlist's other videos play after it.
 *
 * With Settings set to stream only the sound, a TubeArchivist video ([videoId] given) plays in the
 * app's audio player instead, which opens. [showVideo] plays the picture anyway, as when it's asked
 * for from the audio player, which also starts other Jellyfin videos this way: [itemKind] says
 * whether it's an episode or a movie.
 */
fun startTaPlayback(
    context: Context,
    episodeId: UUID,
    startFromBeginning: Boolean,
    positionSeconds: Double?,
    playlistId: String? = null,
    videoId: String? = null,
    showVideo: Boolean = false,
    itemKind: BaseItemKind = BaseItemKind.EPISODE,
) {
    val app =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AudioPlayerEntryPoint::class.java,
        )
    val audio = app.audioPlayer()
    if (videoId != null && !showVideo) {
        val preferences = app.appPreferences()
        val quality = preferences.getValue(preferences.playerStreamQuality)
        if (quality == StreamQuality.AUDIO_ONLY.key) {
            audio.playVideoSound(
                itemId = episodeId,
                kind = BaseItemKind.EPISODE,
                videoId = videoId,
                positionMs = positionSeconds?.times(1000)?.toLong(),
                playlistId = playlistId,
                fromStart = startFromBeginning,
            )
            audio.requestOpen()
            return
        }
    }
    // The audio player hands the same video over, from where it got to
    var position = positionSeconds
    val audioState = audio.state.value
    if (audioState.video?.itemId == episodeId) {
        position = audioState.positionMs / 1000.0
        audio.stop()
    }
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra("itemId", episodeId.toString())
    intent.putExtra("itemKind", itemKind.serialName)
    intent.putExtra("startFromBeginning", startFromBeginning)
    if (!startFromBeginning && position != null) {
        intent.putExtra("position", (position * 1000).toLong())
    }
    playlistId?.let { intent.putExtra("taPlaylistId", it) }
    if (showVideo) intent.putExtra(EXTRA_SHOW_VIDEO, true)
    context.startActivity(intent)
}
