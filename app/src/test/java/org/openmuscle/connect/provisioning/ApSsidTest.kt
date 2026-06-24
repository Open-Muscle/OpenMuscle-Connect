package org.openmuscle.connect.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApSsidTest {

    @Test
    fun parsesFlexgridAp() {
        val d = ApSsid.parse("OM-flexgrid-d7af0b")!!
        assertEquals("flexgrid", d.dev)
        assertEquals("d7af0b", d.idTail)
        assertEquals("flexgrid-d7af0b", d.deviceId)
        assertEquals("OM-flexgrid-d7af0b", d.ssid)
    }

    @Test
    fun parsesLask5Ap() {
        val d = ApSsid.parse("OM-lask5-01")!!
        assertEquals("lask5", d.dev)
        assertEquals("01", d.idTail)
        assertEquals("lask5-01", d.deviceId)
    }

    @Test
    fun rejectsNonOmAndMalformed() {
        assertNull(ApSsid.parse("MyHomeWiFi"))
        assertNull(ApSsid.parse("OM-"))            // no dev/id
        assertNull(ApSsid.parse("OM-flexgrid"))    // no id tail
        assertNull(ApSsid.parse("OM--d7af0b"))     // empty dev
        assertNull(ApSsid.parse("OM-flexgrid-"))   // empty id tail
    }
}
