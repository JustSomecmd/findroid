package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A page in a section's history. What a page loads lives here, so going back to it shows it as it
 * was rather than loading it again. Its work stops when the page is left for good.
 */
class PageEntry(val route: Route) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val retained = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> retain(key: String, create: (CoroutineScope) -> T): T =
        retained.getOrPut(key) { create(scope) } as T

    fun dispose() {
        scope.cancel()
    }
}

val LocalPageEntry = staticCompositionLocalOf<PageEntry> { error("No page") }

/** Something this page keeps for as long as it's in the history, such as its loaded videos. */
@Composable
fun <T : Any> rememberPageModel(key: String, create: (CoroutineScope) -> T): T {
    val entry = LocalPageEntry.current
    return remember(entry, key) { entry.retain(key, create) }
}
