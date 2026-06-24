package org.openmuscle.connect.capture

import org.openmuscle.connect.domain.LabelFrame
import kotlin.math.abs

/**
 * Pairs a sensor frame with the nearest label frame within a time window,
 * matched on phone receive time (device clocks are not shared; see
 * docs/WIRE-FORMAT.md section 3). Port of the PC TemporalMatcher
 * (receiver/matcher.py); equivalence is checked in tools/wireformat_check.py.
 *
 * Not thread-safe; drive it from a single coroutine.
 */
class TemporalMatcher(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val labels = ArrayDeque<LabelFrame>()

    var unpairedCount: Long = 0
        private set

    fun addLabel(label: LabelFrame) {
        labels.addLast(label)
    }

    /**
     * Returns the nearest label within [windowMs] of [receiveTimeMs], or null if
     * none is in window (and increments [unpairedCount]). Prunes labels older
     * than the window first, so the buffer stays bounded.
     */
    fun match(receiveTimeMs: Long): LabelFrame? {
        while (labels.isNotEmpty() && labels.first().receiveTimeMs < receiveTimeMs - windowMs) {
            labels.removeFirst()
        }
        var best: LabelFrame? = null
        var bestGap = windowMs + 1
        for (label in labels) {
            val gap = abs(receiveTimeMs - label.receiveTimeMs)
            if (gap < bestGap) {
                bestGap = gap
                best = label
            }
        }
        if (bestGap > windowMs) {
            unpairedCount++
            return null
        }
        return best
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 100L
    }
}
