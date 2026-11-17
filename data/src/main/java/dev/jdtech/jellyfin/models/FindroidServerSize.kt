package dev.jdtech.jellyfin.models

/** How much of a show is on the server: its episodes and the space their files take. */
data class FindroidServerSize(val episodeCount: Int, val bytes: Long)
