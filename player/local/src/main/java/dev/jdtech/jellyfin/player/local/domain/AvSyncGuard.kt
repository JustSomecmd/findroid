package dev.jdtech.jellyfin.player.local.domain

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaFormat
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import timber.log.Timber

/**
 * Watches whether sound and picture stay in step while a video plays, puts right what it can, and
 * keeps a record of what happened to them for the player's Playback info.
 *
 * The player shows each frame of the picture by a clock it works out from the sound: how far the
 * phone says the sound has got. So they come apart in one of two ways, and this measures both:
 * - **The sound's clock is off.** The player asks the phone where the sound is only every ten
 *   seconds, and counts on from the last answer in between. After a hiccup in the sound, or an
 *   answer taken while the sound was only just starting, it times the picture by a clock that's
 *   wrong until it asks again, and then closes the gap slowly. [WatchedOutput] asks four times a
 *   second, and has the player ask afresh as soon as the two stay apart.
 * - **The picture is behind the clock**, because frames come out of the decoder too late.
 *   [PictureTiming] measures how late the frames shown were. Left to the player while it catches up
 *   by itself; a picture that stays behind is what [watch] asks a resync for.
 *
 * With tunneled playback the phone's video hardware times the picture itself, so neither the player
 * nor this can see or correct it. There, a resync is asked for after each rebuffer, as that's when
 * the hardware is known to lose its place.
 */
class AvSyncGuard(val enabled: Boolean) {
    private val log = SyncLog()
    private val soundClock = SoundClock(log)
    private val picture = PictureTiming()
    private val window = Timeline.Window()
    private var player: ExoPlayer? = null

    // The video the counts below are for, which may be loaded more than once, such as when the
    // quality changes
    private var mediaId: String? = null

    // Since when it has played on without a break, in System.nanoTime, which is as far back as the
    // frames shown say anything about now. Not steady while it isn't playing
    private var steadySinceNs = NOT_STEADY

    // What's being waited out before a pause in loading counts as a rebuffer: the first loading of
    // a video, and the loading after a skip
    private var wasReady = false
    private var settling = false
    private var rebufferSinceMs = 0L

    // This video's counts, for Playback info
    private var rebuffers = 0
    private var rebufferMs = 0L
    private var underruns = 0
    private var resyncs = 0
    private var droppedFrames = 0

    // How late the picture is now and was at worst, and since when it has been too late
    private var pictureLateMs: Long? = null
    private var worstPictureLateMs = 0L
    private var lateSinceMs = 0L
    private var lateResyncs = 0

    // A resync asked for a moment from now, and when the last one was done
    private var pendingResync: String? = null
    private var pendingResyncAtMs = 0L
    private var lastResyncAtMs = 0L

    /**
     * The renderers to build the player with: the usual ones, with the sound's clock watched. With
     * the guard turned off in settings, exactly the usual ones.
     */
    fun renderersFactory(context: Context): DefaultRenderersFactory =
        if (enabled) WatchedRenderersFactory(context, soundClock)
        else DefaultRenderersFactory(context)

    /** Starts watching [player]. Called once, when the player is made. */
    fun attach(player: ExoPlayer) {
        if (!enabled) return
        this.player = player
        player.addAnalyticsListener(listener)
        player.setVideoFrameMetadataListener(picture)
    }

    /**
     * Looks at how sound and picture are doing, every [SYNC_WATCH_MS] while it plays. Gives the
     * reason when they should be put back in step with a resync now, or null when there's none,
     * which is nearly always.
     */
    fun watch(): String? {
        val player = player ?: return null
        val nowMs = SystemClock.elapsedRealtime()
        pendingResync?.let { reason ->
            if (nowMs >= pendingResyncAtMs) {
                pendingResync = null
                if (player.isPlaying && mayResync(nowMs)) return reason
            }
        }
        // Tunneled frames are shown by the phone's video hardware, which doesn't say when
        if (player.isTunnelingEnabled) {
            pictureLateMs = null
            lateSinceMs = 0
            return null
        }
        val late = pictureLate(player)
        pictureLateMs = late
        if (late == null) {
            lateSinceMs = 0
            return null
        }
        if (abs(late) > abs(worstPictureLateMs)) worstPictureLateMs = late
        if (late < LATE_MS) {
            if (lateSinceMs != 0L) log.add("Picture caught up with the sound")
            lateSinceMs = 0
            return null
        }
        if (lateSinceMs == 0L) {
            lateSinceMs = nowMs
            log.add("Picture $late ms behind the sound at ${clockTime(player.currentPosition)}")
            return null
        }
        // The player drops frames to catch up by itself, which usually takes under a second
        if (nowMs - lateSinceMs < LATE_FOR_MS) return null
        lateSinceMs = 0
        if (lateResyncs >= MAX_LATE_RESYNCS || !mayResync(nowMs)) return null
        lateResyncs++
        return "picture stayed $late ms behind"
    }

