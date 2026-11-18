package dev.jdtech.jellyfin.settings.domain

/** The player's optional buttons, which the user can move between bars or hide. */
enum class PlayerButton(val key: String, val defaultPlacement: PlayerButtonPlacement) {
    AUTOPLAY("autoplay", PlayerButtonPlacement.TOP),
    LOCK("lock", PlayerButtonPlacement.TOP),
    PIP("pip", PlayerButtonPlacement.TOP),
    CHAPTERS("chapters", PlayerButtonPlacement.TOP),
    QUALITY("quality", PlayerButtonPlacement.TOP),
    SPEED("speed", PlayerButtonPlacement.TOP),
    AUDIO("audio", PlayerButtonPlacement.TOP),
    SUBTITLES("subtitles", PlayerButtonPlacement.TOP),
    ROTATE("rotate", PlayerButtonPlacement.BOTTOM),
}

enum class PlayerButtonPlacement(val key: String) {
    TOP("top"),
    BOTTOM("bottom"),
    HIDDEN("hidden"),
}

data class PlayerButtonConfig(val button: PlayerButton, val placement: PlayerButtonPlacement)

/**
 * Reads and writes the button layout, stored as "lock:top,pip:top,...". The order of the list is
 * the order of the buttons within each bar.
 */
object PlayerButtonLayout {
    val default: List<PlayerButtonConfig> =
        PlayerButton.entries.map { PlayerButtonConfig(it, it.defaultPlacement) }

    fun parse(value: String?): List<PlayerButtonConfig> {
        if (value.isNullOrBlank()) return default

        val parsed =
            value
                .split(",")
                .mapNotNull { part ->
                    val pieces = part.trim().split(":")
                    if (pieces.size != 2) return@mapNotNull null
                    val button =
                        PlayerButton.entries.firstOrNull { it.key == pieces[0] }
                            ?: return@mapNotNull null
                    val placement =
                        PlayerButtonPlacement.entries.firstOrNull { it.key == pieces[1] }
                            ?: button.defaultPlacement
                    PlayerButtonConfig(button, placement)
                }
                .distinctBy { it.button }

        // Buttons added in a later version show up in their default place
        val missing = default.filter { config -> parsed.none { it.button == config.button } }
        return parsed + missing
    }

    fun serialize(configs: List<PlayerButtonConfig>): String =
        configs.joinToString(",") { "${it.button.key}:${it.placement.key}" }
}
