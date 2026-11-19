package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.tubearchivist.TaPage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A list TubeArchivist sends a page at a time, loading the next page when asked. */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val load: suspend (page: Int) -> TaPage<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var endReached by mutableStateOf(false)
        private set

    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        job = scope.launch {
            try {
                val next = load(page + 1)
                page += 1
                items = (items + next.data).distinctBy(key)
                endReached = !next.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    /** Starts again from the first page, keeping what's shown until it arrives. */
    fun reload() {
        job?.cancel()
        loading = false
        page = 0
        endReached = false
        val previous = items
        loading = true
        error = null
        job = scope.launch {
            try {
                val first = load(1)
                page = 1
                items = first.data.distinctBy(key)
                endReached = !first.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                items = previous
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}
