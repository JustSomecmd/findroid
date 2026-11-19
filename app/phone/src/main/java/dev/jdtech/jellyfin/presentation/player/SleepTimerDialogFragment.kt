package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.SleepTimer

/**
 * Picks when the sleep timer stops playback: after a number of minutes, at the end of the video, or
 * not at all. Shows how long is left while one runs.
 */
class SleepTimerDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val timer = viewModel.uiState.value.sleepTimer

        // Off, the minutes, then the end of the video
        val labels =
            listOf(getString(R.string.player_sleep_off)) +
                SLEEP_MINUTES.map { minutesText(it) } +
                getString(R.string.player_sleep_end_of_video)
        val checked =
            when (timer) {
                null -> 0
                SleepTimer.EndOfVideo -> labels.lastIndex
                // A timer that's counting down matches none of the choices any more
                is SleepTimer.At -> -1
            }
        val title =
            when (timer) {
                is SleepTimer.At ->
                    getString(
                        R.string.player_sleep_timer_left,
                        DateUtils.formatElapsedTime(timer.millisLeft / 1000),
                    )
                SleepTimer.EndOfVideo -> getString(R.string.player_sleep_timer_end_title)
                null -> getString(R.string.player_sleep_timer)
            }

        return MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val message =
                    when (which) {
                        0 -> {
                            viewModel.cancelSleepTimer()
                            getString(R.string.player_sleep_cancelled)
                        }
                        labels.lastIndex -> {
                            viewModel.setSleepTimerAtEnd()
                            getString(R.string.player_sleep_set_end)
                        }
                        else -> {
                            val minutes = SLEEP_MINUTES[which - 1]
                            viewModel.setSleepTimer(minutes)
                            getString(R.string.player_sleep_set, minutesText(minutes))
                        }
                    }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .create()
    }

    private fun minutesText(minutes: Int): String =
        resources.getQuantityString(R.plurals.player_sleep_minutes, minutes, minutes)

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

private val SLEEP_MINUTES = listOf(10, 15, 30, 45, 60, 90)
