package dev.jdtech.jellyfin.presentation.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiOnPhone
import dev.jdtech.jellyfin.jrwi.JrwiOnPhoneState
import dev.jdtech.jellyfin.presentation.tube.formatDuration

/** For the composables that mark what's on the phone, which aren't given a view model. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface JrwiOnPhoneEntryPoint {
    fun jrwiOnPhone(): JrwiOnPhone
}

/**
 * Which versions of JRWI's posts are on the phone, the one record every page of JRWI mode marks
 * them from, kept up to date as downloads are added, finish or are removed.
 */
@Composable
fun jrwiOnPhone(): JrwiOnPhoneState {
    if (LocalInspectionMode.current) return JrwiOnPhoneState()
    val context = LocalContext.current
    val holder =
        remember(context) {
            EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    JrwiOnPhoneEntryPoint::class.java,
                )
                .jrwiOnPhone()
        }
    val state by holder.state.collectAsStateWithLifecycle()
    return state
}

/**
 * The label in the corner of an audio or video post's picture, as every list in JRWI mode shows it:
 * a film when it has a video, headphones when it has audio, then its length. A version that's
 * downloaded to the phone is drawn in the accent yellow.
 */
@Composable
fun JrwiVersionLabel(
    video: Boolean,
    audio: Boolean,
    videoOnPhone: Boolean,
    audioOnPhone: Boolean,
    duration: Double?,
    modifier: Modifier = Modifier,
) {
    val length = duration?.takeIf { it > 0 }
    if (!video && !audio && length == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .padding(6.dp)
                .clip(JrwiShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        if (video) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_film),
                contentDescription =
                    stringResource(
                        if (videoOnPhone) CoreR.string.jrwi_video_on_phone
                        else CoreR.string.jrwi_video
                    ),
                tint = if (videoOnPhone) JrwiColors.Accent else Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        if (audio) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_headphones),
                contentDescription =
                    stringResource(
                        if (audioOnPhone) CoreR.string.jrwi_audio_on_phone
                        else CoreR.string.jrwi_audio
                    ),
                tint = if (audioOnPhone) JrwiColors.Accent else Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        length?.let {
            Text(
                text = formatDuration(it),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/**
 * [JrwiVersionLabel] for [item], with what's on the phone as [onPhone] has it. A single post, as
 * the feed has them, is marked by the item it's grouped into, which its download is kept under.
 */
@Composable
fun JrwiVersionLabel(
    item: JrwiItem,
    modifier: Modifier = Modifier,
    onPhone: JrwiOnPhoneState = jrwiOnPhone(),
) {
    val itemId = item.itemId ?: item.id
    JrwiVersionLabel(
        video = item.video != null,
        audio = item.audio != null,
        videoOnPhone = item.video != null && onPhone.videoOnPhone(itemId),
        audioOnPhone = item.audio != null && onPhone.audioOnPhone(itemId),
        duration = item.duration,
        modifier = modifier,
    )
}
