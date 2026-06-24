package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class CsvSessionWriterTest {

    // Verified byte-for-byte against the real PC CaptureWriter by
    // tools/make_golden_csv.py. Labels are floats (LASK5 calibrated [0,1]); the
    // 0.9942197 case proves Kotlin Double.toString matches Python str(float). The
    // CRLF line endings are intentional: the PC's csv module emits them.
    private val golden =
        "timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
        "1000,0,10,20,1,11,21,1.0,0.9942197\r\n" +
        "1020,100,110,120,101,111,121,0.0,0.5\r\n"

    @Test
    fun matchesPcGoldenOutput() {
        val sw = StringWriter()
        val w = CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2)
        w.writeRow(1000, intArrayOf(0, 10, 20, 1, 11, 21), listOf(1.0, 0.9942197))
        w.writeRow(1020, intArrayOf(100, 110, 120, 101, 111, 121), listOf(0.0, 0.5))
        assertEquals(golden, sw.toString())
    }

    @Test
    fun emptySessionStillWritesHeader() {
        val sw = StringWriter()
        CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2).close()
        assertEquals("timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n", sw.toString())
    }

    @Test
    fun infersLabelCountFromFirstRow() {
        val sw = StringWriter()
        val w = CsvSessionWriter(sw, rows = 1, cols = 1, labelCount = null)
        w.writeRow(5, intArrayOf(42), listOf(7.0, 8.0, 9.0))
        assertEquals("timestamp,R0C0,label_0,label_1,label_2\r\n5,42,7.0,8.0,9.0\r\n", sw.toString())
    }

    @Test
    fun writesArbitraryDoubleLabels() {
        // The writer must round-trip any double, including negatives, byte-for-byte
        // with Python str(float).
        val sw = StringWriter()
        val w = CsvSessionWriter(sw, rows = 1, cols = 1, labelCount = 2)
        w.writeRow(5, intArrayOf(42), listOf(-30.0, 0.125))
        assertEquals("timestamp,R0C0,label_0,label_1\r\n5,42,-30.0,0.125\r\n", sw.toString())
    }
}
