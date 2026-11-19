package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.tubearchivist.TaConfig
import java.io.File
import java.util.prefs.Preferences

/**
 * The app's settings, kept in the user's registry by Java's preferences. Each value is also Compose
 * state, so pages follow a change right away.
 */
class AppSettings(private val prefs: Preferences = Preferences.userRoot().node("tubedroid")) :
    TaConfig {

    override var url: String? by stored<String?>("ta_url", null)
    override var token: String? by stored<String?>("ta_token", null)

    /** Where the download button saves videos. The user's Downloads folder unless changed. */
    var downloadFolder: String by
        stored<String>("download_folder", File(System.getProperty("user.home"), "Downloads").path)

    /** Whether the next video of a playlist plays when one ends. */
    var autoplay: Boolean by storedBoolean("autoplay", true)

    /** What to do at a SponsorBlock segment: button, auto or off. */
    var sponsorBlock: String by stored<String>("sponsorblock", SPONSORBLOCK_BUTTON)

    /** mpv's hardware decoding, as its hwdec option. */
    var hardwareDecoding: String by stored<String>("hwdec", "auto-safe")

    /** The accent preset, by the same names as the phone app. */
    var accent: String by stored<String>("accent", "tubearchivist")

    val isConfigured: Boolean
        get() = !url.isNullOrBlank() && !token.isNullOrBlank()

    /** Where mpv.conf, input.conf and mpv scripts can be put, like Jellyfin Media Player has. */
    val mpvConfigFolder: File
        get() {
            val base =
                System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"))
            return File(base, "Tubedroid/mpv").also { it.mkdirs() }
        }

    private fun <T : String?> stored(key: String, default: T) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            private val state = mutableStateOf(read())

            @Suppress("UNCHECKED_CAST") private fun read(): T = prefs.get(key, default) as T

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T =
                state.value

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: T,
            ) {
                if (value == null) prefs.remove(key) else prefs.put(key, value)
                state.value = value
            }
        }

    private fun storedBoolean(key: String, default: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            private var state by mutableStateOf(prefs.getBoolean(key, default))

            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean =
                state

            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: Boolean,
            ) {
                prefs.putBoolean(key, value)
                state = value
            }
        }

    companion object {
        const val SPONSORBLOCK_BUTTON = "button"
        const val SPONSORBLOCK_AUTO = "auto"
        const val SPONSORBLOCK_OFF = "off"
    }
}
