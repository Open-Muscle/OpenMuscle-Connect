package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

/**
 * The phone half of the schema-v2 byte contract: these literals are byte-identical
 * to what tools/make_golden_csv_v2.py pins (the same bytes the PC CaptureWriter v2
 * must also reproduce). If either side changes, regenerate and update both.
 */
class CsvV2WriterTest {

    @Test
    fun matchesV2GoldenSingleSource() {
        val sw = StringWriter()
        val w = CsvV2Writer(sw, rows = 2, cols = 2, labelCount = 2)
        w.writeRow(1718000000000, "left", "fg-left", intArrayOf(12, 18, 20, 25), listOf(1.0, 0.5))
        w.writeRow(1718000000033, "left", "fg-left", intArrayOf(13, 19, 21, 24), listOf(0.8, 0.5))
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n" +
                "1718000000000,left,fg-left,12,18,20,25,1.0,0.5\r\n" +
                "1718000000033,left,fg-left,13,19,21,24,0.8,0.5\r\n",
            sw.toString(),
        )
    }

    @Test
    fun matchesV2GoldenBilateral() {
        // Two sources interleaved by arrival; the role column disambiguates, so the
        // feature columns stay R{r}C{c} on every row.
        val sw = StringWriter()
        val w = CsvV2Writer(sw, rows = 2, cols = 2, labelCount = 2)
        w.writeRow(1718000000000, "left", "fg-left", intArrayOf(12, 18, 20, 25), listOf(1.0, 0.5))
        w.writeRow(1718000000007, "right", "fg-right", intArrayOf(30, 28, 22, 19), listOf(1.0, 0.5))
        w.writeRow(1718000000033, "left", "fg-left", intArrayOf(13, 19, 21, 24), listOf(0.8, 0.5))
        w.writeRow(1718000000040, "right", "fg-right", intArrayOf(31, 27, 23, 18), listOf(0.8, 0.5))
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n" +
                "1718000000000,left,fg-left,12,18,20,25,1.0,0.5\r\n" +
                "1718000000007,right,fg-right,30,28,22,19,1.0,0.5\r\n" +
                "1718000000033,left,fg-left,13,19,21,24,0.8,0.5\r\n" +
                "1718000000040,right,fg-right,31,27,23,18,0.8,0.5\r\n",
            sw.toString(),
        )
    }

    @Test
    fun emptySessionStillWritesHeader() {
        val sw = StringWriter()
        CsvV2Writer(sw, rows = 2, cols = 2, labelCount = 2).close()
        assertEquals("ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n", sw.toString())
    }

    @Test
    fun infersLabelCountFromFirstRow() {
        val sw = StringWriter()
        val w = CsvV2Writer(sw, rows = 1, cols = 1, labelCount = null)
        w.writeRow(5, "labeler", "lask5-01", intArrayOf(42), listOf(0.25, 0.5, 0.75))
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,label_0,label_1,label_2\r\n5,labeler,lask5-01,42,0.25,0.5,0.75\r\n",
            sw.toString(),
        )
    }
}
