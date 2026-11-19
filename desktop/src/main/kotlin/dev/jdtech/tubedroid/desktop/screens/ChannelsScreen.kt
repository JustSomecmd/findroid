package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.ChannelItem
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class ChannelsTab(val label: String) {
    SUBSCRIBED("Subscribed"),
    ALL("All channels"),
}

class ChannelsModel(private val api: TaApi, private val scope: CoroutineScope) {
    var tab by mutableStateOf(ChannelsTab.SUBSCRIBED)

    val subscribed =
        PagedList(scope, key = TaChannel::id) { page ->
            api.getChannels(page = page, subscribedOnly = true)
        }
    val all = PagedList(scope, key = TaChannel::id) { page -> api.getChannels(page = page) }

    val grids = ChannelsTab.entries.associateWith { LazyGridState() }

    fun refresh() {
        when (tab) {
            ChannelsTab.SUBSCRIBED -> subscribed.reload()
            ChannelsTab.ALL -> all.reload()
        }
    }

    /** Subscribes to channels by their links. */
    fun subscribe(links: List<String>, onDone: (String) -> Unit) {
        scope.launch {
            val message =
                try {
                    api.subscribeToChannels(links)
                    "TubeArchivist is subscribing to the channels"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorText(e)
                }
            onDone(message)
        }
    }
}

@Composable
fun ChannelsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("channels") { scope -> ChannelsModel(app.api, scope) }
    var subscribing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Channels", onBack = null) {
            FilledTonalButton(onClick = { subscribing = true }) { Text("Subscribe") }
            RefreshButton(onClick = model::refresh)
        }
        TintedTabRow(selected = model.tab.ordinal) {
            ChannelsTab.entries.forEach { tab ->
                Tab(
                    selected = model.tab == tab,
                    onClick = { model.tab = tab },
                    text = { Text(tab.label) },
                )
            }
        }
        when (model.tab) {
            ChannelsTab.SUBSCRIBED ->
                ChannelGrid(
                    list = model.subscribed,
                    state = model.grids.getValue(ChannelsTab.SUBSCRIBED),
                    emptyText = "No subscriptions yet",
                    onClick = { navigator.open(Route.Channel(it)) },
                )
            ChannelsTab.ALL ->
                ChannelGrid(
                    list = model.all,
                    state = model.grids.getValue(ChannelsTab.ALL),
                    emptyText = "No channels yet",
                    onClick = { navigator.open(Route.Channel(it)) },
                )
        }
    }

    if (subscribing) {
        TextInputDialog(
            title = "Subscribe to channels",
            label = "Links, one per line",
            confirmLabel = "Subscribe",
            multiLine = true,
            onConfirm = { text ->
                val links = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (links.isNotEmpty()) model.subscribe(links, navigator::message)
            },
            onDismiss = { subscribing = false },
        )
    }
}

/** A tab row in the same tint of the accent as the phone app's. */
@Composable
fun TintedTabRow(selected: Int, tabs: @Composable () -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = selected,
        containerColor =
            lerp(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.primary,
                0.1f,
            ),
        tabs = tabs,
    )
}

@Composable
private fun ChannelGrid(
    list: PagedList<TaChannel>,
    state: LazyGridState,
    emptyText: String,
    onClick: (String) -> Unit,
) {
    LoadMoreEffect(state) { list.loadMore() }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(Spacings.default),
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            items(list.items, key = { it.id }) { channel ->
                ChannelItem(channel = channel, onClick = { onClick(channel.id) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { ListFooter(list, emptyText = emptyText) }
        }
        GridScrollbar(state)
    }
}

/** Asks for some text, such as links to subscribe to or a playlist's name. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    multiLine: Boolean = false,
    initial: String = "",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = !multiLine,
                    minLines = if (multiLine) 4 else 1,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = if (multiLine) 120.dp else 0.dp),
                )
                Spacer(Modifier.height(Spacings.small))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(text)
                    onDismiss()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
