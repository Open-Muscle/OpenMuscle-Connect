package org.openmuscle.connect.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmuscle.connect.domain.Role

class CaptureMetaTest {

    @Test
    fun encodesSidecarJson() {
        val meta = CaptureMeta(
            mirror = true,
            labelSource = "lask5",
            roles = mapOf("flexgrid-d7af0b" to Role.LEFT, "flexgrid-1524c3" to Role.RIGHT),
            createdMs = 1718000000000,
        )
        assertEquals(
            """{"schema":"v2","mirror":true,"label_source":"lask5",""" +
                """"roles":{"flexgrid-1524c3":"right","flexgrid-d7af0b":"left"},"created_ms":1718000000000}""",
            CaptureMetaCodec.encode(meta),
        )
    }

    @Test
    fun emptyRolesEncodes() {
        val meta = CaptureMeta(mirror = false, labelSource = "manual", roles = emptyMap(), createdMs = 1)
        assertEquals(
            """{"schema":"v2","mirror":false,"label_source":"manual","roles":{},"created_ms":1}""",
            CaptureMetaCodec.encode(meta),
        )
    }

    @Test
    fun sidecarNameStripsCsv() {
        assertEquals("capture_v2_123.meta.json", CaptureMetaCodec.sidecarName("capture_v2_123.csv"))
        assertEquals("capture_v2_123.meta.json", CaptureMetaCodec.sidecarName("capture_v2_123"))
    }
}
