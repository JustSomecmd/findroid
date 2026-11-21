package dev.jdtech.jellyfin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.jrwi.JrwiPurple

/**
 * The switch between the app's TubeArchivist mode and its JRWI mode, at the start of the bottom
 * bar. Bigger than the page switches, and unlike them: its track is split in the two modes'
 * colours, the app's on the left and JRWI's purple on the right, and a wide knob in the open mode's
 * colour sits on that mode's half, carrying its icon (Tubedroid's head or JRWI's dice). The other
 * mode's icon shows faintly in its own half. The bar's item flips it with a tap; a swipe along it
 * to the other end flips it too.
 */
@Composable
fun ModeSwitch(jrwi: Boolean, onSwitch: (jrwi: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val taColour = MaterialTheme.colorScheme.primary
    val jrwiColour = JrwiPurple.Accent
    val knobAt by animateDpAsState(if (jrwi) KNOB_TRAVEL else 0.dp, label = "knob")
    val knob by animateColorAsState(if (jrwi) jrwiColour else taColour, label = "knob colour")
    val onKnob = if (jrwi) JrwiPurple.Ink else MaterialTheme.colorScheme.onPrimary
    val current by rememberUpdatedState(jrwi)
    val switch by rememberUpdatedState(onSwitch)
    val swipe = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    val description =
        stringResource(
            if (jrwi) CoreR.string.mode_switch_to_ta else CoreR.string.mode_switch_to_jrwi
        )
    // Each mode's half of the track, in its colour, dimmed on the bar
    val track =
        Brush.horizontalGradient(
            0f to taColour.copy(alpha = TRACK_ALPHA),
            0.5f to taColour.copy(alpha = TRACK_ALPHA),
            0.5f to jrwiColour.copy(alpha = TRACK_ALPHA),
            1f to jrwiColour.copy(alpha = TRACK_ALPHA),
        )
    Box(
        modifier =
            modifier
                .size(TRACK_WIDTH, TRACK_HEIGHT)
                .clip(CircleShape)
                .background(track)
                .border(1.dp, Color.White.copy(alpha = BORDER_ALPHA), CircleShape)
                .semantics {
                    contentDescription = description
                    role = Role.Switch
                }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            // Towards the end it isn't at
                            if (dragged > swipe && !current) switch(true)
                            else if (dragged < -swipe && current) switch(false)
                        },
                    ) { change, amount ->
                        dragged += amount
                        change.consume()
                    }
                }
    ) {
        // The other mode's icon, faintly in its own colour, in the half the knob isn't on
        Icon(
            painter =
                painterResource(
                    if (jrwi) CoreR.drawable.ic_tubedroid_head else CoreR.drawable.ic_dice
                ),
            contentDescription = null,
            tint = (if (jrwi) taColour else jrwiColour).copy(alpha = OTHER_ICON_ALPHA),
            modifier =
                Modifier.align(if (jrwi) Alignment.CenterStart else Alignment.CenterEnd)
                    .offset(x = if (jrwi) OTHER_ICON_INSET else -OTHER_ICON_INSET)
                    .size(OTHER_ICON),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .offset(x = KNOB_INSET + knobAt)
                    .size(KNOB_WIDTH, KNOB_HEIGHT)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(knob),
        ) {
            Icon(
                painter =
                    painterResource(
                        if (jrwi) CoreR.drawable.ic_dice else CoreR.drawable.ic_tubedroid_head
                    ),
                contentDescription = null,
                tint = onKnob,
                modifier = Modifier.size(KNOB_ICON),
            )
        }
    }
}

private val TRACK_WIDTH = 72.dp
private val TRACK_HEIGHT = 40.dp
private val KNOB_INSET = 4.dp
private val KNOB_WIDTH = 40.dp
private val KNOB_HEIGHT = TRACK_HEIGHT - KNOB_INSET * 2
private val KNOB_TRAVEL = TRACK_WIDTH - KNOB_WIDTH - KNOB_INSET * 2
private val KNOB_ICON = 22.dp
private val OTHER_ICON = 20.dp
// Puts the other icon in the middle of the part of the track the knob leaves free
private val OTHER_ICON_INSET = (TRACK_WIDTH - KNOB_WIDTH - KNOB_INSET - OTHER_ICON) / 2
private const val TRACK_ALPHA = 0.22f
private const val BORDER_ALPHA = 0.12f
private const val OTHER_ICON_ALPHA = 0.85f
// How far a swipe along the switch has to go to flip it
private val SWIPE_DISTANCE = 12.dp
