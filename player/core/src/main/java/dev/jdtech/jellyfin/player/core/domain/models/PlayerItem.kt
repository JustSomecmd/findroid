package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerItem(
    val name: String,
    val itemId: UUID,
    val mediaSourceId: String,
    val playbackPosition: Long,
    val mediaSourceUri: String = "",
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val indexNumberEnd: Int? = null,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val chapters: List<PlayerChapter> = emptyList(),
    val trickplayInfo: TrickplayInfo? = null,
    /** Played from a download on the device, so it can't be converted to another quality. */
    val isLocal: Boolean = false,
    /** Converted by the server to a lower quality. */
    val isTranscoded: Boolean = false,
    /** Only the sound, without the picture. */
    val isAudioOnly: Boolean = false,
    /** Where it's from, shown under its name: the channel of a video, the show of an episode. */
    val subtitle: String? = null,
    /** Watched in Jellyfin when it was loaded, so playing it is watching it again. */
    val played: Boolean = false,
) : Parcelable
