package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException

/** Lists the chapters of the playing video with their start times. Tapping one jumps to it. */
class ChapterSelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val chapters = viewModel.uiState.value.currentChapters
        val labels = chapters.mapIndexed { index, chapter ->
            val name =
                chapter.name?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.chapter_number, index + 1)
            val time = DateUtils.formatElapsedTime(chapter.startPosition / 1000)
            "$time  $name"
        }

        return activity?.let { activity ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(getString(R.string.chapters))
                .setSingleChoiceItems(
                    labels.toTypedArray(),
                    viewModel.currentChapterIndex() ?: -1,
                ) { dialog, which ->
                    viewModel.seekToChapterIndex(which)
                    dialog.dismiss()
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
