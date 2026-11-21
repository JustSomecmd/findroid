package dev.jdtech.jellyfin

import androidx.navigation3.runtime.NavKey
import dev.jdtech.jellyfin.navigation.navigateOrReuse
import dev.jdtech.jellyfin.navigation.truncateTo
import java.util.UUID

/** Handles navigation events (forward and back) by updating the [NavigationState]. */
class Navigator(private val state: NavigationState) {
    /**
     * Where to open an episode instead of its Jellyfin page, such as the TubeArchivist page of a
     * downloaded video. Null for the Jellyfin page.
     */
    var episodeRedirect: (UUID) -> NavKey? = { null }

    /**
     * Where Back goes from the top level route given, at its base, such as a mode's Home from its
     * other tabs. Null for the start route.
     */
    var backTo: (NavKey) -> NavKey? = { null }

    /**
     * Navigates to [route]. If [route] is a top level route, the active back stack is switched to
     * its stack (state is retained). Otherwise, [route] is pushed onto the active back stack.
     */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * Navigates back. If the active back stack is at its base (a top level route), switches to the
     * stack [backTo] gives, or the start route's.
     */
    fun goBack() {
        val currentStack =
            state.backStacks[state.topLevelRoute]
                ?: error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.last()

        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = backTo(currentRoute) ?: state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }

    /** Resets all back stacks to their roots and switches to the start route. */
    fun navigateHome() {
        state.backStacks.values.forEach { stack -> stack.truncateTo(1) }
        state.topLevelRoute = state.startRoute
    }

    /**
     * Navigates to [route], reusing an existing instance on the active back stack if present
     * (everything above it is popped). Otherwise, a new instance is pushed.
     */
    fun navigateOrReuse(route: NavKey) {
        val currentStack =
            state.backStacks[state.topLevelRoute]
                ?: error("Stack for ${state.topLevelRoute} not found")
        currentStack.navigateOrReuse(route)
    }
}