    /**
     * Notes that sound and picture were just put back in step with a skip to where it already was,
     * whoever asked for it, so it shows in the record and the next one waits its turn.
     */
    fun resynced(reason: String) {
        if (!enabled) return
        resyncs++
        lastResyncAtMs = SystemClock.elapsedRealtime()
        pendingResync = null
        lateSinceMs = 0
        steadySinceNs = NOT_STEADY
        log.add("Resynced: $reason")
    }

    /** How sound and picture are doing, as lines for Playback info. */
    fun info(): List<Pair<String, String>> {
        if (!enabled) return listOf("Sync guard" to "Off in settings")
        return buildList {
            soundClock.output?.let { add("Sound output" to it) }
            add("Sound clock" to soundClockText())
            add("Picture timing" to pictureText())
            val rebufferSeconds = "%.1f".format(Locale.ROOT, rebufferMs / 1000.0)
            add(
                "Rebuffers" to
                    if (rebuffers == 0) "None" else "$rebuffers, $rebufferSeconds s in all"
            )
            if (underruns > 0) add("Sound underruns" to underruns.toString())
            add("Resyncs" to resyncs.toString())
        }
    }

    /** What happened to sound and picture lately, oldest first, each with its time of day. */
    fun record(): List<String> = log.lines()

    private fun soundClockText(): String {
        if (!soundClock.checked) return "Not checked yet"
        return buildString {
            append(signedMs(soundClock.offUs / 1000))
            append(" against the phone's own timing, ")
            append(signedMs(soundClock.worstOffUs / 1000))
            append(" at worst")
            val corrections = soundClock.corrections
            if (corrections > 0) append(", corrected ${times(corrections)}")
            val stuck = soundClock.stuckOffUs
            if (stuck != 0L) append(", stays ${signedMs(stuck / 1000)} off")
        }
    }

    private fun pictureText(): String {
        if (player?.isTunnelingEnabled == true) return "Timed by the phone's video hardware"
        val late = pictureLateMs ?: return "Not measured right now"
        return buildString {
            append(lateText(late))
            append(" by the sound clock, ")
            append(lateText(worstPictureLateMs))
            append(" at worst")
            if (droppedFrames > 0) append(", $droppedFrames frames dropped")
        }
    }

    /**
     * How far behind the clock the frames shown lately were, in milliseconds, or null while it
     * hasn't played on for long enough to tell.
     */
    private fun pictureLate(player: ExoPlayer): Long? {
        val nowNs = System.nanoTime()
        if (!player.isPlaying || nowNs < steadySinceNs) return null
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        if (timeline.isEmpty || index >= timeline.windowCount) return null
        // Frames carry their place in the stream, which for some streams starts before the video
        val streamUs = timeline.getWindow(index, window).positionInFirstPeriodUs
        val positionUs = Util.msToUs(player.currentPosition) + streamUs
        val sinceNs = maxOf(steadySinceNs, nowNs - LATE_WINDOW_NS)
        val late = picture.lateMs(positionUs, player.playbackParameters.speed, nowNs, sinceNs)
        // Further off than a picture can be: the stream counts its time some other way
        return late?.takeIf { abs(it) < IMPLAUSIBLE_MS }
    }

    private fun mayResync(nowMs: Long): Boolean =
        lastResyncAtMs == 0L || nowMs - lastResyncAtMs >= RESYNC_GAP_MS

    /** Frames shown before now say nothing more: something broke the flow of playing. */
    private fun notSteady(isPlaying: Boolean) {
        steadySinceNs = if (isPlaying) System.nanoTime() + STEADY_AFTER_NS else NOT_STEADY
        lateSinceMs = 0
    }

