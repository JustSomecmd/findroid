package dev.jdtech.jellyfin.player.core.domain.models

/** Streaming qualities. Anything but [ORIGINAL] is converted by the Jellyfin server. */
enum class StreamQuality(
    /** Stored in the settings. */
    val key: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
) {
    ORIGINAL("original", 0, 0, 0),
    P1080("1080p", 1920, 1080, 8_000_000),
    P720("720p", 1280, 720, 4_000_000),
    P480("480p", 854, 480, 1_500_000),
    P360("360p", 640, 360, 800_000);

    /** For example "720p · 4 Mbps". */
    val label: String
        get() = "$key · ${formatBitrate(maxBitrate)}"

    companion object {
        fun fromKey(key: String?): StreamQuality = entries.firstOrNull { it.key == key } ?: ORIGINAL

        private fun formatBitrate(bitrate: Int): String =
            if (bitrate >= 1_000_000) {
                val mbps = bitrate / 1_000_000.0
                if (mbps % 1.0 == 0.0) "${mbps.toInt()} Mbps" else "$mbps Mbps"
            } else {
                "${bitrate / 1000} kbps"
            }
    }
}
