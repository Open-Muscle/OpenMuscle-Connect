package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame
import java.io.StringWriter

class CaptureRecorderTest {

    private fun sensor(rt: Long) = SensorFrame(
        deviceId = "flexgrid-01",
        deviceType = "flexgrid",
        rows = 2,
        cols = 3,
        matrix = listOf(listOf(0, 1), listOf(10, 11), listOf(20, 21)),
        deviceTimestampMs = 0,
        seq = null,
        receiveTimeMs = rt,
        status = null,
    )

    private fun label(rt: Long, vals: List<Int>) = LabelFrame(
        deviceId = "lask5-01",
        deviceType = "lask5",
        values = vals,
        deviceTimestampMs = 0,
        receiveTimeMs = rt,
    )

    @Test
    fun pairsExternalLabelsAndWritesRows() {
        val sw = StringWriter()
        val rec = CaptureRecorder(CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2))
        rec.onLabel(label(1000, listOf(100, 200)))
        rec.onSensor(sensor(1010))
        assertEquals(1L, rec.matched)
        assertEquals(1L, rec.seen)
        assertEquals(
            "timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
                "1010,0,10,20,1,11,21,100,200\r\n",
            sw.toString(),
        )
    }

    @Test
    fun dropsSensorWithNoLabel() {
        val sw = StringWriter()
        val rec = CaptureRecorder(CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2))
        rec.onSensor(sensor(5000))
        assertEquals(0L, rec.matched)
        assertEquals(1L, rec.seen)
        assertEquals("", sw.toString())   // header is lazy; nothing written
    }

    @Test
    fun manualLabelAppliesToEveryFrame() {
        val sw = StringWriter()
        val rec = CaptureRecorder(CsvSessionWriter(sw, rows = 2, cols = 3, labelCount = 2))
        rec.manualLabel = listOf(7, 8)
        rec.onSensor(sensor(10))
        rec.onSensor(sensor(20))
        assertEquals(2L, rec.matched)
        assertEquals(
            "timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
                "10,0,10,20,1,11,21,7,8\r\n" +
                "20,0,10,20,1,11,21,7,8\r\n",
            sw.toString(),
        )
    }
}
