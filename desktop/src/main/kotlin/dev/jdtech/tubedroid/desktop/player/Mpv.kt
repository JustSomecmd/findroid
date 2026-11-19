package dev.jdtech.tubedroid.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * The parts of libmpv's C API the player uses. Jellyfin Media Player plays through the same
 * library, embedded in its window the same way.
 */
@Suppress("FunctionName")
internal interface MpvLib : Library {
    fun mpv_create(): Pointer?

    fun mpv_initialize(handle: Pointer): Int

    fun mpv_set_option_string(handle: Pointer, name: String, data: String): Int

    fun mpv_set_property_string(handle: Pointer, name: String, data: String): Int

    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?

    fun mpv_free(data: Pointer)

    fun mpv_command(handle: Pointer, args: Array<String?>): Int

    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer?

    fun mpv_wakeup(handle: Pointer)

    fun mpv_terminate_destroy(handle: Pointer)

    fun mpv_error_string(error: Int): String?
}

/** Loads libmpv-2.dll from the app's folder, once. */
internal object MpvLoader {
    private var loaded: MpvLib? = null

    var loadError: String? = null
        private set

    @Synchronized
    fun load(): MpvLib? {
        loaded?.let {
            return it
        }
        val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let {
                add(File(it, "libmpv-2.dll").path)
            }
            add("libmpv-2")
            add("mpv-2")
            add("mpv")
        }
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            if (candidate.endsWith(".dll") && !File(candidate).exists()) continue
            try {
                val lib = Native.load(candidate, MpvLib::class.java, options)
                loaded = lib
                loadError = null
                return lib
            } catch (e: UnsatisfiedLinkError) {
                errors += e.message.orEmpty()
            }
        }
        loadError =
            "Couldn't load mpv (libmpv-2.dll), which plays the videos. Reinstalling Winchivist should bring it back."
        System.err.println("libmpv: " + errors.joinToString(" | "))
        return null
    }
}

/** What mpv says happened, from its event queue. */
internal sealed interface MpvEvent {
    data object FileLoaded : MpvEvent

    data class LoadFailed(val message: String) : MpvEvent

    /** mpv was told to quit, such as with its q key. */
    data object Shutdown : MpvEvent
}

/**
 * One mpv player, drawing into the native window [wid]. Commands and properties are safe to use
 * from any thread; events arrive on a thread of their own and go to [onEvent].
 */
internal class MpvPlayer
private constructor(
    private val lib: MpvLib,
    private val handle: Pointer,
    private val onEvent: (MpvEvent) -> Unit,
) {
    @Volatile private var destroyed = false
    private val events =
        Thread({ eventLoop() }, "mpv-events").apply {
            isDaemon = true
            start()
        }

    fun command(vararg args: String): Int {
        if (destroyed) return -1
        return lib.mpv_command(handle, arrayOf(*args, null))
    }

    fun get(name: String): String? {
        if (destroyed) return null
        val pointer = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            pointer.getString(0, "UTF-8")
        } finally {
            lib.mpv_free(pointer)
        }
    }

    fun set(name: String, value: String): Int {
        if (destroyed) return -1
        return lib.mpv_set_property_string(handle, name, value)
    }

    fun getDouble(name: String): Double? = get(name)?.toDoubleOrNull()

    fun getFlag(name: String): Boolean = get(name) == "yes"

    /** Stops playing and frees everything. Call before the window it draws into goes away. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        lib.mpv_wakeup(handle)
        events.join(2000)
        lib.mpv_terminate_destroy(handle)
    }

    private fun eventLoop() {
        while (!destroyed) {
            val event = lib.mpv_wait_event(handle, 0.5) ?: continue
            if (destroyed) break
            when (event.getInt(0)) {
                EVENT_SHUTDOWN -> {
                    onEvent(MpvEvent.Shutdown)
                    return
                }
                EVENT_FILE_LOADED -> onEvent(MpvEvent.FileLoaded)
                EVENT_END_FILE -> {
                    val data = event.getPointer(16) ?: continue
                    if (data.getInt(0) == END_FILE_REASON_ERROR) {
                        val error = data.getInt(4)
                        onEvent(MpvEvent.LoadFailed(lib.mpv_error_string(error) ?: "error $error"))
                    }
                }
            }
        }
    }

    companion object {
        // From mpv's client.h
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_END_FILE = 7
        private const val EVENT_FILE_LOADED = 8
        private const val END_FILE_REASON_ERROR = 4

        /**
         * Starts mpv inside the native window [wid] with [options], which are set before it starts,
         * as on its command line.
         */
        fun create(
            lib: MpvLib,
            wid: Long,
            options: List<Pair<String, String>>,
            onEvent: (MpvEvent) -> Unit,
        ): MpvPlayer {
            val handle = lib.mpv_create() ?: error("mpv couldn't start")
            lib.mpv_set_option_string(handle, "wid", wid.toString())
            for ((name, value) in options) {
                val result = lib.mpv_set_option_string(handle, name, value)
                if (result < 0)
                    System.err.println("mpv option $name: ${lib.mpv_error_string(result)}")
            }
            val result = lib.mpv_initialize(handle)
            if (result < 0) {
                lib.mpv_terminate_destroy(handle)
                error("mpv couldn't start: ${lib.mpv_error_string(result)}")
            }
            return MpvPlayer(lib, handle, onEvent)
        }
    }
}
