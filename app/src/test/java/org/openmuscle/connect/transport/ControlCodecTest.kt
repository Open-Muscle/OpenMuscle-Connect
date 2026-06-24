package org.openmuscle.connect.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These literals are byte-identical to CANONICAL_REQUESTS in tools/cmd_server.py,
 * whose --selftest proves the V4-shaped reference server accepts them, and they
 * match what tools/v4_probe.py sends to a real device. If you change either side,
 * regenerate and update both.
 */
class ControlCodecTest {

    @Test
    fun encodesGetInfo() {
        assertEquals(
            """{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":1,"data":{"verb":"get_info"}}""",
            ControlCodec.encode("flexgrid-a3f9c1", 1, Command.GetInfo),
        )
    }

    @Test
    fun encodesSetScanRate() {
        assertEquals(
            """{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":42,"data":{"verb":"set_scan_rate","interval_ms":17}}""",
            ControlCodec.encode("flexgrid-a3f9c1", 42, Command.SetScanRate(17)),
        )
    }

    @Test
    fun encodesSubscribe() {
        assertEquals(
            """{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":2,"data":{"verb":"subscribe","port":3141,"transport":"wifi","hub_id":"hub-1"}}""",
            ControlCodec.encode("flexgrid-a3f9c1", 2, Command.Subscribe(port = 3141, hubId = "hub-1")),
        )
    }

    @Test
    fun encodesHeartbeat() {
        assertEquals(
            """{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":3,"data":{"verb":"heartbeat","port":3141,"transport":"wifi"}}""",
            ControlCodec.encode("flexgrid-a3f9c1", 3, Command.Heartbeat(port = 3141)),
        )
    }

    @Test
    fun parsesOkAck() {
        val ack = ControlCodec.parseAckLine(
            """{"v":"1.0","type":"ack","status":"ok","msg_id":42,"data":{"verb":"set_scan_rate","interval_ms":17}}""",
        )
        assertNotNull(ack)
        assertTrue(ack!!.ok)
        assertEquals(42, ack.msgId)
        assertEquals("set_scan_rate", ack.verb)
    }

    @Test
    fun parsesErrorAck() {
        val ack = ControlCodec.parseAckLine(
            """{"v":"1.0","type":"ack","status":"error","msg_id":5,"data":{"verb":"set_scan_rate","message":"interval_ms out of range (5..2000): 3"}}""",
        )
        assertNotNull(ack)
        assertFalse(ack!!.ok)
        assertEquals(5, ack.msgId)
        assertEquals("interval_ms out of range (5..2000): 3", ack.message)
    }

    @Test
    fun parsesGetInfoAckIntoDeviceInfo() {
        val ack = ControlCodec.parseAckLine(
            """{"v":"1.0","type":"ack","status":"ok","msg_id":1,"data":{"verb":"get_info",""" +
                """"id":"flexgrid-v3-02","dev":"flexgrid","fw":"v4.0.0","matrix":[15,4],""" +
                """"caps":["sensor","status","cmd","imu"],"subscribers":[{"host":"10.0.0.50","port":3141}]}}""",
        )
        assertNotNull(ack)
        assertTrue(ack!!.ok)
        val info = ControlCodec.parseInfo(ack.data!!)
        assertEquals("flexgrid-v3-02", info.deviceId)
        assertEquals("flexgrid", info.deviceType)
        assertEquals("v4.0.0", info.firmware)
        assertEquals(15, info.cols)
        assertEquals(4, info.rows)
        assertEquals(listOf("sensor", "status", "cmd", "imu"), info.caps)
        assertEquals(1, info.subscriberCount)
    }

    @Test
    fun ignoresNonAckLine() {
        assertEquals(null, ControlCodec.parseAckLine("""{"v":"1.0","type":"flexgrid","id":"x"}"""))
        assertEquals(null, ControlCodec.parseAckLine("not json"))
    }
}