    private val listener =
        object : AnalyticsListener {
            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                wasReady = false
                settling = false
                rebufferSinceMs = 0
                pictureLateMs = null
                lateSinceMs = 0
                pendingResync = null
                if (mediaItem?.mediaId == mediaId) return
                mediaId = mediaItem?.mediaId
                rebuffers = 0
                rebufferMs = 0
                underruns = 0
                resyncs = 0
                droppedFrames = 0
                worstPictureLateMs = 0
                lateResyncs = 0
                soundClock.newVideo()
                val title = mediaItem?.mediaMetadata?.title
                if (title != null) log.add("Playing \"$title\"")
            }

            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) {
                val nowMs = SystemClock.elapsedRealtime()
                when (state) {
                    Player.STATE_BUFFERING ->
                        if (wasReady && !settling && player?.playWhenReady == true) {
                            rebufferSinceMs = nowMs
                        }
                    Player.STATE_READY -> {
                        val since = rebufferSinceMs
                        if (since != 0L) {
                            rebufferSinceMs = 0
                            rebuffers++
                            rebufferMs += nowMs - since
                            val seconds = "%.1f".format(Locale.ROOT, (nowMs - since) / 1000.0)
                            val at = clockTime(eventTime.currentPlaybackPositionMs)
                            log.add("Rebuffered for $seconds s at $at")
                            if (player?.isTunnelingEnabled == true) {
                                pendingResync = "after rebuffering, tunneled"
                                pendingResyncAtMs = nowMs + RESYNC_AFTER_REBUFFER_MS
                            }
                        }
                        if (!wasReady) {
                            val at = clockTime(eventTime.currentPlaybackPositionMs)
                            log.add("Loaded, playing from $at")
                        }
                        wasReady = true
                        settling = false
                    }
                    else -> rebufferSinceMs = 0
                }
            }

            override fun onIsPlayingChanged(
                eventTime: AnalyticsListener.EventTime,
                isPlaying: Boolean,
            ) = notSteady(isPlaying)

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Loading after a skip isn't a rebuffer
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) settling = true
                notSteady(player?.isPlaying == true)
                // A skip of the viewer's own, which a resync is not
                val ownSkip =
                    reason == Player.DISCONTINUITY_REASON_SEEK &&
                        SystemClock.elapsedRealtime() - lastResyncAtMs > OWN_SKIP_AFTER_MS
                if (ownSkip && wasReady) {
                    val from = clockTime(oldPosition.positionMs)
                    log.add("Skipped from $from to ${clockTime(newPosition.positionMs)}")
                }
            }

            override fun onPlaybackParametersChanged(
                eventTime: AnalyticsListener.EventTime,
                playbackParameters: PlaybackParameters,
            ) = notSteady(player?.isPlaying == true)

            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) =
                notSteady(player?.isPlaying == true)

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                underruns++
                val at = clockTime(eventTime.currentPlaybackPositionMs)
                log.add("Sound ran dry at $at, last fed $elapsedSinceLastFeedMs ms before")
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception,
            ) {
                // Such as the sound's own timestamps jumping, which the player then follows
                log.add("Sound output: ${audioSinkError.message ?: audioSinkError.javaClass.name}")
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                val how =
                    when {
                        audioTrackConfig.tunneling -> ", tunneled"
                        audioTrackConfig.offload -> ", by the phone's audio chip"
                        else -> ""
                    }
                log.add("Sound output opened: ${audioTrackConfig.sampleRate} Hz$how")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                log.add("Picture decoder: $decoderName")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                this@AvSyncGuard.droppedFrames += droppedFrames
                if (droppedFrames >= DROPPED_WORTH_NOTING) {
                    val at = clockTime(eventTime.currentPlaybackPositionMs)
                    log.add("$droppedFrames frames dropped within $elapsedMs ms at $at")
                }
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                log.add("Player error: ${error.errorCodeName}")
            }
        }

    private companion object {
        const val NOT_STEADY = Long.MAX_VALUE

        // Frames shown this soon after playing started or skipped are still finding their place
        const val STEADY_AFTER_NS = 1_500_000_000L

        // The frames looked at: those shown in the last second and a half
        const val LATE_WINDOW_NS = 1_500_000_000L

        // A picture this far behind the sound shows; this long behind, it isn't catching up
        const val LATE_MS = 100L
        const val LATE_FOR_MS = 3_000L

        // More than this isn't a late picture but a stream that counts its time differently
        const val IMPLAUSIBLE_MS = 30_000L

        // A resync stops playback for a moment, so they're kept apart, and a picture that falls
        // behind again and again isn't going to be fixed by another
        const val RESYNC_GAP_MS = 20_000L
        const val MAX_LATE_RESYNCS = 2
        const val RESYNC_AFTER_REBUFFER_MS = 1_500L

        // A skip this soon after a resync is the resync's own
        const val OWN_SKIP_AFTER_MS = 1_000L

        const val DROPPED_WORTH_NOTING = 15
    }
}

