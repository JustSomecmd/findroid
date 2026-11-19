package dev.jdtech.jellyfin.tubearchivist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The TubeArchivist video the player has open, such as in a small window, so the app's pages can
 * mark it where it shows up.
 */
object TaNowPlaying {
    data class State(val videoId: String, val playing: Boolean)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The video now open in the player, or null once there's none. */
    fun set(videoId: String?, playing: Boolean) {
        _state.value = videoId?.let { State(it, playing) }
    }

    fun setPlaying(playing: Boolean) {
        _state.update { it?.copy(playing = playing) }
    }
}
