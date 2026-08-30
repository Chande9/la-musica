/**
 * DSP chain ported from Stash (rawnaldclark/Stash, GPL-3.0) — see VAULT/05-CANIBALIZACION/stash-dsp-30ago2026.md
 * SlidingWindowMax.kt
 */
package com.metrolist.music.eq.dsp

/**
 * Monotonic-deque sliding window maximum over Short sample magnitudes.
 * O(1) amortized per sample; window size fixed at construction.
 */
class SlidingWindowMax(private val window: Int) {
    private val values = IntArray(window)
    private val indices = IntArray(window)
    private var head = 0
    private var tail = 0
    private var count = 0
    private var index = 0

    fun push(value: Int) {
        while (count > 0) {
            val t = indices[tail]
            if (t + window <= index || kotlin.math.abs(values[tail]) <= kotlin.math.abs(value)) break
            // pop back
            tail = (tail + window - 1) % window
            count--
        }
        values[tail] = value.coerceIn(-65535, 65535)
        indices[tail] = index
        tail = (tail + 1) % window
        count++
        index++
        // drop expired front
        while (count > 0 && indices[head] + window <= index) {
            head = (head + 1) % window
            count--
        }
    }

    fun max(): Int {
        if (count == 0) return 0
        return values[head]
    }

    fun reset() {
        head = 0; tail = 0; count = 0; index = 0
    }
}
