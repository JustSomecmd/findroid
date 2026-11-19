package dev.jdtech.jellyfin.settings.domain

/**
 * The player's optional buttons, which the user can put in a bar, in the settings menu behind the
 * cogwheel, or hide. Their order here is the order they start in.
 */
enum class PlayerButton(val key: String, val defaultPlacement: PlayerButtonPlacement) {
    AUTOPLAY("autoplay", PlayerButtonPlacement.TOP),
    QUALITY("quality", PlayerButtonPlacement.MENU),
    SPEED("speed", PlayerButtonPlacement.MENU),
    // A switch for captions in a bar; picking the track is in the menu too
    SUBTITLES("subtitles", PlayerButtonPlacement.TOP),
    LOCK("lock", PlayerButtonPlacement.MENU),
    SLEEP_TIMER("sleep", PlayerButtonPlacement.MENU),
    PIP("pip", PlayerButtonPlacement.MENU),
    AUDIO("audio", PlayerButtonPlacement.MENU),
    CHAPTERS("chapters", PlayerButtonPlacement.MENU),
    ROTATE("rotate", PlayerButtonPlacement.BOTTOM),
}

enum class PlayerButtonPlacement(val key: String) {
    TOP("top"),
    BOTTOM("bottom"),
    MENU("menu"),
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
