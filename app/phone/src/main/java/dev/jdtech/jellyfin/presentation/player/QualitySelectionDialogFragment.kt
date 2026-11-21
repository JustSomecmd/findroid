package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException

/**
 * Lets the user pick the original file or a lower quality converted by the server. Only the sound
 * has a button of its own, which goes on in the app's audio player; when the video plays only its
 * sound already, as Settings can start it, none is ticked.
 */
class QualitySelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val qualities = StreamQuality.entries.filter { it != StreamQuality.AUDIO_ONLY }
        val labels = qualities.map { quality ->
            when (quality) {
                StreamQuality.ORIGINAL -> getString(R.string.quality_original_summary)
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
                    viewModel.selectQuality(quality)
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
