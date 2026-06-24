package org.openmuscle.connect.discovery

import org.openmuscle.connect.domain.DiscoveredDevice

/**
 * Deduplicates discovered devices by id and merges partial updates from the
 * different discovery sources (mDNS, beacons, sensor frames). A later update with
 * a null field does not clobber a known value, and a non-flexgrid/unknown type
 * does not overwrite a known type. Insertion order is preserved for stable list
 * display. Pure and single-threaded; see DeviceRegistryTest.
 */
class DeviceRegistry {

    private val devices = LinkedHashMap<String, DiscoveredDevice>()

    /** Merge [found] in, applying [nickname] as the display name. Returns the list. */
    fun upsert(found: DiscoveredDevice, nickname: String?): List<DiscoveredDevice> {
        val existing = devices[found.id]
        val merged = if (existing == null) {
            found
        } else {
            existing.copy(
                host = found.host ?: existing.host,
                port = found.port ?: existing.port,
                cmdPort = found.cmdPort ?: existing.cmdPort,
                rssi = found.rssi ?: existing.rssi,
                deviceType = if (found.deviceType != "unknown") found.deviceType else existing.deviceType,
            )
        }
        devices[found.id] = merged.copy(nickname = nickname)
        return devices.values.toList()
    }

    /** Set the friendly name for a device already known. Returns the list. */
    fun rename(id: String, nickname: String?): List<DiscoveredDevice> {
        devices[id]?.let { devices[id] = it.copy(nickname = nickname) }
        return devices.values.toList()
    }
}
