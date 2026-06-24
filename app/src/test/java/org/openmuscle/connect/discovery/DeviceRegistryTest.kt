package org.openmuscle.connect.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmuscle.connect.domain.DiscoveredDevice
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
        r.upsert(dev("a"), null)
        val list = r.upsert(dev("a", host = "1.2.3.4", rssi = -50), null)
        assertEquals(1, list.size)
        assertEquals("1.2.3.4", list[0].host)
        assertEquals(-50, list[0].rssi)
    }

    @Test
    fun laterNullDoesNotClobberKnownValues() {
        val r = DeviceRegistry()
        r.upsert(dev("a", host = "1.2.3.4", rssi = -50), null)
        val list = r.upsert(dev("a"), null)   // a bare sensor-frame update
        assertEquals("1.2.3.4", list[0].host)
        assertEquals(-50, list[0].rssi)
    }

    @Test
    fun unknownTypeDoesNotOverwriteKnownType() {
        val r = DeviceRegistry()
        r.upsert(dev("a", type = "flexgrid"), null)
        val list = r.upsert(dev("a", type = "unknown"), null)
        assertEquals("flexgrid", list[0].deviceType)
    }

    @Test
    fun nicknameApplied() {
        val list = DeviceRegistry().upsert(dev("a"), "My Band")
        assertEquals("My Band", list[0].nickname)
    }

    @Test
    fun renameUpdatesNickname() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null)
        val list = r.rename("a", "Renamed")
        assertEquals("Renamed", list[0].nickname)
    }

    @Test
    fun preservesInsertionOrder() {
        val r = DeviceRegistry()
        r.upsert(dev("a"), null)
        val list = r.upsert(dev("b"), null)
        assertEquals(listOf("a", "b"), list.map { it.id })
    }
}
