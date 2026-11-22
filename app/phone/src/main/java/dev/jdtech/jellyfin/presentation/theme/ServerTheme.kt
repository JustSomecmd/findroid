package dev.jdtech.jellyfin.presentation.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Shows the content in the server colors when [onServer] is true, fading between the two. The
 * server colors swap the main and accent colors: the backgrounds take the accent's hue and the
 * buttons and highlights take the plain text color.
 */
@Composable
fun ServerSideTheme(onServer: Boolean, content: @Composable () -> Unit) {
    val progress by
        animateFloatAsState(
            targetValue = if (onServer) 1f else 0f,
            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
            label = "server colors",
        )
    ServerSideColors(step = colorStep(progress), content = content)
}

/**
 * Follows a pager with the phone side as its first page and the server side as its second, or the
 * other way round with [serverFirst], so the colors move along with a swipe.
 */
@Composable
fun ServerSideTheme(
    pagerState: PagerState,
    serverFirst: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Read through derived state, so only a new step of the colors redraws anything, rather than
    // every frame of the swipe
    val step by
        remember(pagerState, serverFirst) {
            derivedStateOf {
                val position =
                    (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                colorStep(if (serverFirst) 1f - position else position)
            }
        }
    ServerSideColors(step = step, content = content)
}

// The colors change in this many steps between the two sides. New colors redraw the whole page,
// which on a busy page such as Home takes longer than a frame, so a swipe that changed them on
// every frame would stutter and stick
private const val COLOR_STEPS = 10

private fun colorStep(fraction: Float): Int = (fraction * COLOR_STEPS).roundToInt()

@Composable
private fun ServerSideColors(step: Int, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val server = remember(base, dark) { base.serverSide(dark) }
    // The same colors for the same step, so nothing below draws again until the step changes
    val scheme =
        remember(base, server, step) {
            when (step) {
                0 -> base
                COLOR_STEPS -> server
                else -> base.blendTo(server, step / COLOR_STEPS.toFloat())
            }
        }
    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** The server colors made from this scheme's accent. */
fun ColorScheme.serverSide(dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(primary.toArgb(), hsl)
    val hue = hsl[0]
    // Keeps a visible tint when the accent is close to grey, such as with some wallpapers
    val saturation = hsl[1].coerceAtLeast(0.35f)

    fun tone(lightness: Float, saturationScale: Float = 0.6f): Color =
        Color(
            ColorUtils.HSLToColor(
                floatArrayOf(hue, (saturation * saturationScale).coerceIn(0f, 1f), lightness)
            )
        )

    val text = onSurface
    return if (dark) {
        val background = tone(0.14f)
        copy(
            primary = text,
            onPrimary = background,
            primaryContainer = tone(0.86f, 0.2f),
            onPrimaryContainer = background,
            inversePrimary = primary,
            secondary = tone(0.82f, 0.25f),
            onSecondary = background,
            secondaryContainer = tone(0.30f),
            onSecondaryContainer = text,
            background = background,
            surface = background,
            surfaceVariant = tone(0.26f),
            onSurfaceVariant = tone(0.80f, 0.3f),
            surfaceTint = text,
            inverseSurface = text,
            inverseOnSurface = background,
            outline = tone(0.55f, 0.3f),
            outlineVariant = tone(0.30f, 0.5f),
            surfaceBright = tone(0.28f),
            surfaceDim = tone(0.12f),
            surfaceContainerLowest = tone(0.10f),
            surfaceContainerLow = tone(0.16f),
            surfaceContainer = tone(0.18f),
            surfaceContainerHigh = tone(0.22f),
            surfaceContainerHighest = tone(0.26f),
        )
    } else {
        val background = tone(0.90f)
        copy(
            primary = text,
            onPrimary = background,
            primaryContainer = tone(0.22f, 0.2f),
            onPrimaryContainer = background,
            inversePrimary = primary,
            secondary = tone(0.30f, 0.25f),
            onSecondary = background,
            secondaryContainer = tone(0.78f),
            onSecondaryContainer = text,
            background = background,
            surface = background,
            surfaceVariant = tone(0.82f),
            onSurfaceVariant = tone(0.28f, 0.3f),
            surfaceTint = text,
            inverseSurface = text,
            inverseOnSurface = background,
            outline = tone(0.45f, 0.3f),
            outlineVariant = tone(0.74f, 0.5f),
            surfaceBright = tone(0.95f),
            surfaceDim = tone(0.82f),
            surfaceContainerLowest = tone(0.96f),
            surfaceContainerLow = tone(0.92f),
            surfaceContainer = tone(0.88f),
            surfaceContainerHigh = tone(0.85f),
            surfaceContainerHighest = tone(0.82f),
        )
    }
}

/** The colors a fraction of the way from this scheme to [other], for the colors that change. */
internal fun ColorScheme.blendTo(other: ColorScheme, fraction: Float): ColorScheme =
    copy(
        primary = lerp(primary, other.primary, fraction),
        onPrimary = lerp(onPrimary, other.onPrimary, fraction),
        primaryContainer = lerp(primaryContainer, other.primaryContainer, fraction),
        onPrimaryContainer = lerp(onPrimaryContainer, other.onPrimaryContainer, fraction),
        inversePrimary = lerp(inversePrimary, other.inversePrimary, fraction),
        secondary = lerp(secondary, other.secondary, fraction),
        onSecondary = lerp(onSecondary, other.onSecondary, fraction),
        secondaryContainer = lerp(secondaryContainer, other.secondaryContainer, fraction),
        onSecondaryContainer = lerp(onSecondaryContainer, other.onSecondaryContainer, fraction),
        background = lerp(background, other.background, fraction),
        surface = lerp(surface, other.surface, fraction),
        surfaceVariant = lerp(surfaceVariant, other.surfaceVariant, fraction),
        onSurfaceVariant = lerp(onSurfaceVariant, other.onSurfaceVariant, fraction),
        surfaceTint = lerp(surfaceTint, other.surfaceTint, fraction),
        inverseSurface = lerp(inverseSurface, other.inverseSurface, fraction),
        inverseOnSurface = lerp(inverseOnSurface, other.inverseOnSurface, fraction),
        outline = lerp(outline, other.outline, fraction),
        outlineVariant = lerp(outlineVariant, other.outlineVariant, fraction),
        surfaceBright = lerp(surfaceBright, other.surfaceBright, fraction),
        surfaceDim = lerp(surfaceDim, other.surfaceDim, fraction),
        surfaceContainerLowest =
            lerp(surfaceContainerLowest, other.surfaceContainerLowest, fraction),
        surfaceContainerLow = lerp(surfaceContainerLow, other.surfaceContainerLow, fraction),
        surfaceContainer = lerp(surfaceContainer, other.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(surfaceContainerHigh, other.surfaceContainerHigh, fraction),
        surfaceContainerHighest =
            lerp(surfaceContainerHighest, other.surfaceContainerHighest, fraction),
    )
