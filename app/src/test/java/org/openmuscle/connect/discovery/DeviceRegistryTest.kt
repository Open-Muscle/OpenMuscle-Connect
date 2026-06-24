package org.openmuscle.connect.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.TransportKind

class DeviceRegistryTest {

    private fun dev(
        id: String,
        host: String? = null,
        rssi: Int? = null,
        type: String = "flexgrid",
    ) = DiscoveredDevice(
        id = id,
        deviceType = type,
        transport = TransportKind.WIFI,
        host = host,
        rssi = rssi,
    )

    @Test
    fun upsertMergesNewFields() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null, null)
        val list = r.upsert(dev("a", host = "1.2.3.4", rssi = -50), null, null)
        assertEquals(1, list.size)
        assertEquals("1.2.3.4", list[0].host)
        assertEquals(-50, list[0].rssi)
    }

    @Test
    fun laterNullDoesNotClobberKnownValues() {
        val r = DeviceRegistry()
        r.upsert(dev("a", host = "1.2.3.4", rssi = -50), null, null)
        val list = r.upsert(dev("a"), null, null)   // a bare sensor-frame update
        assertEquals("1.2.3.4", list[0].host)
        assertEquals(-50, list[0].rssi)
    }

    @Test
    fun unknownTypeDoesNotOverwriteKnownType() {
        val r = DeviceRegistry()
        r.upsert(dev("a", type = "flexgrid"), null, null)
        val list = r.upsert(dev("a", type = "unknown"), null, null)
        assertEquals("flexgrid", list[0].deviceType)
    }

    @Test
    fun nicknameApplied() {
        val list = DeviceRegistry().upsert(dev("a"), "My Band", null)
        assertEquals("My Band", list[0].nickname)
    }

    @Test
    fun renameUpdatesNickname() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null, null)
        val list = r.rename("a", "Renamed")
        assertEquals("Renamed", list[0].nickname)
    }

    @Test
    fun roleAppliedOnUpsert() {
        val list = DeviceRegistry().upsert(dev("a"), null, Role.LEFT)
        assertEquals(Role.LEFT, list[0].role)
    }

    @Test
    fun setRoleUpdatesAndClears() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null, null)
        assertEquals(Role.RIGHT, r.setRole("a", Role.RIGHT)[0].role)
        assertNull(r.setRole("a", null)[0].role)
    }

    @Test
    fun preservesInsertionOrder() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null, null)
        val list = r.upsert(dev("b"), null, null)
        assertEquals(listOf("a", "b"), list.map { it.id })
    }
}
