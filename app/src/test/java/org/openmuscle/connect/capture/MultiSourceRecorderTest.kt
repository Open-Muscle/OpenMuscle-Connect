package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.SensorFrame
import java.io.StringWriter

class MultiSourceRecorderTest {

    private val roles = mapOf("fg-left" to Role.LEFT, "fg-right" to Role.RIGHT, "lask5-01" to Role.LABELER)

    private fun sensor(id: String, rt: Long, matrix: List<List<Int>>) = SensorFrame(
        deviceId = id, deviceType = "flexgrid", rows = matrix[0].size, cols = matrix.size,
        matrix = matrix, deviceTimestampMs = 0, seq = null, receiveTimeMs = rt, status = null,
    )

    private fun label(id: String, rt: Long, vals: List<Double>) = LabelFrame(
        deviceId = id, deviceType = "lask5", values = vals, deviceTimestampMs = 0, receiveTimeMs = rt,
    )

    private fun recorder(sw: StringWriter) =
        MultiSourceRecorder(CsvV2Writer(sw, rows = 2, cols = 2, labelCount = 2), roleOf = { roles[it] })

    @Test
    fun writesRoleTaggedRowsForEachSource() {
        val sw = StringWriter()
        val rec = recorder(sw)
        rec.onLabel(label("lask5-01", 1000, listOf(1.0, 0.5)))
        rec.onSensor(sensor("fg-left", 1010, listOf(listOf(10, 30), listOf(20, 40))))
        rec.onSensor(sensor("fg-right", 1015, listOf(listOf(11, 31), listOf(21, 41))))
        assertEquals(2L, rec.matched)
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n" +
                "1010,left,fg-left,10,20,30,40,1.0,0.5\r\n" +
                "1015,right,fg-right,11,21,31,41,1.0,0.5\r\n",
            sw.toString(),
        )
    }

    @Test
    fun ignoresUntaggedDevice() {
        val sw = StringWriter()
        val rec = recorder(sw)
        rec.onLabel(label("lask5-01", 1000, listOf(1.0, 0.5)))
        rec.onSensor(sensor("fg-unknown", 1010, listOf(listOf(1, 2), listOf(3, 4))))
        assertEquals(0L, rec.seen)
        assertEquals(0L, rec.matched)
        assertEquals("", sw.toString())   // header is lazy; nothing written
    }

    @Test
    fun ignoresSensorFrameFromLabelerRole() {
        val sw = StringWriter()
        val rec = recorder(sw)
        // A flexgrid-shaped frame from a device tagged labeler is not a feature source.
        rec.onSensor(sensor("lask5-01", 1010, listOf(listOf(1, 2), listOf(3, 4))))
        assertEquals(0L, rec.seen)
        assertEquals("", sw.toString())
    }

    @Test
    fun skipsSensorWithNoMatchedLabel() {
        val sw = StringWriter()
        val rec = recorder(sw)
        rec.onSensor(sensor("fg-left", 1010, listOf(listOf(10, 30), listOf(20, 40))))   // no label yet
        assertEquals(1L, rec.seen)
        assertEquals(0L, rec.matched)
        assertEquals("", sw.toString())
    }
}
