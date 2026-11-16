package dev.jdtech.jellyfin.models

/** A video converted by the server to a lower resolution, streamed over HLS. */
data class FindroidTranscodeStream(
    val url: String,
    /** Text subtitles, delivered as separate files because the converted stream drops them. */
    val subtitles: List<FindroidMediaStream>,
)
