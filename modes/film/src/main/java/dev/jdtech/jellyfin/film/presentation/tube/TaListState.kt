package dev.jdtech.jellyfin.film.presentation.tube

import dev.jdtech.jellyfin.tubearchivist.TaPage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

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

/**
 * Loads the pages shown so far again and swaps them in all at once, so changed items update in
 * place without the list emptying or showing as loading. Keeps the list as it is if that fails.
 */
suspend fun <T> MutableStateFlow<TaListState<T>>.reloadInPlace(
    key: (T) -> String,
    isCurrent: () -> Boolean = { true },
    fetch: suspend (page: Int) -> TaPage<T>,
) {
    // A page that is loading now would be overwritten, so that one finishes first
    if (value.loading) return
    val pages = value.page.coerceAtLeast(1)
    val items = mutableListOf<T>()
    var last: TaPage<T>? = null
    var loaded = 0
    try {
        for (page in 1..pages) {
            val result = fetch(page)
            items += result.data
            last = result
            loaded = page
            if (!result.hasMore || result.data.isEmpty()) break
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Could not reload the list")
        return
    }
    val result = last ?: return
    // The list may have been started over for something else in the meantime
    if (!isCurrent()) return
    update { state ->
        state.copy(
            items = items.distinctBy(key),
            page = loaded,
            hasMore = result.hasMore && result.data.isNotEmpty(),
            error = null,
            total = result.paginate?.totalHits ?: state.total,
        )
    }
}

fun Throwable.errorText(): String = localizedMessage ?: javaClass.simpleName
