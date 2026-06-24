package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class CsvSessionWriterTest {

    // Verified byte-for-byte against the real PC CaptureWriter by
    // tools/make_golden_csv.py. The CRLF line endings are intentional: the PC's
    // csv module emits them, so the phone must too.
    private val golden =
        "timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
        "1000,0,10,20,1,11,21,100,200\r\n" +
        "1020,100,110,120,101,111,121,105,205\r\n"

    @Test
    fun matchesPcGoldenOutput() {
        val sw = StringWriter()
        val w = CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2)
        w.writeRow(1000, intArrayOf(0, 10, 20, 1, 11, 21), listOf(100, 200))
        w.writeRow(1020, intArrayOf(100, 110, 120, 101, 111, 121), listOf(105, 205))
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
        w.writeRow(5, intArrayOf(42), listOf(7, 8, 9))
        assertEquals("timestamp,R0C0,label_0,label_1,label_2\r\n5,42,7,8,9\r\n", sw.toString())
    }

    @Test
    fun writesNegativeLabels() {
        // Legacy LASK5 piston values can be negative; they must round-trip as-is.
        val sw = StringWriter()
        val w = CsvSessionWriter(sw, rows = 1, cols = 1, labelCount = 2)
        w.writeRow(5, intArrayOf(42), listOf(-30, -35))
        assertEquals("timestamp,R0C0,label_0,label_1\r\n5,42,-30,-35\r\n", sw.toString())
    }
}
