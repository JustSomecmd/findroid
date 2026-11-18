package dev.jdtech.jellyfin.film.presentation.tube

import dev.jdtech.jellyfin.tubearchivist.TaPage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A list from TubeArchivist that is loaded a page at a time as it is scrolled. */
data class TaListState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    /** How many there are in all, once the first page is in. */
    val total: Int? = null,
) {
    /** Nothing loaded yet and nothing found either. */
    val isEmpty: Boolean
        get() = items.isEmpty() && !hasMore && error == null
}

/**
 * Loads the next page, unless one is already loading or the last one is in. [key] keeps an item
 * that moved to the next page while scrolling from showing up twice.
 */
suspend fun <T> MutableStateFlow<TaListState<T>>.loadNextPage(
    key: (T) -> String,
    fetch: suspend (page: Int) -> TaPage<T>,
) {
    var start = false
    update { state ->
        start = false
        if (state.loading || !state.hasMore) {
            state
        } else {
            start = true
            state.copy(loading = true, error = null)
        }
    }
    if (!start) return
    try {
        val result = fetch(value.page + 1)
        update { state ->
            state.copy(
                items = (state.items + result.data).distinctBy(key),
                page = state.page + 1,
                hasMore = result.hasMore && result.data.isNotEmpty(),
                loading = false,
                total = result.paginate?.totalHits ?: state.total,
            )
        }
    } catch (e: CancellationException) {
        update { it.copy(loading = false) }
        throw e
    } catch (e: Exception) {
        update { it.copy(loading = false, error = e.errorText()) }
    }
}

fun Throwable.errorText(): String = localizedMessage ?: javaClass.simpleName
