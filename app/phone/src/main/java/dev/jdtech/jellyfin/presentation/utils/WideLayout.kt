package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Whether the window is wider than it is tall and at least 600dp wide, like a phone held sideways.
 * There pictures go beside the text, or get smaller, rather than filling the whole width.
 */
@Composable
fun isWideLayout(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { size.width.toDp() }
    return size.width > size.height && width >= 600.dp
}
