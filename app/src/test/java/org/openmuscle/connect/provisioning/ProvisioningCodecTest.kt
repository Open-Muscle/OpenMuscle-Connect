package org.openmuscle.connect.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningCodecTest {

    @Test
    fun provisionRequestOmitsHubHost() {
        // hub_host is intentionally absent (the phone cannot know its home IP during the AP session).
        assertEquals(
            """{"ssid":"MyHomeWiFi","password":"hunter2hunter2"}""",
            ProvisioningCodec.provisionRequest("MyHomeWiFi", "hunter2hunter2"),
        )
    }

    @Test
    fun parsesInfo() {
        val info = ProvisioningCodec.parseInfo(
            """{"v":"1.0","type":"info","id":"flexgrid-d7af0b","dev":"flexgrid","fw":"v4.0.0",""" +
                """"caps":["sensor","status","cmd","imu"],"matrix":[15,4],"state":"unprovisioned"}""",
        )
        assertEquals("flexgrid-d7af0b", info!!.id)
        assertEquals("flexgrid", info.dev)
        assertEquals("v4.0.0", info.fw)
        assertEquals("unprovisioned", info.state)
        assertEquals(listOf("sensor", "status", "cmd", "imu"), info.caps)
        assertEquals(listOf(15, 4), info.matrix)
    }

    @Test
    fun parsesProvisionOk() {
        val r = ProvisioningCodec.parseProvisionAck(
            """{"v":"1.0","type":"provision_ack","status":"ok","id":"flexgrid-d7af0b",""" +
                """"next":"switching_to_sta","reboot_in_ms":1500}""",
        )
        assertTrue(r is ProvisionResult.Ok)
        r as ProvisionResult.Ok
        assertEquals("flexgrid-d7af0b", r.id)
        assertEquals(1500L, r.rebootInMs)
    }

    @Test
    fun parsesProvisionError() {
        val r = ProvisioningCodec.parseProvisionAck(
            """{"v":"1.0","type":"provision_ack","status":"error","data":{"message":"ssid_too_long"}}""",
        )
        assertTrue(r is ProvisionResult.Error)
        assertEquals("ssid_too_long", (r as ProvisionResult.Error).message)
    }

    @Test
    fun parsesReprovisionAck() {
        assertTrue(
            ProvisioningCodec.parseReprovisionAck(
                """{"v":"1.0","type":"reprovision_ack","status":"ok","reboot_in_ms":1500}""",
            ),
        )
        assertFalse(ProvisioningCodec.parseReprovisionAck("""{"type":"reprovision_ack","status":"error"}"""))
        assertFalse(ProvisioningCodec.parseReprovisionAck("not json"))
    }

    @Test
    fun toleratesGarbage() {
        assertNull(ProvisioningCodec.parseInfo("not json"))
        assertNull(ProvisioningCodec.parseInfo("""{"dev":"x"}"""))   // no id
        assertNull(ProvisioningCodec.parseProvisionAck("""{"status":"weird"}"""))
    }
}
