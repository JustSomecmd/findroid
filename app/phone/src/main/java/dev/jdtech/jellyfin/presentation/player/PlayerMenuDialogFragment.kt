package dev.jdtech.jellyfin.presentation.player

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.color.MaterialColors
import dev.jdtech.jellyfin.core.R as CoreR

/** A line of the player's settings menu. */
data class PlayerMenuItem(
    @param:DrawableRes val icon: Int,
    val title: String,
    /** What it's set to now, such as 1x, shown on the right. */
    val value: String? = null,
    /** Whether it opens a list to choose from, which an arrow shows. */
    val opensChoices: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The player's settings behind the cogwheel, as a sheet from the bottom: an icon, a name and the
 * current value on each line.
 */
class PlayerMenuDialogFragment(private val items: List<PlayerMenuItem> = emptyList()) :
    BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val list =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(16))
                addView(BottomSheetDragHandleView(context))
            }
        items.forEach { item -> list.addView(row(item)) }
        return NestedScrollView(context).apply { addView(list) }
    }

    override fun onStart() {
        super.onStart()
        // Brought back after the app was closed, when the lines are gone
        if (items.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // The player fills the screen, also while the menu is open
        dialog?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun row(item: PlayerMenuItem): View {
        val context = requireContext()
        val onSurface =
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                android.graphics.Color.WHITE,
            )
        val onSurfaceVariant =
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.LTGRAY,
            )
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(24), dp(8), dp(20), dp(8))
            setBackgroundResource(ripple.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                item.onClick()
            }

            addView(
                ImageView(context).apply {
                    setImageResource(item.icon)
                    imageTintList = ColorStateList.valueOf(onSurface)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            addView(
                TextView(context).apply {
                    text = item.title
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                    )
                    setTextColor(onSurface)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(24)
                    marginEnd = dp(16)
                },
            )
            item.value?.let { value ->
                addView(
                    TextView(context).apply {
                        text = value
                        maxLines = 1
                        // Track names can be long, and the name of the line comes first
                        ellipsize = TextUtils.TruncateAt.END
                        maxWidth = dp(200)
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        )
                        setTextColor(onSurfaceVariant)
                    }
                )
            }
            if (item.opensChoices) {
                // The down arrow, turned to point right
                addView(
                    ImageView(context).apply {
                        setImageResource(CoreR.drawable.ic_chevron_down)
                        rotation = -90f
                        imageTintList = ColorStateList.valueOf(onSurfaceVariant)
                    },
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) },
                )
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
