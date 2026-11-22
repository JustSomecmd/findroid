package dev.jdtech.jellyfin.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape

/**
 * The shape of pills, round buttons, badges and switches: fully round in the app's own look, and
 * with small corners on the JRWI pages, which follow the archiver's square-cornered site.
 */
val LocalPillShape = staticCompositionLocalOf<Shape> { CircleShape }
