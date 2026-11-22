package dev.jdtech.jellyfin.presentation.jrwi

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiSearchState
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiSearchViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged

/** The archive's tabs, in the site's order, with their names. */
private val SEARCH_TABS =
    listOf(
        "episodes" to CoreR.string.jrwi_tab_episodes,
        "aftershow" to CoreR.string.jrwi_tab_aftershow,
        "extras" to CoreR.string.jrwi_tab_extras,
        "art" to CoreR.string.jrwi_tab_art,
        "posts" to CoreR.string.jrwi_tab_posts,
    )

/**
 * Searches the archive's posts as the web app's feed does: by title and text, kept to a show, a tab
 * or a tag, newest or oldest first. Each post is listed on its own, with its watched mark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JrwiSearchScreen(
    onItemClick: (Long) -> Unit,
    navigateBack: () -> Unit,
    viewModel: JrwiSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (!state.active) focusRequester.requestFocus() }

    JrwiTheme {
        JrwiScaffold(
            modifier = Modifier.fillMaxSize().recalculateWindowInsetsUnlessPaged(),
            topBar = {
                TopAppBar(
                    title = {
                        TextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = {
                                Text(
                                    text = stringResource(CoreR.string.jrwi_search_hint),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = {
                                        viewModel.searchNow()
                                        focusManager.clearFocus()
                                    }
                                ),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = JrwiColors.Accent,
                                    focusedTextColor = JrwiColors.Text,
                                    unfocusedTextColor = JrwiColors.Text,
                                    focusedPlaceholderColor = JrwiColors.Muted,
                                    unfocusedPlaceholderColor = JrwiColors.Muted,
                                ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_x),
                                    contentDescription =
                                        stringResource(CoreR.string.jrwi_search_clear),
                                )
                            }
                        }
                    },
                    windowInsets = TOP_BAR_INSETS,
                    colors = jrwiTopBarColors(),
                )
            },
            contentWindowInsets = TOP_BAR_INSETS,
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                JrwiSearchFilters(state = state, viewModel = viewModel)
                JrwiSearchResults(state = state, viewModel = viewModel, onItemClick = onItemClick)
            }
        }
    }
}

/**
 * What the search is kept to, in a row under the bar as the web feed's tag picker: a show, a tab
 * and a tag, and newest or oldest first.
 */
@Composable
private fun JrwiSearchFilters(state: JrwiSearchState, viewModel: JrwiSearchViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .background(JrwiColors.Header)
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacings.medium,
                    vertical = MaterialTheme.spacings.small,
                ),
    ) {
        val showName = state.shows.firstOrNull { it.id == state.show }?.name ?: state.show
        JrwiFilterMenu(
            text = showName ?: stringResource(CoreR.string.jrwi_search_all_shows),
            selected = state.show != null,
            choices =
                listOf(null to stringResource(CoreR.string.jrwi_search_all_shows)) +
                    state.shows.map { it.id to it.name },
            onChoose = viewModel::setShow,
        )
        val tabName = SEARCH_TABS.firstOrNull { it.first == state.tab }?.second
        JrwiFilterMenu(
            text = stringResource(tabName ?: CoreR.string.jrwi_search_all_tabs),
            selected = state.tab != null,
            choices =
                listOf(null to stringResource(CoreR.string.jrwi_search_all_tabs)) +
                    SEARCH_TABS.map { (id, name) -> id to stringResource(name) },
            onChoose = viewModel::setTab,
        )
        // Only archivers that list their tags can keep posts to one
        state.tags?.let { tags ->
            JrwiFilterMenu(
                text = state.tag ?: stringResource(CoreR.string.jrwi_search_all_tags),
                selected = state.tag != null,
                icon = CoreR.drawable.ic_tag,
                choices =
                    listOf(null to stringResource(CoreR.string.jrwi_search_all_tags)) +
                        tags.map { it.name to "${it.name} (${it.count})" },
                onChoose = viewModel::setTag,
            )
        }
        JrwiFilterButton(
            text =
                stringResource(
                    if (state.order == "asc") CoreR.string.jrwi_search_oldest
                    else CoreR.string.jrwi_search_newest
                ),
            selected = false,
            icon = CoreR.drawable.ic_arrow_down_up,
            menu = false,
            onClick = viewModel::toggleOrder,
        )
        if (state.filtered) {
            JrwiFilterButton(
                text = stringResource(CoreR.string.jrwi_search_clear_filters),
                selected = false,
                icon = CoreR.drawable.ic_x,
                menu = false,
                onClick = viewModel::clearFilters,
            )
        }
    }
}

/** A filter that opens a menu of its choices, with null for none. */
@Composable
private fun JrwiFilterMenu(
    text: String,
    selected: Boolean,
    choices: List<Pair<String?, String>>,
    onChoose: (String?) -> Unit,
    @DrawableRes icon: Int? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        JrwiFilterButton(text = text, selected = selected, icon = icon, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_HEIGHT),
        ) {
            choices.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onChoose(value)
                    },
                )
            }
        }
    }
}

private val MENU_HEIGHT = 420.dp

/** A square-cornered filter button, yellow while it keeps the search to something. */
@Composable
private fun JrwiFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    menu: Boolean = true,
) {
    val content = if (selected) JrwiColors.AccentInk else JrwiColors.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.height(36.dp)
                .clip(JrwiShape)
                .background(if (selected) JrwiColors.Accent else JrwiColors.Input)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
    ) {
        icon?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = content,
            maxLines = 1,
        )
        if (menu) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_chevron_down),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun JrwiSearchResults(
    state: JrwiSearchState,
    viewModel: JrwiSearchViewModel,
    onItemClick: (Long) -> Unit,
) {
    val showNames = remember(state.shows) { state.shows.associate { it.id to it.name } }
    when {
        !state.active -> {
            JrwiSearchNote(
                text =
                    stringResource(
                        if (state.tagsUnsupported) CoreR.string.jrwi_search_intro_no_tags
                        else CoreR.string.jrwi_search_intro
                    )
            )
            return
        }
        state.searched && state.results.isEmpty() && state.error == null && !state.loading -> {
            JrwiSearchNote(text = stringResource(CoreR.string.ta_no_results))
            return
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.searched && state.results.isNotEmpty()) {
            item(key = "count") {
                Text(
                    text =
                        pluralStringResource(
                            CoreR.plurals.jrwi_item_count,
                            state.total,
                            state.total,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JrwiColors.Muted,
                )
            }
        }
        state.error?.let { error ->
            item(key = "error") { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        itemsIndexed(state.results, key = { _, post -> post.id }) { index, post ->
            LaunchedEffect(post.id) { viewModel.loadProgress(post.id) }
            if (index >= state.results.size - LOAD_AHEAD) {
                LaunchedEffect(state.results.size) { viewModel.loadMore() }
            }
            JrwiItemRow(
                item = post,
                onClick = { onItemClick(post.id) },
                otherShow = showNames[post.show],
                label = versionLabel(post),
            )
        }
        if (state.loading) {
            item(key = "loading") {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

// How many results before the end of the list the next page is asked for
private const val LOAD_AHEAD = 5

@Composable
private fun JrwiSearchNote(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacings.large),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = JrwiColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The search button in JRWI Home's bar: square, on the input grey of the side switch beside it, as
 * the archiver's square buttons.
 */
@Composable
internal fun JrwiSearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(32.dp)
                .clip(JrwiShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_search),
            contentDescription = stringResource(CoreR.string.jrwi_search),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}