/** How often the player looks at sound and picture while a video plays, in milliseconds. */
const val SYNC_WATCH_MS = 500L

private const val TAG = "AvSync"

/** The last things that happened to sound and picture, with the time of day of each. */
internal class SyncLog {
    private val entries = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun add(text: String) {
        Timber.tag(TAG).d(text)
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(System.currentTimeMillis() to text)
    }

    @Synchronized
    fun lines(): List<String> {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        return entries.map { (at, text) -> "${time.format(Date(at))} $text" }
    }

    private companion object {
        const val MAX_ENTRIES = 40
    }
}

/**
 * What the sound's clock was found to be doing. Written on the player's own thread, where the sound
 * is fed, and read for Playback info.
 */
internal class SoundClock(val log: SyncLog) {
    /** Where the sound goes and how, such as "Bluetooth, 48000 Hz, 500 ms buffer". */
    @Volatile var output: String? = null

    /** Whether the phone has said where the sound is, to check the player's clock against. */
    @Volatile var checked = false

    /** How far the player's clock is ahead of what the phone says, now and at worst. */
    @Volatile var offUs = 0L
    @Volatile var worstOffUs = 0L

    /** How often the player was made to ask the phone afresh. */
    @Volatile var corrections = 0

    /** How far off the clock stayed when asking afresh didn't help, or 0 when it's fine. */
    @Volatile var stuckOffUs = 0L

    fun newVideo() {
        worstOffUs = 0
        corrections = 0
        stuckOffUs = 0
    }
}

/** The usual renderers, with the sound going out through a [WatchedOutput]. */
private class WatchedRenderersFactory(context: Context, private val soundClock: SoundClock) :
    DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(
                WatchedOutputProvider(
                    AudioTrackAudioOutputProvider.Builder(context).build(),
                    soundClock,
                )
            )
            .build()
}

private class WatchedOutputProvider(
    provider: AudioOutputProvider,
    private val soundClock: SoundClock,
) : ForwardingAudioOutputProvider(provider) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        return if (output is AudioTrackAudioOutput) WatchedOutput(output, config, soundClock)
        else output
    }
}

/**
 * The sound's way out, which is also the player's clock: it says how far the sound has got. This
 * passes everything on as it is, and four times a second compares what the player takes the sound's
 * place to be with what the phone itself says ([AudioTrack.getTimestamp]).
 *
 * The player only asks the phone every ten seconds and counts on from the last answer, and when a
 * new answer differs it closes the gap by a tenth of a second per second. So when the two stay
 * apart without the gap closing, the player is counting on from an answer that no longer holds, and
 * it's made to ask afresh. That changes nothing in the sound.
 */
