package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException

/**
 * Lets the user pick the original file, a lower quality converted by the server, or only the sound.
 */
class QualitySelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val qualities = StreamQuality.entries
        val labels = qualities.map { quality ->
            when (quality) {
                StreamQuality.ORIGINAL -> getString(R.string.quality_original_summary)
                StreamQuality.AUDIO_ONLY -> getString(R.string.quality_audio_only)
                else -> quality.label
            }
        }

        return activity?.let { activity ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(getString(R.string.select_quality))
                .setSingleChoiceItems(
                    labels.toTypedArray(),
                    qualities.indexOf(viewModel.uiState.value.currentQuality),
                ) { dialog, which ->
                    val quality = qualities[which]
                    dialog.dismiss()
                    // A TubeArchivist video's sound plays on in the app's audio player
                    val handedOver =
                        quality == StreamQuality.AUDIO_ONLY &&
                            (activity as? PlayerActivity)?.listenInAudioPlayer() == true
                    if (!handedOver) viewModel.selectQuality(quality)
                }
                .create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
