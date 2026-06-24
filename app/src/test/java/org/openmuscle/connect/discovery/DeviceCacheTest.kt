package org.openmuscle.connect.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind

class DeviceCacheTest {

    private fun cached(id: String, seen: Long, host: String = "10.0.0.23", cmd: Int? = 8001) =
        CachedDevice(id, "flexgrid", host, port = 3141, cmdPort = cmd, lastSeenMs = seen)

    @Test
    fun roundTripsAList() {
        val devices = listOf(cached("flexgrid-d7af0b", 200), cached("lask5-01", 100, host = "10.0.0.192", cmd = 8002))
        val decoded = DeviceCache.decode(DeviceCache.encode(devices))
        // Most-recent-first ordering on encode.
        assertEquals(listOf("flexgrid-d7af0b", "lask5-01"), decoded.map { it.id })
        assertEquals(devices.sortedByDescending { it.lastSeenMs }, decoded)
    }

    @Test
    fun omitsAndRestoresNullPorts() {
        val d = CachedDevice("fg", "flexgrid", "10.0.0.5", port = null, cmdPort = null, lastSeenMs = 1)
        val json = DeviceCache.encode(listOf(d))
        assertTrue("no port/cmd keys when null", !json.contains("\"port\"") && !json.contains("\"cmd\""))
        assertEquals(d, DeviceCache.decode(json).single())
    }

    @Test
    fun decodeToleratesNullEmptyAndGarbage() {
        assertEquals(emptyList<CachedDevice>(), DeviceCache.decode(null))
        assertEquals(emptyList<CachedDevice>(), DeviceCache.decode(""))
        assertEquals(emptyList<CachedDevice>(), DeviceCache.decode("not json"))
        assertEquals(emptyList<CachedDevice>(), DeviceCache.decode("{}"))      // object, not array
        assertEquals(emptyList<CachedDevice>(), DeviceCache.decode("""[{"dev":"x"}]"""))  // no id/host
    }

    @Test
    fun capsAtMaxEntriesKeepingMostRecent() {
        val many = (1..30).map { cached("dev-$it", seen = it.toLong()) }
        val decoded = DeviceCache.decode(DeviceCache.encode(many))
        assertEquals(DeviceCache.MAX_ENTRIES, decoded.size)
        // Kept the highest lastSeenMs (30 down to 30-16+1=15).
        assertEquals("dev-30", decoded.first().id)
        assertEquals("dev-15", decoded.last().id)
    }

    @Test
    fun mapsToAndFromDiscoveredDevice() {
        val live = DiscoveredDevice(
            id = "flexgrid-d7af0b", deviceType = "flexgrid", transport = TransportKind.WIFI,
            host = "10.0.0.23", port = 3141, cmdPort = 8001, rssi = -57,
        )
        val c = live.toCached(nowMs = 999)!!
        assertEquals(CachedDevice("flexgrid-d7af0b", "flexgrid", "10.0.0.23", 3141, 8001, 999), c)
        val back = c.toDiscovered()
        assertEquals(live.copy(rssi = null), back)   // rssi is live-only, dropped through the cache
    }

    @Test
    fun toCachedIsNullWithoutHost() {
        val noHost = DiscoveredDevice(id = "x", deviceType = "flexgrid", transport = TransportKind.WIFI, host = null)
        assertNull(noHost.toCached(1))
    }
}
