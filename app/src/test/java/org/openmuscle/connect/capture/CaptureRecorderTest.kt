package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
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
        values = vals.map { it.toDouble() },
        deviceTimestampMs = 0,
        receiveTimeMs = rt,
    )

    private fun recorder(sw: StringWriter, role: Role = Role.LEFT) =
        CaptureRecorder(CsvV2Writer(sw, rows = 2, cols = 3, labelCount = 2), role)

    @Test
    fun pairsExternalLabelsAndWritesV2Rows() {
        val sw = StringWriter()
        val rec = recorder(sw)
        rec.onLabel(label(1000, listOf(100, 200)))
        rec.onSensor(sensor(1010))
        assertEquals(1L, rec.matched)
        assertEquals(1L, rec.seen)
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
                "1010,left,flexgrid-01,0,10,20,1,11,21,100.0,200.0\r\n",
            sw.toString(),
        )
    }

    @Test
    fun dropsSensorWithNoLabel() {
        val sw = StringWriter()
        val rec = recorder(sw)
        rec.onSensor(sensor(5000))
        assertEquals(0L, rec.matched)
        assertEquals(1L, rec.seen)
        assertEquals("", sw.toString())   // header is lazy; nothing written
    }

    @Test
    fun manualLabelAppliesToEveryFrameWithRoleTag() {
        val sw = StringWriter()
        val rec = recorder(sw, role = Role.RIGHT)
        rec.manualLabel = listOf(7.0, 8.0)
        rec.onSensor(sensor(10))
        rec.onSensor(sensor(20))
        assertEquals(2L, rec.matched)
        assertEquals(
            "ts_hub_ms,role,device_id,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n" +
                "10,right,flexgrid-01,0,10,20,1,11,21,7.0,8.0\r\n" +
                "20,right,flexgrid-01,0,10,20,1,11,21,7.0,8.0\r\n",
            sw.toString(),
        )
    }
}
