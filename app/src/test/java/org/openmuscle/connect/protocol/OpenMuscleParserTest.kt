package org.openmuscle.connect.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMuscleParserTest {

    private fun matrixJson(): String =
        (0 until 15).joinToString(",", "[", "]") { c ->
            (0 until 4).joinToString(",", "[", "]") { r -> (c * 100 + r).toString() }
        }

    @Test
    fun parsesFlexgridV1() {
        val json = """{"v":"1.0","type":"flexgrid","id":"flexgrid-a3f9c1","ts":12345,""" +
            """"data":{"matrix":${matrixJson()},"rows":4,"cols":15},""" +
            """"meta":{"pct":95,"rssi":-65,"vbat":4.15}}"""
        val p = OpenMuscleParser.parse(json, 1000L)
        assertTrue(p is ParsedPacket.Sensor)
        val f = (p as ParsedPacket.Sensor).frame
        assertEquals("flexgrid-a3f9c1", f.deviceId)
        assertEquals(4, f.rows)
        assertEquals(15, f.cols)
        assertEquals(12345L, f.deviceTimestampMs)
        assertEquals(1000L, f.receiveTimeMs)
        assertEquals(100, f.matrix[1][0])   // column-major storage preserved
        assertEquals(95, f.status?.batteryPct)
        assertEquals(-65, f.status?.rssi)
        assertEquals(4.15, f.status?.vbat!!, 1e-9)
    }

    @Test
    fun parsesResetCauseFromStatusMeta() {
        val json = """{"v":"1.0","type":"flexgrid","id":"flexgrid-d7af0b","ts":7,""" +
            """"data":{"matrix":${matrixJson()},"rows":4,"cols":15},""" +
            """"meta":{"pct":57,"rssi":-55,"reset_cause":3,"reset_cause_name":"wdt"}}"""
        val f = (OpenMuscleParser.parse(json, 0L) as ParsedPacket.Sensor).frame
        assertEquals(3, f.status?.resetCause)
        assertEquals("wdt", f.status?.resetCauseName)
    }

    @Test
    fun parsesImuFromStatusMeta() {
        val json = """{"v":"1.0","type":"flexgrid","id":"flexgrid-d7af0b","ts":7,""" +
            """"data":{"matrix":${matrixJson()},"rows":4,"cols":15},""" +
            """"meta":{"imu":{"variant":"tokmas","accel":[10,-20,16000],"gyro":[1,2,-3],"temp_c":31.5}}}"""
        val imu = (OpenMuscleParser.parse(json, 0L) as ParsedPacket.Sensor).frame.status?.imu!!
        assertEquals("tokmas", imu.variant)
        assertEquals(listOf(10, -20, 16000), imu.accel)
        assertEquals(listOf(1, 2, -3), imu.gyro)
        assertEquals(31.5, imu.tempC!!, 1e-9)
    }

    @Test
    fun parsesLask5() {
        val json = """{"v":"1.0","type":"lask5","id":"lask5-01","ts":42,""" +
            """"data":{"values":[1.0,0.5,0.9942197,0],"joystick":{"x":2048,"y":1900}}}"""
        val p = OpenMuscleParser.parse(json, 5L)
        assertTrue(p is ParsedPacket.Label)
        val l = (p as ParsedPacket.Label).frame
        // Calibrated floats preserved; an int-encoded JSON value (0) parses to 0.0.
        assertEquals(listOf(1.0, 0.5, 0.9942197, 0.0), l.values)
        assertEquals(2048, l.joystickX)
        assertEquals(1900, l.joystickY)
    }

    @Test
    fun ignoresLegacyAndGarbage() {
        // Legacy bare array (no "v"), non-JSON, and v1.0-without-"v" are all ignored.
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse("[[1,2,3,4]]", 0))
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse("not json at all", 0))
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse("""{"type":"flexgrid"}""", 0))
    }

    @Test
    fun rejectsIncompatibleMajorVersion() {
        // 10.7 / PROTOCOL.md 4: a major-version bump is incompatible and must be rejected;
        // a 0.x legacy version too.
        val v2 = """{"v":"2.0","type":"flexgrid","id":"x","ts":1,"data":{"matrix":[[1,2,3,4]]}}"""
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse(v2, 0))
        val v0 = """{"v":"0.9","type":"flexgrid","id":"x","ts":1,"data":{"matrix":[[1,2,3,4]]}}"""
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse(v0, 0))
        // A minor bump (1.x) stays compatible (additive per PROTOCOL.md 4).
        val v17 = """{"v":"1.7","type":"flexgrid","id":"x","ts":1,"data":{"matrix":[[1,2,3,4]]}}"""
        assertTrue(OpenMuscleParser.parse(v17, 0) is ParsedPacket.Sensor)
    }

    @Test
    fun ignoresUnknownType() {
        val json = """{"v":"1.0","type":"quest_hand","id":"q","ts":1,"data":{}}"""
        assertEquals(ParsedPacket.Ignored, OpenMuscleParser.parse(json, 0))
    }

    @Test
    fun parsesV3AnnounceBeaconTopLevelPort() {
        // Legacy V3-style beacon: ports at the top level, no `services` map.
        val json = """{"v":"1.0","type":"announce","id":"flexgrid-a3f9c1","dev":"flexgrid",""" +
            """"fw":"0.1.7","transports":["wifi"],"caps":["sensor","status"],""" +
            """"matrix":[15,4],"port":3141}"""
        val p = OpenMuscleParser.parse(json, 0)
        assertTrue(p is ParsedPacket.Announce)
        val a = p as ParsedPacket.Announce
        assertEquals("flexgrid-a3f9c1", a.deviceId)
        assertEquals("flexgrid", a.deviceType)
        assertEquals("0.1.7", a.firmware)
        assertEquals(listOf("wifi"), a.transports)
        assertEquals(3141, a.sensorPort)
        assertEquals(null, a.cmdPort)
        assertEquals(null, a.host)   // filled by the receiver, not the parser
    }

    @Test
    fun parsesV4AnnounceBeaconServicesMap() {
        // V4 beacon (FlexGridV4 discovery.py): ports under `services`.
        val json = """{"v":"1.0","type":"announce","id":"flexgrid-v3-02","role":"source",""" +
            """"dev":"flexgrid","fw":"v4.0.0","transports":["wifi"],""" +
            """"caps":["sensor","status","cmd","imu"],"matrix":[15,4],""" +
            """"services":{"sensor":3141,"cmd":8001},"ts":12345}"""
        val p = OpenMuscleParser.parse(json, 0)
        assertTrue(p is ParsedPacket.Announce)
        val a = p as ParsedPacket.Announce
        assertEquals("flexgrid-v3-02", a.deviceId)
        assertEquals("flexgrid", a.deviceType)
        assertEquals("v4.0.0", a.firmware)
        assertEquals(3141, a.sensorPort)
        assertEquals(8001, a.cmdPort)
        assertEquals(null, a.host)
    }

    @Test
    fun parses16ColLegacyDimensions() {
        val matrix = (0 until 16).joinToString(",", "[", "]") { c ->
            (0 until 4).joinToString(",", "[", "]") { _ -> c.toString() }
        }
        val json = """{"v":"1.0","type":"flexgrid","id":"fg","ts":1,"data":{"matrix":$matrix}}"""
        val p = OpenMuscleParser.parse(json, 0)
        assertTrue(p is ParsedPacket.Sensor)
        val f = (p as ParsedPacket.Sensor).frame
        assertEquals(16, f.cols)
        assertEquals(4, f.rows)
    }

    @Test
    fun handlesFloatAdcValues() {
        val json = """{"v":"1.0","type":"flexgrid","id":"fg","ts":1,"data":{"matrix":[[100.0,200.0,300.0,400.0]]}}"""
        val f = (OpenMuscleParser.parse(json, 0) as ParsedPacket.Sensor).frame
        assertEquals(100, f.matrix[0][0])
        assertEquals(400, f.matrix[0][3])
    }

    @Test
    fun parsesNegativeLaskValues() {
        // Calibrated LASK5 values are [0,1], but the parser must still round-trip
        // arbitrary (incl. negative, incl. int-encoded) values as floats.
        val json = """{"v":"1.0","type":"lask5","id":"l","ts":1,"data":{"values":[-30,-35,-30,-37]}}"""
        val l = (OpenMuscleParser.parse(json, 0) as ParsedPacket.Label).frame
        assertEquals(listOf(-30.0, -35.0, -30.0, -37.0), l.values)
    }

    @Test
    fun flexgridWithoutMetaHasNullStatus() {
        val json = """{"v":"1.0","type":"flexgrid","id":"fg","ts":1,"data":{"matrix":[[1,2,3,4]]}}"""
        val f = (OpenMuscleParser.parse(json, 0) as ParsedPacket.Sensor).frame
        assertEquals(null, f.status)
    }

    @Test
    fun ignoresMalformedFlexgrid() {
        assertEquals(
            ParsedPacket.Ignored,
            OpenMuscleParser.parse("""{"v":"1.0","type":"flexgrid","id":"x","data":{}}""", 0),
        )
        assertEquals(
            ParsedPacket.Ignored,
            OpenMuscleParser.parse("""{"v":"1.0","type":"flexgrid","id":"x","data":{"matrix":[]}}""", 0),
        )
    }
}
