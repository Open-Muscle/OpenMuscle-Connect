package org.openmuscle.connect.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind

/**
 * A persisted device the hub has seen before, enough to reach it again without a
 * fresh announce. PROTOCOL.md 5.3 / 10.2 require this: a source stops broadcasting
 * once any hub subscribes, so a late-joining phone would never see its beacon and
 * must fall back to a cached address (then re-probe via get_info).
 *
 * The cache key is [id]; the reachable bits are [host] + [cmdPort] (the TCP cmd
 * channel). [lastSeenMs] is wall-clock at last contact, used to order and bound
 * the cache.
 */
data class CachedDevice(
    val id: String,
    val deviceType: String,
    val host: String,
    val port: Int?,        // UDP sensor port (services.sensor); 3141 by convention
    val cmdPort: Int?,     // TCP cmd port (services.cmd)
    val lastSeenMs: Long,
)

/**
 * Pure (de)serialization of the device cache to a JSON string for SharedPreferences
 * (see [org.openmuscle.connect.Prefs]). Manual JSON to match the rest of the
 * codebase and avoid the serialization compiler plugin. JVM-testable; see
 * DeviceCacheTest.
 */
object DeviceCache {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Most-recently-seen first; never persist more than this many entries. */
    const val MAX_ENTRIES = 16

    fun encode(devices: List<CachedDevice>): String =
        buildJsonArray {
            devices.sortedByDescending { it.lastSeenMs }.take(MAX_ENTRIES).forEach { d ->
                addJsonObject {
                    put("id", d.id)
                    put("dev", d.deviceType)
                    put("host", d.host)
                    d.port?.let { put("port", it) }
                    d.cmdPort?.let { put("cmd", it) }
                    put("seen", d.lastSeenMs)
                }
            }
        }.toString()

    fun decode(text: String?): List<CachedDevice> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val host = o.str("host") ?: return@mapNotNull null
            CachedDevice(
                id = id,
                deviceType = o.str("dev") ?: "unknown",
                host = host,
                port = o.int("port"),
                cmdPort = o.int("cmd"),
                lastSeenMs = (o["seen"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/** A live discovery with a known host, as a cache entry stamped [nowMs]; null if no host to cache. */
fun DiscoveredDevice.toCached(nowMs: Long): CachedDevice? {
    val h = host ?: return null
    return CachedDevice(id, deviceType, h, port, cmdPort, nowMs)
}

/** A cached entry surfaced back into the picker. Wi-Fi only; rssi is live-only so null here. */
fun CachedDevice.toDiscovered(): DiscoveredDevice =
    DiscoveredDevice(
        id = id,
        deviceType = deviceType,
        transport = TransportKind.WIFI,
        host = host,
        port = port,
        cmdPort = cmdPort,
    )
