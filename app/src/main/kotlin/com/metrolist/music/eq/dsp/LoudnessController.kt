/**
 * DSP chain ported from Stash (rawnaldclark/Stash, GPL-3.0).
 * LoudnessController: holds the current track's target gain, driven by MusicService.
 */
package com.metrolist.music.eq.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LoudnessState(
    val enabled: Boolean = false,
    val currentTrackGainDb: Float = 0f,
)

class LoudnessController {
    private val _state = MutableStateFlow(LoudnessState())
    val state: StateFlow<LoudnessState> = _state

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun setTrackGainDb(gainDb: Float) {
        _state.value = _state.value.copy(currentTrackGainDb = gainDb)
    }
}
