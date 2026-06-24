package org.openmuscle.connect.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class HzMeterTest {

    @Test
    fun zeroForFirstSample() {
        assertEquals(0f, HzMeter().record(1000), 0f)
    }

    @Test
    fun computesRateOverWindow() {
        val m = HzMeter(windowMs = 1000)
        // 11 samples 100 ms apart span 1000 ms -> 10 intervals / 1 s = 10 Hz.
        var hz = 0f
        for (i in 0..10) hz = m.record(1000L + i * 100)
        assertEquals(10f, hz, 0.001f)
    }

    @Test
    fun prunesSamplesOutsideWindow() {
        val m = HzMeter(windowMs = 1000)
        m.record(0)
        m.record(5000)              // old sample pruned, single sample -> 0
        val hz = m.record(5100)     // two samples 100 ms apart -> 10 Hz
        assertEquals(10f, hz, 0.001f)
    }

    @Test
    fun resetClears() {
        val m = HzMeter()
        m.record(1000)
        m.record(1100)
        m.reset()
        assertEquals(0f, m.record(2000), 0f)
    }
}
