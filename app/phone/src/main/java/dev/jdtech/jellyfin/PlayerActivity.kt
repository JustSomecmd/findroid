package dev.jdtech.jellyfin

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.databinding.ActivityPlayerBinding
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.player.ChapterSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.QualitySelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.SpeedSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.TrackSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.theme.darkAppColorScheme
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.PlayerButton
import dev.jdtech.jellyfin.settings.domain.PlayerButtonLayout
import dev.jdtech.jellyfin.settings.domain.PlayerButtonPlacement
import dev.jdtech.jellyfin.utils.PlayerGestureHelper
import dev.jdtech.jellyfin.utils.PreviewScrubListener
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

var isControlsLocked: Boolean = false

@AndroidEntryPoint
class PlayerActivity : BasePlayerActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    lateinit var binding: ActivityPlayerBinding
    private var playerGestureHelper: PlayerGestureHelper? = null
    override val viewModel: PlayerViewModel by viewModels()

    override fun isBackgroundPlaybackEnabled(): Boolean =
        appPreferences.getValue(appPreferences.playerBackgroundPlayback)

    // Switched with the rotate button; the player starts in landscape
    private var portraitMode = false

    private fun playerOrientation(): Int =
        if (portraitMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    private var previewScrubListener: PreviewScrubListener? = null
    private var wasZoom: Boolean = false
    private var skipButtonTimeoutExpired: Boolean = true
    private var hasCurrentSegment: Boolean = false
    private var controllerFadingOut: Boolean = false

    private lateinit var skipSegmentButton: Button

    private val isPipSupported by lazy {
        // Check if device has PiP feature
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@lazy false
        }

        // Check if PiP is enabled for the app
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager?
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private val handler = Handler(Looper.getMainLooper())
    private val skipButtonTimeout = Runnable {
        if (!binding.playerView.isControllerFullyVisible) {
            skipSegmentButton.isVisible = false
            skipButtonTimeoutExpired = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemId = UUID.fromString(intent.extras!!.getString("itemId"))
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                onControllerVisibilityChanged(visibility)
            }
        )

        val playerControls = binding.playerView.findViewById<View>(R.id.player_controls)
        val lockedControls = binding.playerView.findViewById<View>(R.id.locked_player_view)

        isControlsLocked = false

        configureInsets(playerControls)
        configureInsets(lockedControls)

        if (appPreferences.getValue(appPreferences.playerGestures)) {
            playerGestureHelper =
                PlayerGestureHelper(
                    appPreferences,
                    this,
                    binding.playerView,
                    getSystemService(AUDIO_SERVICE) as AudioManager,
                )
        }

        binding.playerView.findViewById<View>(R.id.back_button).setOnClickListener {
            finishPlayback()
        }

        val videoNameTextView = binding.playerView.findViewById<TextView>(R.id.video_name)

        val audioButton = binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)
        val subtitleButton = binding.playerView.findViewById<ImageButton>(R.id.btn_subtitle)
        val speedButton = binding.playerView.findViewById<ImageButton>(R.id.btn_speed)
        val chaptersButton = binding.playerView.findViewById<ImageButton>(R.id.btn_chapters)
        val qualityButton = binding.playerView.findViewById<ImageButton>(R.id.btn_quality)
        skipSegmentButton = binding.playerView.findViewById(R.id.btn_skip_segment)
        val pipButton = binding.playerView.findViewById<ImageButton>(R.id.btn_pip)
        val lockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_lockview)
        val unlockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_unlock)
        val rotateButton = binding.playerView.findViewById<ImageButton>(R.id.btn_rotate)

        // Sits between the picture and the controls
        val audioOnlyOverlay =
            binding.playerView.overlayFrameLayout?.let { overlay ->
                layoutInflater.inflate(R.layout.player_audio_only_overlay, overlay, false).also {
                    overlay.addView(it)
                }
            }

        applyButtonLayout(
            mapOf(
                PlayerButton.LOCK to lockButton,
                PlayerButton.PIP to pipButton,
                PlayerButton.CHAPTERS to chaptersButton,
                PlayerButton.QUALITY to qualityButton,
                PlayerButton.SPEED to speedButton,
                PlayerButton.AUDIO to audioButton,
                PlayerButton.SUBTITLES to subtitleButton,
                PlayerButton.ROTATE to rotateButton,
            )
        )
        applyAccentControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // Title
                            videoNameTextView.text = currentItemTitle

                            // Media segment
                            currentSegment?.let { segment ->
                                // Skip Button - text
                                skipSegmentButton.text = getString(currentSkipButtonStringRes)
                                // Skip Button - visibility
                                skipSegmentButton.isVisible = !isInPictureInPictureMode
                                if (skipSegmentButton.isVisible) {
                                    skipButtonTimeoutExpired = false
                                    handler.removeCallbacks(skipButtonTimeout)
                                    handler.postDelayed(
                                        skipButtonTimeout,
                                        viewModel.segmentsSkipButtonDuration * 1000,
                                    )
                                }
                                // Skip Button - onClick
                                skipSegmentButton.setOnClickListener {
                                    viewModel.skipSegment(segment)
                                    skipSegmentButton.isVisible = false
                                }
                            } ?: run { skipSegmentButton.isVisible = false }
                            hasCurrentSegment = currentSegment != null

                            // Trickplay
                            previewScrubListener?.let { it.currentTrickplay = currentTrickplay }

                            playerGestureHelper?.let { it.currentTrickplay = currentTrickplay }

                            // Chapters
                            val playerControlView =
                                findViewById<PlayerControlView>(R.id.exo_controller)
                            if (currentChapters.isNotEmpty()) {
                                val numOfChapters = currentChapters.size
                                playerControlView.setExtraAdGroupMarkers(
                                    LongArray(numOfChapters) { index ->
                                        currentChapters[index].startPosition
                                    },
                                    BooleanArray(numOfChapters) { false },
                                )
                            } else {
                                playerControlView.setExtraAdGroupMarkers(null, null)
                            }
                            // A single chapter has nothing to skip to
                            chaptersButton.isVisible = currentChapters.size > 1

                            // Subtitles: hidden when the video has none
                            subtitleButton.isVisible = hasSubtitles

                            audioOnlyOverlay?.isVisible = isAudioOnly

                            // Quality: downloads play from the device and can't be converted
                            qualityButton.isVisible = canChangeQuality

                            // File Loaded
                            if (fileLoaded) {
                                audioButton.isEnabled = true
                                audioButton.imageAlpha = 255
                                lockButton.isEnabled = true
                                lockButton.imageAlpha = 255
                                subtitleButton.isEnabled = true
                                subtitleButton.imageAlpha = 255
                                speedButton.isEnabled = true
                                speedButton.imageAlpha = 255
                                pipButton.isEnabled = true
                                pipButton.imageAlpha = 255
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            is PlayerEvents.IsPlayingChanged -> {
                                if (event.isPlaying) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    )
                                }

                                val pipGesture =
                                    appPreferences.getValue(appPreferences.playerPipGesture)
                                // Also keeps the play/pause button in the PiP window current
                                if (pipGesture || (backgroundPlayback && isPipSupported)) {
                                    try {
                                        setPictureInPictureParams(
                                            pipParams(pipGesture && event.isPlaying)
                                        )
                                    } catch (_: IllegalArgumentException) {}
                                }
                            }
                        }
                    }
                }

                if (!backgroundPlayback) {
                    launchPlaybackLoops(this)
                }
            }
        }

        // With background playback, progress keeps being saved and intros keep being skipped
        // while the screen is off
        if (backgroundPlayback) {
            launchPlaybackLoops(lifecycleScope)
        }

        audioButton.isEnabled = false
        audioButton.imageAlpha = 75

        lockButton.isEnabled = false
        lockButton.imageAlpha = 75

        subtitleButton.isEnabled = false
        subtitleButton.imageAlpha = 75

        speedButton.isEnabled = false
        speedButton.imageAlpha = 75

        if (isPipSupported) {
            pipButton.isEnabled = false
            pipButton.imageAlpha = 75
        } else {
            pipButton.isVisible = false
        }

        audioButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_AUDIO, viewModel)
                .show(supportFragmentManager, "trackselectiondialog")
        }

        val exoPlayerControlView = findViewById<FrameLayout>(R.id.player_controls)
        val lockedLayout = findViewById<FrameLayout>(R.id.locked_player_view)

        lockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.GONE
            lockedLayout.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            isControlsLocked = true
        }

        unlockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.VISIBLE
            lockedLayout.visibility = View.GONE
            requestedOrientation = playerOrientation()
            isControlsLocked = false
        }

        subtitleButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_TEXT, viewModel)
                .show(supportFragmentManager, "trackselectiondialog")
        }

        speedButton.setOnClickListener {
            SpeedSelectionDialogFragment(viewModel)
                .show(supportFragmentManager, "speedselectiondialog")
        }

        rotateButton.setOnClickListener {
            portraitMode = !portraitMode
            requestedOrientation = playerOrientation()
        }
        updateOrientationLayout(
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        )

        chaptersButton.setOnClickListener {
            ChapterSelectionDialogFragment(viewModel)
                .show(supportFragmentManager, "chapterselectiondialog")
        }

        qualityButton.setOnClickListener {
            QualitySelectionDialogFragment(viewModel)
                .show(supportFragmentManager, "qualityselectiondialog")
        }

        pipButton.setOnClickListener { pictureInPicture() }

        // Set marker color
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
        timeBar.setAdMarkerColor(Color.WHITE)

        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
            val imagePreview = binding.playerView.findViewById<ImageView>(R.id.image_preview)
            previewScrubListener = PreviewScrubListener(imagePreview, timeBar, viewModel.player)

            timeBar.addListener(previewScrubListener!!)
        }

        if (backgroundPlayback && isPipSupported) {
            ContextCompat.registerReceiver(
                this,
                pipControlReceiver,
                IntentFilter(ACTION_PIP_CONTROL),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pipControlReceiverRegistered = true
        }

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            startPositionMs = intent.extras?.getLong("position", 0L),
        )
        hideSystemUI()
    }

    /** Reports progress to the server and checks for skippable segments while playing. */
    private fun launchPlaybackLoops(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                viewModel.updatePlaybackProgress()
                delay(5000L)
            }
        }

        if (viewModel.checksSegments) {
            scope.launch {
                while (true) {
                    viewModel.updateCurrentSegment()
                    delay(1000L)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Tapping the playback notification only brings the player back
        val itemIdString = intent.extras?.getString("itemId") ?: return
        setIntent(intent)

        val itemId = UUID.fromString(itemIdString)
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            startPositionMs = intent.extras?.getLong("position", 0L),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationLayout(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    /**
     * In portrait the screen is too narrow for the title and all buttons in one row, so the buttons
     * move to a second row with less space between them.
     */
    private fun updateOrientationLayout(portrait: Boolean) {
        val topBar = binding.playerView.findViewById<LinearLayout>(R.id.top_bar) ?: return
        val titleRow = binding.playerView.findViewById<View>(R.id.title_row)
        val buttonsScroll = binding.playerView.findViewById<View>(R.id.extra_buttons_scroll)
        val extraButtons = binding.playerView.findViewById<LinearLayout>(R.id.extra_buttons)
        val bottomButtons = binding.playerView.findViewById<LinearLayout>(R.id.bottom_buttons)

        topBar.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        titleRow.layoutParams =
            (titleRow.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0
                weight = if (portrait) 0f else 1f
            }
        buttonsScroll.layoutParams =
            (buttonsScroll.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = Gravity.END
            }

        // Gaps between buttons. Dividers only go between visible buttons, so a hidden button
        // leaves no extra gap behind.
        val density = resources.displayMetrics.density
        extraButtons.dividerDrawable = gap(((if (portrait) 4 else 16) * density).toInt())
        extraButtons.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        bottomButtons?.dividerDrawable = gap(((if (portrait) 4 else 8) * density).toInt())
        bottomButtons?.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE

        binding.playerView
            .findViewById<ImageButton>(R.id.btn_rotate)
            ?.setImageResource(
                if (portrait) CoreR.drawable.ic_rotate_to_landscape
                else CoreR.drawable.ic_rotate_to_portrait
            )
    }

    private fun gap(width: Int) =
        GradientDrawable().apply {
            setSize(width, 1)
            setColor(Color.TRANSPARENT)
        }

    /**
     * Puts the optional buttons in the bars and order chosen in settings, leaving out hidden ones.
     */
    private fun applyButtonLayout(buttons: Map<PlayerButton, View>) {
        val topBar = binding.playerView.findViewById<LinearLayout>(R.id.extra_buttons) ?: return
        val bottomBar = binding.playerView.findViewById<LinearLayout>(R.id.bottom_buttons) ?: return
        val density = resources.displayMetrics.density

        buttons.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }

        val layout = PlayerButtonLayout.parse(appPreferences.getValue(appPreferences.playerButtons))
        for (config in layout) {
            val view = buttons[config.button] ?: continue
            val bar =
                when (config.placement) {
                    PlayerButtonPlacement.TOP -> topBar
                    PlayerButtonPlacement.BOTTOM -> bottomBar
                    PlayerButtonPlacement.HIDDEN -> continue
                }
            // The bottom bar is slimmer, so its buttons are too
            val padding = ((if (bar == topBar) 16 else 12) * density).toInt()
            view.setPadding(padding, padding, padding, padding)
            bar.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun onControllerVisibilityChanged(visibility: Int) {
        if (visibility == View.GONE && !isInPictureInPictureMode) {
            hideSystemUI()
        }
        if (skipButtonTimeoutExpired && hasCurrentSegment) {
            skipSegmentButton.visibility = visibility
        }
        fadeController(visibility)
    }

    /**
     * The player shows and hides its controls instantly; this fades them instead. To fade out, the
     * controls are made visible again right after the player hides them, and hidden for real once
     * they are fully transparent. Showing them again in the middle of a fade picks up from there.
     */
    private fun fadeController(visibility: Int) {
        val controller = binding.playerView.findViewById<View>(R.id.exo_controller) ?: return
        controller.animate().cancel()

        if (isInPictureInPictureMode || !binding.playerView.useController) {
            controllerFadingOut = false
            controller.alpha = 1f
            return
        }

        if (visibility == View.VISIBLE) {
            if (!controllerFadingOut) controller.alpha = 0f
            controllerFadingOut = false
            controller.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_MS)
        } else {
            controllerFadingOut = true
            controller.visibility = View.VISIBLE
            controller.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_MS).withEndAction {
                controllerFadingOut = false
                controller.visibility = View.GONE
                controller.alpha = 1f
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                appPreferences.getValue(appPreferences.playerPipGesture) &&
                viewModel.player.isPlaying &&
                !isControlsLocked
        ) {
            pictureInPicture()
        }
    }

    private fun finishPlayback() {
        // Stops the sound together with the picture. With background playback on, the player
        // would otherwise keep going until the screen has finished closing.
        viewModel.player.playWhenReady = false
        try {
            viewModel.player.clearVideoSurfaceView(
                binding.playerView.videoSurfaceView as SurfaceView
            )
        } catch (e: Exception) {
            Timber.e(e)
        }
        handler.removeCallbacks(skipButtonTimeout)
        finish()
    }

    private fun pipParams(
        enableAutoEnter: Boolean = viewModel.player.isPlaying
    ): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        val aspectRatio =
            binding.playerView.player?.videoSize?.let {
                Rational(
                    it.width.coerceAtMost((it.height * 2.39f).toInt()),
                    it.height.coerceAtMost((it.width * 2.39f).toInt()),
                )
            }

        val sourceRectHint =
            if (displayAspectRatio < aspectRatio!!) {
                val space =
                    ((binding.playerView.height -
                            (binding.playerView.width.toFloat() / aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    0,
                    space,
                    binding.playerView.width,
                    (binding.playerView.width.toFloat() / aspectRatio.toFloat()).toInt() + space,
                )
            } else {
                val space =
                    ((binding.playerView.width -
                            (binding.playerView.height.toFloat() * aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    space,
                    0,
                    (binding.playerView.height.toFloat() * aspectRatio.toFloat()).toInt() + space,
                    binding.playerView.height,
                )
            }

        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setSourceRectHint(sourceRectHint)

        if (backgroundPlayback) {
            // Custom buttons replace the default media buttons, so play/pause is added as well
            builder.setActions(pipActions(viewModel.player.isPlaying))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enableAutoEnter)
        }

        return builder.build()
    }

    /**
     * Gives the buttons the app's accent color, with a grey play button whose arrow takes the
     * accent, when that's turned on in the player settings. The progress bar follows the accent
     * too.
     */
    private fun applyAccentControls() {
        if (!appPreferences.getValue(appPreferences.playerAccentControls)) return
        val scheme =
            darkAppColorScheme(
                context = this,
                dynamicColor = appPreferences.getValue(appPreferences.dynamicColors),
                accent = appPreferences.getValue(appPreferences.accentColor),
            )
        val accent = scheme.primary.toArgb()
        val icons =
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(ColorUtils.setAlphaComponent(accent, DISABLED_ICON_ALPHA), accent),
            )
        listOf(R.id.player_controls, R.id.locked_player_view).forEach { id ->
            binding.playerView.findViewById<ViewGroup>(id)?.let { tintImageButtons(it, icons) }
        }
        binding.playerView.findViewById<ImageButton>(R.id.exo_play_pause)?.backgroundTintList =
            ColorStateList.valueOf(scheme.surfaceContainerHighest.toArgb())
        binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)?.apply {
            setPlayedColor(accent)
            setScrubberColor(accent)
        }
    }

    private fun tintImageButtons(group: ViewGroup, tint: ColorStateList) {
        for (child in group.children) {
            when (child) {
                is ImageButton -> child.imageTintList = tint
                is ViewGroup -> tintImageButtons(child, tint)
            }
        }
    }

    private fun pipActions(isPlaying: Boolean): List<RemoteAction> {
        val playPause =
            if (isPlaying) {
                pipAction(
                    CoreR.drawable.ic_pause,
                    getString(PlayerR.string.player_pip_pause),
                    PIP_CONTROL_PAUSE,
                )
            } else {
                pipAction(
                    CoreR.drawable.ic_play,
                    getString(PlayerR.string.player_pip_play),
                    PIP_CONTROL_PLAY,
                )
            }
        val background =
            pipAction(
                CoreR.drawable.ic_headphones,
                getString(PlayerR.string.player_pip_background),
                PIP_CONTROL_BACKGROUND,
            )
        return listOf(playPause, background)
    }

    private fun pipAction(iconRes: Int, title: String, control: Int): RemoteAction {
        val intent =
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_CONTROL, control)
        val pendingIntent =
            PendingIntent.getBroadcast(this, control, intent, PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pendingIntent)
    }

    private val pipControlReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PIP_CONTROL) return
                when (intent.getIntExtra(EXTRA_PIP_CONTROL, 0)) {
                    PIP_CONTROL_PLAY -> viewModel.player.play()
                    PIP_CONTROL_PAUSE -> viewModel.player.pause()
                    PIP_CONTROL_BACKGROUND -> {
                        // Closes the window; the player keeps going in the background
                        continueInBackgroundAfterPip = true
                        moveTaskToBack(true)
                    }
                }
            }
        }

    private var pipControlReceiverRegistered = false

    override fun onDestroy() {
        if (pipControlReceiverRegistered) {
            unregisterReceiver(pipControlReceiver)
            pipControlReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun pictureInPicture() {
        if (!isPipSupported) {
            return
        }

        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalArgumentException) {}
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.isInPictureInPictureMode = isInPictureInPictureMode
        when (isInPictureInPictureMode) {
            true -> {
                binding.playerView.useController = false
                skipSegmentButton.isVisible = false

                wasZoom = playerGestureHelper?.isZoomEnabled == true
                playerGestureHelper?.updateZoomMode(false)

                // Brightness mode Auto
                window.attributes =
                    window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
            }

            false -> {
                binding.playerView.useController = true
                playerGestureHelper?.updateZoomMode(wasZoom)

                // Override auto brightness
                if (
                    appPreferences.getValue(appPreferences.playerGesturesVB) &&
                        appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)
                ) {
                    window.attributes =
                        window.attributes.apply {
                            screenBrightness =
                                appPreferences.getValue(appPreferences.playerBrightness)
                        }
                }
            }
        }
    }
}

private const val ACTION_PIP_CONTROL = "dev.jdtech.jellyfin.PIP_CONTROL"
private const val EXTRA_PIP_CONTROL = "control"
private const val PIP_CONTROL_PLAY = 1
private const val PIP_CONTROL_PAUSE = 2
private const val PIP_CONTROL_BACKGROUND = 3
private const val CONTROLS_FADE_IN_MS = 150L
private const val CONTROLS_FADE_OUT_MS = 250L

// About 38%, as Material uses for disabled icons
private const val DISABLED_ICON_ALPHA = 97