private class WatchedOutput(
    output: AudioTrackAudioOutput,
    private val config: AudioOutputProvider.OutputConfig,
    private val clock: SoundClock,
) : ForwardingAudioOutput(output) {
    private val track: AudioTrack = output.audioTrack
    private val sampleRate = track.sampleRate
    private val timestamp = AudioTimestamp()

    // Plain sound played the usual way: sound handed to the phone's audio chip, or sped up by the
    // phone rather than the player, keeps its place differently
    private var watching =
        sampleRate > 0 &&
            Util.isEncodingLinearPcm(config.encoding) &&
            !config.isOffload &&
            !config.usePlaybackParameters

    // In System.nanoTime: when it last started playing, from when on it's judged, and when it's
    // next looked at. Not judged while it isn't playing
    private var playedAtNs = 0L
    private var settledAtNs = NOT_PLAYING
    private var nextCheckNs = 0L
    private var nextOutputCheckNs = 0L

    // The end of the stream was handed over, after which the player counts on by itself
    private var stopped = false
    private var lastFrames = 0L

    // Since when the clock has been off, and how far it was then, to see whether the gap closes
    private var offSinceNs = 0L
    private var offAtStartUs = 0L
    private var correctedAtNs = 0L
    private var corrections = 0

    override fun play() {
        super.play()
        val nowNs = System.nanoTime()
        playedAtNs = nowNs
        settledAtNs = nowNs + SETTLE_NS
        offSinceNs = 0
    }

    override fun pause() {
        super.pause()
        settledAtNs = NOT_PLAYING
        offSinceNs = 0
    }

    override fun stop() {
        super.stop()
        stopped = true
    }

    override fun flush() {
        super.flush()
        stopped = false
        lastFrames = 0
        offSinceNs = 0
        if (settledAtNs != NOT_PLAYING) settledAtNs = System.nanoTime() + SETTLE_NS
    }

    override fun getPositionUs(): Long {
        val positionUs = super.getPositionUs()
        if (watching) {
            val nowNs = System.nanoTime()
            if (nowNs >= nextCheckNs) {
                nextCheckNs = nowNs + CHECK_EVERY_NS
                try {
                    check(positionUs, nowNs)
                } catch (e: RuntimeException) {
                    // Measuring never gets in the way of playing
                    watching = false
                    Timber.tag(TAG).w(e, "Stopped checking the sound clock")
                    clock.log.add("Sound clock no longer checked: ${e.javaClass.simpleName}")
                }
            }
        }
        return positionUs
    }

    private fun check(positionUs: Long, nowNs: Long) {
        if (stopped || nowNs < settledAtNs || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            offSinceNs = 0
            return
        }
        if (nowNs >= nextOutputCheckNs) {
            nextOutputCheckNs = nowNs + OUTPUT_EVERY_NS
            describeOutput()
        }
        if (!track.getTimestamp(timestamp)) {
            offSinceNs = 0
            return
        }
        val frames = timestamp.framePosition
        val ageNs = nowNs - timestamp.nanoTime
        val usable =
            // From since it last started, and recent: an older one says nothing about now
            timestamp.nanoTime >= playedAtNs &&
                ageNs in 0..MAX_TIMESTAMP_AGE_NS &&
                // The count started over, as it does after very long
                frames > 0 &&
                frames >= lastFrames
        lastFrames = frames
        if (!usable) {
            offSinceNs = 0
            return
        }
        val phoneUs = Util.sampleCountToDurationUs(frames, sampleRate) + ageNs / 1000
        val offUs = positionUs - phoneUs
        clock.checked = true
        clock.offUs = offUs
        if (abs(offUs) > abs(clock.worstOffUs)) clock.worstOffUs = offUs

        if (abs(offUs) < OFF_US) {
            offSinceNs = 0
            clock.stuckOffUs = 0
            return
        }
        if (offSinceNs == 0L) {
            offSinceNs = nowNs
            offAtStartUs = offUs
            return
        }
        val offForNs = nowNs - offSinceNs
        if (offForNs < OFF_FOR_NS) return
        // The player closes a gap by itself, a tenth of a second every second. Left to it while it
        // does at least half that; the next look starts from here
        val closedUs = abs(offAtStartUs) - abs(offUs)
        offSinceNs = nowNs
        offAtStartUs = offUs
        if (closedUs >= offForNs / 1000 / 20) return
        // Tunneled, the phone's hardware times the picture by the sound itself
        if (config.isTunneling) return
        if (corrections >= MAX_CORRECTIONS) {
            if (clock.stuckOffUs == 0L) {
                clock.log.add("Sound clock stays ${signedMs(offUs / 1000)} off the phone's timing")
            }
            clock.stuckOffUs = offUs
            return
        }
        if (correctedAtNs != 0L && nowNs - correctedAtNs < CORRECT_GAP_NS) return
        corrections++
        correctedAtNs = nowNs
        clock.corrections++
        clock.log.add(
            "Sound clock ${signedMs(offUs / 1000)} off the phone's timing, asked it afresh"
        )
        // Giving it the speed it already has changes nothing in the sound, and has the player drop
        // what it was counting on from and ask the phone where the sound is
        super.setPlaybackParameters(super.getPlaybackParameters())
        settledAtNs = nowNs + SETTLE_NS
        offSinceNs = 0
    }

    /** Where the sound goes, which says a lot about how well its timing can be known. */
    private fun describeOutput() {
        val described =
            listOfNotNull(
                    track.routedDevice?.let { deviceName(it.type) },
                    "$sampleRate Hz",
                    "${track.bufferSizeInFrames * 1000L / sampleRate} ms buffer",
                    "tunneled".takeIf { config.isTunneling },
                )
                .joinToString(", ")
        val before = clock.output
        if (described != before) {
            clock.output = described
            if (before != null) clock.log.add("Sound output now: $described")
        }
    }

    private companion object {
        const val NOT_PLAYING = Long.MAX_VALUE

        const val CHECK_EVERY_NS = 250_000_000L
        const val OUTPUT_EVERY_NS = 10_000_000_000L

        // After it starts playing, the player takes a moment to find the sound's place, and may
        // close a first gap for a second or two
        const val SETTLE_NS = 2_000_000_000L

        // The phone renews its answer every few hundredths of a second while sound comes out
        const val MAX_TIMESTAMP_AGE_NS = 500_000_000L

        // A gap this big shows as sound and picture out of step; this long, and it isn't a blip.
        // Smaller ones are left alone: asking afresh makes the clock hop, which is worse than them
        const val OFF_US = 60_000L
        const val OFF_FOR_NS = 1_500_000_000L

        const val CORRECT_GAP_NS = 5_000_000_000L
        const val MAX_CORRECTIONS = 3
    }
}

