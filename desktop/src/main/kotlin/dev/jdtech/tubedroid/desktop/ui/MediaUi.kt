package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import kotlinx.coroutines.launch

// The pieces pages are made of, after Jellyfin's web interface, which its desktop app shows:
// cards with slight corners and a play button on hover, rows that scroll sideways with arrows,
// item pages with a backdrop, a poster and round buttons, and a slim header with tabs

/** Cards' corners: slight, as on Jellyfin. */
val CardShape = RoundedCornerShape(6.dp)

/** The space at the sides of a page, which rows and grids line up with. */
val PagePadding = 32.dp

/** Something to do with a card or an item, for its ⋮ menu and its right-click menu. */
data class ItemAction(val label: String, val onClick: () -> Unit)

/**
 * A card as on Jellyfin: the picture, with a darker cover and a play button while the mouse is on
 * it, and the title and a second line under it, centered. Clicking it opens it; the play button
 * plays it straight away. Its actions are behind ⋮ and the right mouse button.
 */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    onPlay: (() -> Unit)? = null,
    actions: List<ItemAction> = emptyList(),
    imageShape: androidx.compose.ui.graphics.Shape = CardShape,
    image: @Composable BoxScope.() -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    ContextMenuArea(items = { actions.map { ContextMenuItem(it.label, it.onClick) } }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                modifier
                    .hoverable(hover)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClick),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(imageShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 2.dp,
                            color =
                                if (hovered) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                            shape = imageShape,
                        )
            ) {
                image()
                if (hovered && (onPlay != null || actions.isNotEmpty())) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
                    if (onPlay != null) {
                        Surface(
                            onClick = onPlay,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center).size(52.dp),
                        ) {
                            AppIcon(
                                name = "ic_play",
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                    if (actions.isNotEmpty()) {
                        MoreButton(
                            actions = actions,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                        )
                    }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacings.small, start = 4.dp, end = 4.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(Spacings.small))
        }
    }
}

/** ⋮, opening a menu of [actions]. */
@Composable
fun MoreButton(
    actions: List<ItemAction>,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 36.dp,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        RoundIconButton(
            icon = "ic_more_vertical",
            label = "More",
            onClick = { open = true },
            size = size,
            tint = tint,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/** A round button with only an icon, which says what it does when the mouse rests on it. */
@Composable
fun RoundIconButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    Tooltip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onClick),
        ) {
            AppIcon(
                name = icon,
                contentDescription = label,
                tint =
                    when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                        tint != Color.Unspecified -> tint
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.3f

/** A tooltip with [text] when the mouse rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        content = content,
    )
}

/**
 * A row of cards that scrolls sideways, under a title, as on Jellyfin's home page. The arrows at
 * the right scroll it a screen at a time; a title with › opens the whole list.
 */
@Composable
fun SectionRow(
    title: String,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun scrollBy(direction: Int) {
        val width = state.layoutInfo.viewportSize.width
        scope.launch { state.animateScrollBy(direction * width * 0.8f) }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PagePadding),
        ) {
            SectionHeading(title = title, onClick = onTitleClick)
            Spacer(Modifier.weight(1f))
            RoundIconButton(
                icon = "ic_chevron_down",
                label = "Back",
                onClick = { scrollBy(-1) },
                enabled = state.canScrollBackward,
                size = 32.dp,
                modifier = Modifier.rotate(90f),
            )
            RoundIconButton(
                icon = "ic_chevron_down",
                label = "Further",
                onClick = { scrollBy(1) },
                enabled = state.canScrollForward,
                size = 32.dp,
                modifier = Modifier.rotate(-90f),
            )
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = PagePadding),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier = Modifier.padding(top = Spacings.small),
            content = content,
        )
    }
}

/** A section's title, with › when it opens the whole list. */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        if (onClick != null) {
            AppIcon(
                name = "ic_chevron_down",
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(22.dp).rotate(-90f),
            )
        }
    }
}

/**
 * The header of a page, as on Jellyfin: back, the title, the page's tabs in the middle, its actions
 * and search at the right.
 */
