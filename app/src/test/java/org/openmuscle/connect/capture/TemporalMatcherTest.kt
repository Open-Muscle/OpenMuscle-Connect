package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openmuscle.connect.domain.LabelFrame

class TemporalMatcherTest {

    private fun label(rt: Long, vals: List<Int>) =
        LabelFrame(
            deviceId = "lask5-01",
            deviceType = "lask5",
            values = vals.map { it.toDouble() },   // label values are floats now
            deviceTimestampMs = 0,
            receiveTimeMs = rt,
        )

    @Test
    fun matchesNearestWithinWindow() {
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(1, 1, 1, 1)))   // 50 ms before
        m.addLabel(label(1080, listOf(2, 2, 2, 2)))   // 30 ms after -> closer
        assertEquals(listOf(2.0, 2.0, 2.0, 2.0), m.match(1050)?.values)
    }

    @Test
    fun dropsWhenNoLabelInWindow() {
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(9, 9, 9, 9)))
        assertNull(m.match(2000))
        assertEquals(1L, m.unpairedCount)
    }

    @Test
    fun prunesOldLabelsThenMatchesFreshOne() {
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(1, 1, 1, 1)))
        assertNull(m.match(2000))   // old label pruned, unpaired
        m.addLabel(label(2010, listOf(5, 5, 5, 5)))
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0), m.match(2020)?.values)
    }

    // Boundary cases. 99 ms in / 101 ms out are cross-verified against the PC
    // matcher in tools/wireformat_check.py.
    @Test
    fun matchesClearlyInsideWindow() {
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(3, 3, 3, 3)))
        assertNotNull(m.match(1099))
    }

    @Test
    fun dropsClearlyOutsideWindow() {
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(4, 4, 4, 4)))
        assertNull(m.match(1101))
    }

    @Test
    fun exactBoundaryMatchesWithIntegerMs() {
        // gap == window matches in integer-ms math (deterministic). The PC uses
        // float seconds, where the exact tick is sub-microsecond fuzzy; this
        // never matters for real receive times.
        val m = TemporalMatcher(windowMs = 100)
        m.addLabel(label(1000, listOf(5, 5, 5, 5)))
        assertNotNull(m.match(1100))
    }
}
