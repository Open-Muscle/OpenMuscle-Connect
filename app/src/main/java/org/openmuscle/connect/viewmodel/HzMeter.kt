package org.openmuscle.connect.viewmodel

/**
 * Sliding-window frame-rate estimator. Feed it receive timestamps (ms) and it
 * reports the rate over the last [windowMs]. Pure and deterministic, so it is
 * unit-testable (see HzMeterTest); the ViewModels use it for the live Hz readout.
 */
class HzMeter(private val windowMs: Long = 1000L) {

    private val times = ArrayDeque<Long>()

    /** Record a timestamp and return the current rate in Hz (0 until 2 samples). */
    fun record(timeMs: Long): Float {
        times.addLast(timeMs)
        while (times.size > 1 && timeMs - times.first() > windowMs) {
            times.removeFirst()
        }
        if (times.size < 2) return 0f
        val span = (times.last() - times.first()).coerceAtLeast(1)
        return (times.size - 1) * 1000f / span
    }

    fun reset() = times.clear()
}