/**
 * When each frame of the picture was shown, as the player reports them on its own thread, for
 * working out how late they were against the player's clock.
 */
internal class PictureTiming : VideoFrameMetadataListener {
    // Which moment of the video each frame is, and when it was put on screen
    private val frameUs = LongArray(FRAMES)
    private val shownAtNs = LongArray(FRAMES)
    private val late = LongArray(FRAMES)
    private var next = 0
    private var filled = 0

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        // Without a time, it's shown right away
        val atNs = if (releaseTimeNs == C.TIME_UNSET) System.nanoTime() else releaseTimeNs
        synchronized(this) {
            frameUs[next] = presentationTimeUs
            shownAtNs[next] = atNs
            next = (next + 1) % FRAMES
            if (filled < FRAMES) filled++
        }
    }

    /**
     * How many milliseconds behind the clock the frames shown since [sinceNs] were, taking the
     * middle one of them, with the clock at [positionUs] at [nowNs] and running at [speed]. Below
     * zero when they were early. Null when too few were shown to tell.
     */
    @Synchronized
    fun lateMs(positionUs: Long, speed: Float, nowNs: Long, sinceNs: Long): Long? {
        var count = 0
        for (i in 0 until filled) {
            val atNs = shownAtNs[i]
            // Those set for a moment still to come are looked at next time
            if (atNs < sinceNs || atNs > nowNs) continue
            val clockUs = positionUs - ((nowNs - atNs) / 1000 * speed).toLong()
            late[count++] = clockUs - frameUs[i]
        }
        if (count < MIN_FRAMES) return null
        Arrays.sort(late, 0, count)
        return late[count / 2] / 1000
    }

    private companion object {
        // Over two seconds of frames, up to 120 a second
        const val FRAMES = 256
        const val MIN_FRAMES = 6
    }
}

/** A position in a video as it shows on the player's clock, such as 1:02:03. */
private fun clockTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds % 60)
    else "%d:%02d".format(Locale.ROOT, minutes, seconds % 60)
}

private fun signedMs(ms: Long): String = if (ms > 0) "+$ms ms" else "$ms ms"

private fun lateText(ms: Long): String = if (ms >= 0) "$ms ms late" else "${-ms} ms early"

private fun times(count: Int): String = if (count == 1) "once" else "$count times"

/** What kind of output the sound is going to, by Android's name for it. */
private fun deviceName(type: Int): String =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Wired"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Cast or recording"
        else -> "Output type $type"
    }
