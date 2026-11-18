package dev.jdtech.jellyfin.presentation.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/** The color each accent preset is made from, by its value in the settings. */
private val accentSeeds =
    mapOf(
        "tubearchivist" to Color(0xFF259485),
        "jellyfin_blue" to Color(0xFF00A4DC),
        "jellyfin_purple" to Color(0xFFAA5CC3),
        "red" to Color(0xFFE53935),
        "orange" to Color(0xFFF4511E),
        "amber" to Color(0xFFFFB300),
        "green" to Color(0xFF43A047),
        "pink" to Color(0xFFD81B60),
    )

/**
 * The color scheme with its accent colors (buttons, highlights, the selected tab) made from a
 * preset, keeping the backgrounds and text as they are. The app's own colors for "default".
 */
fun ColorScheme.withAccent(accent: String, dark: Boolean): ColorScheme {
    val seed = accentSeeds[accent] ?: return this
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    val saturation = hsl[1]

    // A color of the same hue at a given lightness, from 0 to 1
    fun tone(lightness: Float, saturationScale: Float = 1f): Color =
        Color(
            ColorUtils.HSLToColor(
                floatArrayOf(hue, (saturation * saturationScale).coerceIn(0f, 1f), lightness)
            )
        )

    return if (dark) {
        copy(
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
    } else {
        copy(
            primary = tone(0.36f),
            onPrimary = Color.White,
            primaryContainer = tone(0.88f, 0.8f),
            onPrimaryContainer = tone(0.12f),
            inversePrimary = tone(0.75f, 0.75f),
            secondary = tone(0.38f, 0.3f),
            onSecondary = Color.White,
            secondaryContainer = tone(0.88f, 0.35f),
            onSecondaryContainer = tone(0.12f, 0.4f),
            surfaceTint = tone(0.36f),
        )
    }
}

/** The app's dark colors for screens drawn without Compose, such as the player's controls. */
fun darkAppColorScheme(context: Context, dynamicColor: Boolean, accent: String): ColorScheme {
    val base =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context)
        } else {
            darkScheme
        }
    return base.withAccent(accent, dark = true)
}
