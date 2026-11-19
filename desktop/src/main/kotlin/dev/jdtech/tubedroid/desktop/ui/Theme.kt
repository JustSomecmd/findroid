package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The same spacing steps as the phone app. */
object Spacings {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val default = 24.dp
    val large = 32.dp
}

private val appShapes =
    Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(10.dp))

// The phone app's dark colors
private val darkScheme =
    darkColorScheme(
        primary = Color(0xFFA4C9FE),
        onPrimary = Color(0xFF00315C),
        primaryContainer = Color(0xFF1F4876),
        onPrimaryContainer = Color(0xFFD3E3FF),
        secondary = Color(0xFFBCC7DB),
        onSecondary = Color(0xFF263141),
        secondaryContainer = Color(0xFF3C4758),
        onSecondaryContainer = Color(0xFFD8E3F8),
        tertiary = Color(0xFFD9BDE3),
        onTertiary = Color(0xFF3C2947),
        tertiaryContainer = Color(0xFF543F5E),
        onTertiaryContainer = Color(0xFFF5D9FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        outline = Color(0xFF8D9199),
        outlineVariant = Color(0xFF43474E),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF2E3035),
        inversePrimary = Color(0xFF3A608F),
        surfaceDim = Color(0xFF111318),
        surfaceBright = Color(0xFF37393E),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF272A2F),
        surfaceContainerHighest = Color(0xFF32353A),
    )

/** The color each accent preset is made from, by the same names as the phone app. */
val accentSeeds =
    linkedMapOf(
        "tubearchivist" to Color(0xFF259485),
        "jellyfin_blue" to Color(0xFF00A4DC),
        "jellyfin_purple" to Color(0xFFAA5CC3),
        "red" to Color(0xFFE53935),
        "orange" to Color(0xFFF4511E),
        "amber" to Color(0xFFFFB300),
        "green" to Color(0xFF43A047),
        "pink" to Color(0xFFD81B60),
    )

@Composable
fun TubedroidTheme(accent: String, content: @Composable () -> Unit) {
    val scheme = remember(accent) { darkScheme.withAccent(accent) }
    MaterialTheme(colorScheme = scheme, shapes = appShapes, content = content)
}

/** The dark scheme with its accent colors made from a preset, as the phone app does. */
private fun ColorScheme.withAccent(accent: String): ColorScheme {
    val seed = accentSeeds[accent] ?: return this
    val (hue, saturation, _) = seed.toHsl()

    fun tone(lightness: Float, saturationScale: Float = 1f): Color =
        hslColor(hue, (saturation * saturationScale).coerceIn(0f, 1f), lightness)

    return copy(
        primary = tone(0.72f, 0.75f),
        onPrimary = tone(0.14f),
        primaryContainer = tone(0.27f, 0.8f),
        onPrimaryContainer = tone(0.88f, 0.8f),
        inversePrimary = tone(0.38f),
        secondary = tone(0.75f, 0.3f),
        onSecondary = tone(0.16f, 0.3f),
        secondaryContainer = tone(0.26f, 0.35f),
        onSecondaryContainer = tone(0.88f, 0.4f),
        surfaceTint = tone(0.72f, 0.75f),
    )
}

/**
 * Shows the content in the server colors when [onServer] is true, fading between the two. The
 * server colors swap the main and accent colors: the backgrounds take the accent's hue and the
 * buttons and highlights take the plain text color.
 */
@Composable
fun ServerSideTheme(onServer: Boolean, content: @Composable () -> Unit) {
    val fraction by
        animateFloatAsState(
            targetValue = if (onServer) 1f else 0f,
            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
            label = "server colors",
        )
    val base = MaterialTheme.colorScheme
    val server = remember(base) { base.serverSide() }
    val scheme =
        when (fraction) {
            0f -> base
            1f -> server
            else -> base.blendTo(server, fraction)
        }
    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** The server colors made from this scheme's accent, as on the phone. */
private fun ColorScheme.serverSide(): ColorScheme {
    val (hue, rawSaturation, _) = primary.toHsl()
    // Keeps a visible tint when the accent is close to grey
    val saturation = rawSaturation.coerceAtLeast(0.35f)

    fun tone(lightness: Float, saturationScale: Float = 0.6f): Color =
        hslColor(hue, (saturation * saturationScale).coerceIn(0f, 1f), lightness)

    val text = onSurface
    val background = tone(0.14f)
    return copy(
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
}

/** The colors a fraction of the way from this scheme to [other], for the colors that change. */
private fun ColorScheme.blendTo(other: ColorScheme, fraction: Float): ColorScheme =
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

/** Hue (0 to 360), saturation and lightness (0 to 1). */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return Triple(0f, 0f, lightness)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue =
        when (max) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
    return Triple((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - chroma / 2f
    val (r, g, b) =
        when {
            hue < 60f -> Triple(chroma, x, 0f)
            hue < 120f -> Triple(x, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, x)
            hue < 240f -> Triple(0f, x, chroma)
            hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
    return Color(
        (r + m).coerceIn(0f, 1f),
        (g + m).coerceIn(0f, 1f),
        (b + m).coerceIn(0f, 1f),
    )
}