@Composable
fun PageBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tabs: List<String> = emptyList(),
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    showSearch: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navigator = LocalNavigator.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = Spacings.medium)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            if (onBack != null) {
                RoundIconButton(icon = "ic_arrow_left", label = "Back", onClick = onBack)
                Spacer(Modifier.width(Spacings.small))
            }
            Text(
                text = title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (tabs.isEmpty()) 900.dp else 320.dp),
            )
        }
        if (tabs.isNotEmpty()) {
            HeaderTabs(
                tabs = tabs,
                selected = selectedTab,
                onSelect = onTabSelected,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            actions()
            if (showSearch) {
                RoundIconButton(
                    icon = "ic_search",
                    label = "Search",
                    onClick = { navigator.open(Route.Search) },
                )
            }
        }
    }
}

val HEADER_HEIGHT = 60.dp

/** Tabs as on Jellyfin: plain words, the chosen one lit and underlined. */
@Composable
fun HeaderTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { index, tab ->
            val chosen = index == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = tab,
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (chosen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier =
                        Modifier.padding(top = 6.dp)
                            .width(28.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(
                                if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                )
            }
        }
    }
}

/**
 * The top of an item's page, as on Jellyfin: the backdrop across the top fading into the page, the
 * item's picture at the left, and its name, facts, buttons and details beside it.
 */
@Composable
fun DetailHeader(
    title: String,
    info: List<String>,
    backdrop: @Composable BoxScope.() -> Unit,
    poster: @Composable () -> Unit,
    posterWidth: Dp,
    buttons: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(BACKDROP_HEIGHT)) {
            backdrop()
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to background.copy(alpha = 0.35f),
                            0.6f to background.copy(alpha = 0.75f),
                            1f to background,
                        )
                    )
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacings.large),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = PagePadding, end = PagePadding, top = BACKDROP_HEIGHT * 0.45f),
        ) {
            Box(modifier = Modifier.width(posterWidth).clip(CardShape)) { poster() }
            Column(modifier = Modifier.weight(1f).padding(top = Spacings.medium)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.isNotEmpty()) {
                    Text(
                        text = info.joinToString("     "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacings.small),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                    modifier = Modifier.padding(top = Spacings.medium).heightIn(min = 56.dp),
                    content = buttons,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacings.small),
                    modifier = Modifier.padding(top = Spacings.medium),
                    content = details,
                )
            }
        }
    }
}

private val BACKDROP_HEIGHT = 420.dp

/**
 * A round button under an item's name, as on Jellyfin's item pages. [main] is the play button,
 * filled with the accent; [on] lights it for a state that's set, such as watched.
 */
@Composable
fun DetailButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    main: Boolean = false,
    on: Boolean = false,
    enabled: Boolean = true,
) {
    Tooltip(label) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color =
                if (main) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
            modifier = modifier.size(if (main) 60.dp else 52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    name = icon,
                    contentDescription = label,
                    tint =
                        when {
                            main -> MaterialTheme.colorScheme.onPrimary
                            on -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.size(if (main) 28.dp else 22.dp),
                )
            }
        }
    }
}

/** A detail under an item's buttons, as on Jellyfin: a dim label and its value, which can open. */
@Composable
fun DetailLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (onClick != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            modifier =
                if (onClick != null) {
                    Modifier.clip(MaterialTheme.shapes.extraSmall)
                        .clickable(onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
        )
    }
}

/**
 * A tile for one of the libraries on Home, as Jellyfin's "My Media": a wide card in the accent's
 * colors with the library's icon and name.
 */
@Composable
fun LibraryTile(
    name: String,
    icon: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    MediaCard(title = name, subtitle = detail, onClick = onClick, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(primary, dark))),
        ) {
            AppIcon(
                name = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

/** The ⋮ beside an item's round buttons, in their style, opening a menu of [actions]. */
@Composable
fun DetailMoreButton(actions: List<ItemAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        DetailButton(icon = "ic_more_vertical", label = "More", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Switches a page to its side on the server, in the server's colors, as on the phone. Lit while
 * it's on.
 */
@Composable
fun ManageButton(on: Boolean, onClick: () -> Unit) {
    Tooltip(if (on) "Back to the page" else "Manage it on the TubeArchivist server") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            val content =
                if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            AppIcon(
                name = "ic_server",
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacings.small))
            Text(text = "Manage", style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/** Opens a web address in the computer's browser. */
fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
