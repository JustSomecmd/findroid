package dev.jdtech.jellyfin

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A page to open in the app, asked for from outside its pages, such as the player's "Open
 * playlist". The app opens it once it's in view.
 */
object PendingNavigation {
    private val _route = MutableStateFlow<NavKey?>(null)
    val route: StateFlow<NavKey?> = _route.asStateFlow()

    fun request(route: NavKey) {
        _route.value = route
    }

    /** Takes the page asked for, so it's opened only once. */
    fun take(): NavKey? {
        val route = _route.value
        _route.value = null
        return route
    }
}
