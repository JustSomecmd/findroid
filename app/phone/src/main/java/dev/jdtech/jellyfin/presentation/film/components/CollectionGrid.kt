package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns

@Composable
fun CollectionGrid(
    sections: List<CollectionSection>,
    innerPadding: PaddingValues,
    onAction: (CollectionAction) -> Unit,
    // Adds items above the sections
    header: LazyGridScope.() -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    /**
     * Section names as plain headings with a line under them that scroll with the list, instead of
     * cards that stay at the top.
     */
    plainHeaders: Boolean = false,
) {
    LazyVerticalGrid(
        columns = GridCellsAdaptiveWithMinColumns(minSize = 160.dp, minColumns = 2),
        state = gridState,
        modifier = Modifier.padding(innerPadding).fillMaxSize(),
        contentPadding = PaddingValues(all = MaterialTheme.spacings.default),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
    ) {
        header()
        sections.forEachIndexed { index, section ->
            if (plainHeaders) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header:$index:${section.id}") {
                    Column(
                        modifier =
                            Modifier.padding(
                                top = if (index == 0) 0.dp else MaterialTheme.spacings.medium
                            )
                    ) {
                        Text(
                            text = section.name.asString(),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(top = MaterialTheme.spacings.small)
                        )
                    }
                }
            } else
                stickyHeader {
                    Card {
                        Text(
                            text = section.name.asString(),
                            modifier =
                                Modifier.padding(
                                    horizontal = MaterialTheme.spacings.medium,
                                    vertical = MaterialTheme.spacings.medium,
                                ),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            items(items = section.items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    direction =
                        if (item is FindroidEpisode) Direction.HORIZONTAL else Direction.VERTICAL,
                    onClick = { onAction(CollectionAction.OnItemClick(item)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
