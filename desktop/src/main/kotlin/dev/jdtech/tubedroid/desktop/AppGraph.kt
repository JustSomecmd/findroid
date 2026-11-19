package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.tubedroid.desktop.player.PlayerRequest

/** What the pages share: the settings, TubeArchivist and the downloads to this computer. */
class AppGraph(val settings: AppSettings) {
    val api = TaApi(settings)
    val downloads = Downloads(settings, api)
}

val LocalApp = staticCompositionLocalOf<AppGraph> { error("No app") }

/** The ways pages move around the app, given to each page by the shell. */
interface Navigator {
    fun open(route: Route)

    fun back()

    fun play(request: PlayerRequest)

    /** Shows a short message at the bottom of the window. */
    fun message(text: String)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
