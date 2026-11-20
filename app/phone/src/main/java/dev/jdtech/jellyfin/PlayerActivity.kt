package dev.jdtech.jellyfin

import android.app.Activity
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
import android.graphics.Bitmap
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
import android.text.format.DateUtils
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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import coil3.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.databinding.ActivityPlayerBinding
import dev.jdtech.jellyfin.player.core.domain.models.StreamQuality
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.player.ChapterSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.PlayerMenuDialogFragment
import dev.jdtech.jellyfin.presentation.player.PlayerMenuItem
import dev.jdtech.jellyfin.presentation.player.PlayerPlaylistPanel
import dev.jdtech.jellyfin.presentation.player.QualitySelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.SleepTimerDialogFragment
import dev.jdtech.jellyfin.presentation.player.SpeedSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.TrackSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.darkAppColorScheme
import dev.jdtech.jellyfin.presentation.tube.LocalTaBaseUrl
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.PlayerButton
import dev.jdtech.jellyfin.settings.domain.PlayerButtonLayout
import dev.jdtech.jellyfin.settings.domain.PlayerButtonPlacement
import dev.jdtech.jellyfin.utils.PlayerGestureHelper
import dev.jdtech.jellyfin.utils.PreviewScrubListener
import java.lang.ref.WeakReference
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

    // Whether the playlist being played is slid open over the player
    private var playlistOpen by mutableStateOf(false)
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

    // The optional buttons, and those in the menu behind the cogwheel in their order
    private var playerButtons: Map<PlayerButton, View> = emptyMap()
    private var menuButtons: List<PlayerButton> = emptyList()

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
        addPlaylistPanel()
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
        val videoSubtitleTextView = binding.playerView.findViewById<TextView>(R.id.video_subtitle)

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
        val playlistButton = binding.playerView.findViewById<ImageButton>(R.id.btn_playlist)
        val autoplayButton = binding.playerView.findViewById<ImageButton>(R.id.btn_autoplay)
        val sleepTimerButton = binding.playerView.findViewById<ImageButton>(R.id.btn_sleep_timer)
        val menuButton = binding.playerView.findViewById<ImageButton>(R.id.btn_menu)
        val upNextCard = binding.playerView.findViewById<View>(R.id.up_next_card)
        val upNextLabel = binding.playerView.findViewById<TextView>(R.id.up_next_label)
        val upNextTitle = binding.playerView.findViewById<TextView>(R.id.up_next_title)
        val upNextThumbnail = binding.playerView.findViewById<ImageView>(R.id.up_next_thumbnail)
        // The picture asked for last, so it's loaded once rather than on every update. Empty
        // before the first, so a missing picture hides the thumbnail then too
        var upNextImage: String? = ""

        autoplayButton.setOnClickListener {
            val enabled = !viewModel.uiState.value.autoplay
            viewModel.setAutoplay(enabled)
            Toast.makeText(
                    this,
                    if (enabled) PlayerR.string.player_autoplay_on
                    else PlayerR.string.player_autoplay_off,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        sleepTimerButton.setOnClickListener {
            SleepTimerDialogFragment(viewModel).show(supportFragmentManager, "sleeptimerdialog")
        }
        menuButton.setOnClickListener { showPlayerMenu() }
        playlistButton.setOnClickListener { openPlaylist() }
        binding.playerView.findViewById<View>(R.id.up_next_play).setOnClickListener {
            viewModel.playNextNow()
        }
        binding.playerView.findViewById<View>(R.id.up_next_cancel).setOnClickListener {
            viewModel.cancelUpNext()
        }

        // Sits between the picture and the controls
        val audioOnlyOverlay =
            binding.playerView.overlayFrameLayout?.let { overlay ->
                layoutInflater.inflate(R.layout.player_audio_only_overlay, overlay, false).also {
                    overlay.addView(it)
                }
            }

        applyButtonLayout(
            mapOf(
                PlayerButton.AUTOPLAY to autoplayButton,
                PlayerButton.LOCK to lockButton,
                PlayerButton.PIP to pipButton,
                PlayerButton.CHAPTERS to chaptersButton,
                PlayerButton.QUALITY to qualityButton,
                PlayerButton.SPEED to speedButton,
                PlayerButton.SLEEP_TIMER to sleepTimerButton,
                PlayerButton.AUDIO to audioButton,
                PlayerButton.SUBTITLES to subtitleButton,
                PlayerButton.ROTATE to rotateButton,
                PlayerButton.PLAYLIST to playlistButton,
            ),
            menuButton,
        )
        applyAccentControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // Title, with the channel or show under it
                            videoNameTextView.text = currentItemTitle
                            videoSubtitleTextView.text = currentItemSubtitle
                            videoSubtitleTextView.isVisible = !currentItemSubtitle.isNullOrBlank()

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

                            // Autoplay: the switch, and the next video near the end of this one
                            autoplayButton.setImageResource(
                                if (autoplay) CoreR.drawable.ic_autoplay_on
                                else CoreR.drawable.ic_autoplay_off
                            )
                            autoplayButton.contentDescription =
                                getString(
                                    if (autoplay) PlayerR.string.player_autoplay_on
                                    else PlayerR.string.player_autoplay_off
                                )
                            // Filled in while the sleep timer runs
                            sleepTimerButton.setImageResource(
                                if (sleepTimer != null) CoreR.drawable.ic_moon_filled
                                else CoreR.drawable.ic_moon
                            )
                            sleepTimerButton.contentDescription =
                                getString(
                                    if (sleepTimer != null) PlayerR.string.player_sleep_timer_on
                                    else PlayerR.string.player_sleep_timer
                                )
                            upNextCard.isVisible = upNext != null && !isInPictureInPictureMode
                            upNext?.let { next ->
                                upNextLabel.text =
                                    getString(PlayerR.string.player_up_next_in, next.secondsLeft)
                                upNextTitle.text = next.title
                                if (next.image != upNextImage) {
                                    upNextImage = next.image
                                    upNextThumbnail.isVisible = next.image != null
                                    upNextThumbnail.setImageDrawable(null)
                                    next.image?.let { image ->
                                        upNextThumbnail.load(image) {
                                            listener(
                                                onError = { _, _ ->
                                                    upNextThumbnail.isVisible = false
                                                }
                                            )
                                        }
                                    }
                                }
                                // Playing the next one now does what the skip button would
                                skipSegmentButton.isVisible = false
                            }

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
                            // Only while a playlist plays, in a bar or in the menu
                            playlistButton.isVisible = taPlaylistId != null

                            // File Loaded
                            if (fileLoaded) {
                                audioButton.isEnabled = true
                                audioButton.imageAlpha = 255
                                lockButton.isEnabled = true
                                lockButton.imageAlpha = 255
                                subtitleButton.isEnabled = true
                                // Dimmed while captions are off
                                subtitleButton.imageAlpha = if (subtitlesOn) 255 else 120
                                subtitleButton.contentDescription =
                                    getString(
                                        if (subtitlesOn) PlayerR.string.player_captions_on
                                        else PlayerR.string.player_captions_off
                                    )
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
                            is PlayerEvents.SleepTimerPaused ->
                                Toast.makeText(
                                        this@PlayerActivity,
                                        PlayerR.string.player_sleep_paused,
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
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

        // Turns captions on and off; holding it, or Captions in the menu, picks the track
        subtitleButton.setOnClickListener {
            val on = viewModel.toggleSubtitles()
            Toast.makeText(
                    this,
                    if (on) PlayerR.string.player_captions_on
                    else PlayerR.string.player_captions_off,
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        subtitleButton.setOnLongClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_TEXT, viewModel)
                .show(supportFragmentManager, "trackselectiondialog")
            true
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
            taPlaylistId = intent.extras?.getString("taPlaylistId"),
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

        // Shows the next video, and counts down to it, near the end of each one
        scope.launch {
            while (true) {
                viewModel.updateUpNext()
                delay(500L)
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

        // Brought back from the background as the app opened, to carry on in a small window
        if (intent.getBooleanExtra(EXTRA_REOPEN_IN_PIP, false)) {
            enterPipOnResume = true
            return
        }

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
            taPlaylistId = intent.extras?.getString("taPlaylistId"),
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
     * Puts the optional buttons in the bars, or in the menu behind the cogwheel, in the order
     * chosen in settings, leaving out hidden ones. The cogwheel comes last in the top bar, when the
     * menu has something in it.
     */
    private fun applyButtonLayout(buttons: Map<PlayerButton, View>, menuButton: View) {
        val topBar = binding.playerView.findViewById<LinearLayout>(R.id.extra_buttons) ?: return
        val bottomBar = binding.playerView.findViewById<LinearLayout>(R.id.bottom_buttons) ?: return
        val density = resources.displayMetrics.density

        buttons.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        (menuButton.parent as? ViewGroup)?.removeView(menuButton)
        playerButtons = buttons
        val menu = mutableListOf<PlayerButton>()

        val layout = PlayerButtonLayout.parse(appPreferences.getValue(appPreferences.playerButtons))
        for (config in layout) {
            val view = buttons[config.button] ?: continue
            // A captions switch in a bar leaves picking the track to the menu
            if (
                config.button == PlayerButton.SUBTITLES &&
                    config.placement != PlayerButtonPlacement.HIDDEN
            ) {
                menu += config.button
            }
            val bar =
                when (config.placement) {
                    PlayerButtonPlacement.TOP -> topBar
                    PlayerButtonPlacement.BOTTOM -> bottomBar
                    PlayerButtonPlacement.MENU -> {
                        if (config.button !in menu) menu += config.button
                        continue
                    }
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

        menuButtons = menu
        // Always there: the menu also has tunneling and the playback info
        val padding = (16 * density).toInt()
        menuButton.setPadding(padding, padding, padding, padding)
        topBar.addView(
            menuButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** The menu behind the cogwheel, with what each setting is now. */
    private fun showPlayerMenu() {
        val state = viewModel.uiState.value
        val items = buildList {
            addAll(menuButtons.mapNotNull { menuItem(it, state) })
            // For finding out why sound and picture drift apart
            add(
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_arrow_down_up,
                    title = getString(PlayerR.string.player_tunneling),
                    value =
                        getString(
                            if (viewModel.tunnelingOn) PlayerR.string.player_on
                            else PlayerR.string.player_sleep_off
                        ),
                ) {
                    val on = !viewModel.tunnelingOn
                    viewModel.setTunneling(on)
                    Toast.makeText(
                            this@PlayerActivity,
                            if (on) PlayerR.string.player_tunneling_on
                            else PlayerR.string.player_tunneling_off,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            )
            add(
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_info,
                    title = getString(PlayerR.string.player_playback_info),
                    opensChoices = true,
                ) {
                    showPlaybackInfo()
                }
            )
        }
        if (items.isEmpty()) return
        PlayerMenuDialogFragment(items).show(supportFragmentManager, "playermenu")
    }

    /**
     * What's playing and how: formats, decoders, tunneling and frames, kept current every second
     * while it's open, so frames dropped while it plays show up.
     */
    private fun showPlaybackInfo() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val text =
            TextView(this).apply {
                setTextIsSelectable(true)
                setPadding(padding, padding / 2, padding, 0)
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
            }
        fun update() {
            text.text =
                viewModel.playbackInfo().joinToString("\n") { (label, value) -> "$label: $value" }
        }
        update()
        val updates = lifecycleScope.launch {
            while (true) {
                delay(1000)
                update()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(PlayerR.string.player_playback_info)
            .setView(text)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                updates.cancel()
                hideSystemUI()
            }
            .show()
    }

    /**
     * Opens the playlist's page in the app. The video carries on in a small window over it, or
     * stops where the phone doesn't allow one.
     */
    /**
     * The playlist being played, on top of the player and hidden until it's opened. Nothing shows
     * while it's closed, so taps go through to the player.
     */
    private fun addPlaylistPanel() {
        val panel =
            ComposeView(this).apply {
                setContent {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    FindroidTheme(darkTheme = true) {
                        CompositionLocalProvider(LocalTaBaseUrl provides viewModel.taBaseUrl) {
                            PlayerPlaylistPanel(
                                open = playlistOpen && state.taPlaylistId != null,
                                name = state.taPlaylistName.orEmpty(),
                                videos = state.taPlaylistVideos,
                                currentVideoId = state.taVideoId,
                                loop = state.loopPlaylist,
                                onClose = { playlistOpen = false },
                                onPlay = { videoId ->
                                    playlistOpen = false
                                    viewModel.playTaPlaylistVideo(videoId)
                                },
                                onLoopChange = viewModel::setLoopPlaylist,
                                onOpenPage = {
                                    playlistOpen = false
                                    state.taPlaylistId?.let(::openPlaylistPage)
                                },
                            )
                        }
                    }
                }
            }
        binding.root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Slides the playlist open, with how far each video was watched brought up to date. */
    private fun openPlaylist() {
        viewModel.refreshTaPlaylistVideos()
        binding.playerView.hideController()
        playlistOpen = true
    }

    private fun openPlaylistPage(playlistId: String) {
        PendingNavigation.request(TaPlaylistRoute(playlistId))
        val inWindow =
            isPipSupported &&
                try {
                    enterPictureInPictureMode(pipParams())
                } catch (e: RuntimeException) {
                    // Such as a video whose size isn't known yet
                    Timber.w(e, "Could not open the small window")
                    false
                }
        if (!inWindow) finishPlayback()
    }

    private fun menuItem(button: PlayerButton, state: PlayerViewModel.UiState): PlayerMenuItem? {
        val view = playerButtons[button] ?: return null
        // Like the buttons, a line only shows when it does something for this video, such as
        // chapters when there are some
        if (!view.isVisible) return null
        val off = getString(PlayerR.string.player_sleep_off)
        return when (button) {
            PlayerButton.AUTOPLAY ->
                PlayerMenuItem(
                    icon =
                        if (state.autoplay) CoreR.drawable.ic_autoplay_on
                        else CoreR.drawable.ic_autoplay_off,
                    title = getString(PlayerR.string.player_autoplay),
                    value = if (state.autoplay) getString(PlayerR.string.player_on) else off,
                ) {
                    view.performClick()
                }
            PlayerButton.QUALITY ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_film,
                    title = getString(PlayerR.string.select_quality),
                    value =
                        when (state.currentQuality) {
                            StreamQuality.ORIGINAL -> getString(PlayerR.string.quality_original)
                            StreamQuality.AUDIO_ONLY -> getString(PlayerR.string.player_audio_only)
                            else -> state.currentQuality.key
                        },
                    opensChoices = true,
                ) {
                    view.performClick()
                }
            PlayerButton.SPEED -> {
                val speed = viewModel.playbackSpeed
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_gauge,
                    title = getString(PlayerR.string.player_playback_speed),
                    value = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                    opensChoices = true,
                ) {
                    view.performClick()
                }
            }
            PlayerButton.SUBTITLES ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_closed_caption,
                    title = getString(PlayerR.string.player_captions),
                    value = selectedTrackName(C.TRACK_TYPE_TEXT) ?: off,
                    opensChoices = true,
                ) {
                    TrackSelectionDialogFragment(C.TRACK_TYPE_TEXT, viewModel)
                        .show(supportFragmentManager, "trackselectiondialog")
                }
            PlayerButton.LOCK ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_lock,
                    title = getString(PlayerR.string.player_lock_screen),
                ) {
                    view.performClick()
                }
            PlayerButton.SLEEP_TIMER ->
                PlayerMenuItem(
                    icon =
                        if (state.sleepTimer != null) CoreR.drawable.ic_moon_filled
                        else CoreR.drawable.ic_moon,
                    title = getString(PlayerR.string.player_sleep_timer),
                    value =
                        when (val timer = state.sleepTimer) {
                            null -> off
                            is PlayerViewModel.SleepTimer.At ->
                                getString(
                                    PlayerR.string.player_sleep_time_left,
                                    DateUtils.formatElapsedTime(timer.millisLeft / 1000),
                                )
                            PlayerViewModel.SleepTimer.EndOfVideo ->
                                getString(PlayerR.string.player_sleep_end_of_video)
                        },
                    opensChoices = true,
                ) {
                    view.performClick()
                }
            PlayerButton.PIP ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_picture_in_picture,
                    title = getString(PlayerR.string.player_picture_in_picture),
                ) {
                    view.performClick()
                }
            PlayerButton.AUDIO ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_speaker,
                    title = getString(PlayerR.string.player_audio_track),
                    value = selectedTrackName(C.TRACK_TYPE_AUDIO),
                    opensChoices = true,
                ) {
                    view.performClick()
                }
            PlayerButton.CHAPTERS ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_list_chapters,
                    title = getString(PlayerR.string.chapters),
                    opensChoices = true,
                ) {
                    view.performClick()
                }
            PlayerButton.PLAYLIST ->
                PlayerMenuItem(
                    icon = CoreR.drawable.ic_list_video,
                    title = getString(PlayerR.string.player_playlist),
                    value = state.taPlaylistName,
                    opensChoices = true,
                ) {
                    openPlaylist()
                }
            PlayerButton.ROTATE ->
                PlayerMenuItem(
                    icon =
                        if (
                            resources.configuration.orientation ==
                                Configuration.ORIENTATION_PORTRAIT
                        )
                            CoreR.drawable.ic_rotate_to_landscape
                        else CoreR.drawable.ic_rotate_to_portrait,
                    title = getString(PlayerR.string.player_rotate_screen),
                ) {
                    view.performClick()
                }
        }
    }

    /** The name of the audio or subtitle track playing, or null when none is. */
    private fun selectedTrackName(type: @C.TrackType Int): String? {
        val groups =
            viewModel.player.currentTracks.groups.filter { it.type == type && it.isSupported }
        val index = groups.indexOfFirst { it.isSelected }
        return if (index >= 0) groups.getTrackNames().getOrNull(index) else null
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

    /** Set when the player was brought back to go straight into picture-in-picture. */
    private var enterPipOnResume = false

    // After picture-in-picture couldn't open, the player stays in the background from then on,
    // rather than coming to the front each time the app is opened
    private var pipReopenFailed = false

    // Set while the player comes back as a small window in the phone's orientation, to turn to
    // landscape again once it's made big
    private var restoreOrientationAfterPip = false

    override fun onStart() {
        super.onStart()
        if (backgroundPlayer?.get() === this) backgroundPlayer = null
    }

    override fun onResume() {
        super.onResume()
        if (enterPipOnResume) {
            enterPipOnResume = false
            val entered =
                try {
                    enterPictureInPictureMode(pipParams())
                } catch (e: Exception) {
                    Timber.w(e, "Could not reopen picture-in-picture")
                    false
                }
            if (!entered) {
                pipReopenFailed = true
                restoreOrientationAfterPip = false
                requestedOrientation = playerOrientation()
                moveTaskToBack(true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Playing on with nothing on screen: opening the app brings it back as a small window
        if (!isFinishing && backgroundPlayback && isPipSupported && !pipReopenFailed) {
            backgroundPlayer = WeakReference(this)
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
        binding.playerView.findViewById<Button>(R.id.up_next_play)?.apply {
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(scheme.onPrimary.toArgb())
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
        // Android puts these in a row in the middle at the bottom, so an empty one on the left
        // puts play and pause in the middle with the headphones to its right
        return listOf(pipSpacer(), playPause, background)
    }

    /** A button that can't be seen or pressed, to take up a place in the window's row. */
    private fun pipSpacer(): RemoteAction {
        val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(this, PIP_CONTROL_NONE, intent, PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithBitmap(empty), " ", " ", pendingIntent).apply {
            isEnabled = false
        }
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
        if (backgroundPlayer?.get() === this) backgroundPlayer = null
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
                playlistOpen = false
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

                if (restoreOrientationAfterPip) {
                    restoreOrientationAfterPip = false
                    requestedOrientation = playerOrientation()
                }

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

    companion object {
        /** A player playing on in the background, with nothing on screen. */
        private var backgroundPlayer: WeakReference<PlayerActivity>? = null

        /**
         * Brings a video playing on in the background back as a picture-in-picture window, for when
         * the app is opened again. Does nothing when no video is playing that way.
         */
        fun reopenInPictureInPicture(from: Activity) {
            val player = backgroundPlayer?.get() ?: return
            backgroundPlayer = null
            if (player.isFinishing || player.isDestroyed) return
            // Paused from the notification, it stays out of the way
            if (!player.viewModel.player.playWhenReady) return
            // Comes back in the phone's orientation, so the screen doesn't turn for a moment
            player.restoreOrientationAfterPip = true
            player.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            from.startActivity(
                Intent(from, PlayerActivity::class.java).putExtra(EXTRA_REOPEN_IN_PIP, true)
            )
        }
    }
}

private const val ACTION_PIP_CONTROL = "dev.jdtech.jellyfin.PIP_CONTROL"
private const val EXTRA_REOPEN_IN_PIP = "reopenInPip"
private const val EXTRA_PIP_CONTROL = "control"
private const val PIP_CONTROL_PLAY = 1
private const val PIP_CONTROL_PAUSE = 2
private const val PIP_CONTROL_BACKGROUND = 3

// The empty button in the small window's row, which does nothing
private const val PIP_CONTROL_NONE = 0
private const val CONTROLS_FADE_IN_MS = 150L
private const val CONTROLS_FADE_OUT_MS = 250L

// About 38%, as Material uses for disabled icons
private const val DISABLED_ICON_ALPHA = 97
