package dev.jdtech.jellyfin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
 * bar: a track with Tubedroid's head on the left and JRWI's dice on the right, and a knob on the
 * mode that's open. The bar's item flips it with a tap; a swipe along it to the other end flips it
 * too. In JRWI mode it's in the archiver's purple.
 */
@Composable
fun ModeSwitch(jrwi: Boolean, onSwitch: (jrwi: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val knobAt by animateDpAsState(if (jrwi) TRACK_WIDTH - TRACK_HEIGHT else 0.dp, label = "knob")
    val track by
        animateColorAsState(
            if (jrwi) JrwiPurple.Card else MaterialTheme.colorScheme.surfaceContainerHighest,
            label = "track",
        )
    val knob by
        animateColorAsState(
            if (jrwi) JrwiPurple.Accent else MaterialTheme.colorScheme.primary,
            label = "knob colour",
        )
    val onKnob = if (jrwi) JrwiPurple.Ink else MaterialTheme.colorScheme.onPrimary
    val offKnob = if (jrwi) JrwiPurple.Muted else MaterialTheme.colorScheme.onSurfaceVariant
    val current by rememberUpdatedState(jrwi)
    val switch by rememberUpdatedState(onSwitch)
    val swipe = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    val description =
        stringResource(
            if (jrwi) CoreR.string.mode_switch_to_ta else CoreR.string.mode_switch_to_jrwi
        )
    Box(
        modifier =
            modifier
                .size(TRACK_WIDTH, TRACK_HEIGHT)
                .clip(CircleShape)
                .background(track)
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
        Box(
            Modifier.offset(x = knobAt)
                .padding(KNOB_INSET)
                .size(TRACK_HEIGHT - KNOB_INSET * 2)
                .clip(CircleShape)
                .background(knob)
        )
        Icon(
            painter = painterResource(CoreR.drawable.ic_tubedroid_head),
            contentDescription = null,
            tint = if (jrwi) offKnob else onKnob,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = ICON_INSET).size(ICON),
        )
        Icon(
            painter = painterResource(CoreR.drawable.ic_dice),
            contentDescription = null,
            tint = if (jrwi) onKnob else offKnob,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = ICON_INSET).size(ICON),
        )
    }
}

private val TRACK_WIDTH = 56.dp
private val TRACK_HEIGHT = 30.dp
private val KNOB_INSET = 3.dp
private val ICON = 16.dp
// Puts each icon in the middle of where the knob sits
private val ICON_INSET = (TRACK_HEIGHT - ICON) / 2
// How far a swipe along the switch has to go to flip it
private val SWIPE_DISTANCE = 12.dp
