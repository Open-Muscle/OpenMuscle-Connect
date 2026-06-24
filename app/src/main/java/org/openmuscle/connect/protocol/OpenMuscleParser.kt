package org.openmuscle.connect.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame

/** Result of parsing one datagram. */
sealed interface ParsedPacket {
    data class Sensor(val frame: SensorFrame) : ParsedPacket
    data class Label(val frame: LabelFrame) : ParsedPacket

    /**
     * A discovery beacon (docs/DEVICE-DISCOVERY-SPEC.md, FlexGridV4 discovery.py).
     * [host] is not in the JSON; the receiver fills it from the datagram's source
     * address. Ports come from the `services` map: [sensorPort] (`services.sensor`,
     * the UDP frame port) and [cmdPort] (`services.cmd`, the TCP command port).
     */
    data class Announce(
        val deviceId: String,
        val deviceType: String,
        val firmware: String?,
        val transports: List<String>,
        val sensorPort: Int?,
        val cmdPort: Int?,
        val host: String? = null,
    ) : ParsedPacket

    data object Ignored : ParsedPacket
}

/**
 * Parses OpenMuscle protocol v1.0 JSON. Routing is by the `type` field, never by
 * source address, matching the PC parser (protocol/parser.py). Legacy packets
 * (no `v` field) are ignored for now; the app only needs to consume v1.0.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 */
object OpenMuscleParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(bytes: ByteArray, length: Int, receiveTimeMs: Long): ParsedPacket =
        parse(String(bytes, 0, length, Charsets.UTF_8), receiveTimeMs)

    fun parse(text: String, receiveTimeMs: Long): ParsedPacket {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ParsedPacket.Ignored
        }
        val obj = root as? JsonObject ?: return ParsedPacket.Ignored
        if ("v" !in obj) return ParsedPacket.Ignored   // v1.0 marker; legacy ignored

        val type = obj.prim("type")?.contentOrNull ?: return ParsedPacket.Ignored
        val id = obj.prim("id")?.contentOrNull ?: "unknown"
        val ts = obj.prim("ts")?.longOrNull ?: 0L
        val seq = obj.prim("seq")?.intOrNull
        val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
        val meta = obj["meta"] as? JsonObject

        return when (type) {
            "flexgrid" -> parseFlexgrid(id, ts, seq, data, meta, receiveTimeMs)
            "lask5" -> parseLask5(id, ts, data, receiveTimeMs)
            "announce" -> parseAnnounce(id, obj)
            else -> ParsedPacket.Ignored
        }
    }

    private fun parseAnnounce(id: String, obj: JsonObject): ParsedPacket {
        // Announce fields sit at the top level, not under `data`.
        val dev = obj.prim("dev")?.contentOrNull ?: "unknown"
        val fw = obj.prim("fw")?.contentOrNull
        val transports = (obj["transports"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        // V4 advertises ports under `services`; fall back to a top-level `port`
        // for V3-style beacons that predate the services map.
        val services = obj["services"] as? JsonObject
        val topPort = obj.prim("port")?.intOrNull
        val sensorPort = services?.prim("sensor")?.intOrNull ?: topPort
        val cmdPort = services?.prim("cmd")?.intOrNull
        return ParsedPacket.Announce(
            deviceId = id,
            deviceType = dev,
            firmware = fw,
            transports = transports,
            sensorPort = sensorPort,
            cmdPort = cmdPort,
        )
    }

    private fun parseFlexgrid(
        id: String,
        ts: Long,
        seq: Int?,
        data: JsonObject,
        meta: JsonObject?,
        receiveTimeMs: Long,
    ): ParsedPacket {
        val matrixEl = data["matrix"] as? JsonArray ?: return ParsedPacket.Ignored
        val matrix = matrixEl.map { col ->
            (col as? JsonArray)?.map { it.asInt() } ?: emptyList()
        }
        if (matrix.isEmpty() || matrix[0].isEmpty()) return ParsedPacket.Ignored
        return ParsedPacket.Sensor(
            SensorFrame(
                deviceId = id,
                deviceType = "flexgrid",
                rows = matrix[0].size,
                cols = matrix.size,
                matrix = matrix,
                deviceTimestampMs = ts,
                seq = seq,
                receiveTimeMs = receiveTimeMs,
                status = meta?.let { parseStatus(it) },
            ),
        )
    }

    private fun parseLask5(
        id: String,
        ts: Long,
        data: JsonObject,
        receiveTimeMs: Long,
    ): ParsedPacket {
        val valuesEl = data["values"] as? JsonArray ?: return ParsedPacket.Ignored
        val values = valuesEl.map { it.asInt() }
        val joystick = data["joystick"] as? JsonObject
        return ParsedPacket.Label(
            LabelFrame(
                deviceId = id,
                deviceType = "lask5",
                values = values,
                joystickX = joystick?.prim("x")?.intOrNull,
                joystickY = joystick?.prim("y")?.intOrNull,
                deviceTimestampMs = ts,
                receiveTimeMs = receiveTimeMs,
            ),
        )
    }

    private fun parseStatus(meta: JsonObject) = DeviceStatus(
        batteryPct = meta.prim("pct")?.intOrNull,
        vbat = meta.prim("vbat")?.doubleOrNull,
        rssi = meta.prim("rssi")?.intOrNull,
        freeMem = meta.prim("free_mem")?.longOrNull,
        uptimeS = meta.prim("uptime_s")?.longOrNull,
        scanRateHz = meta.prim("scan_rate_hz")?.intOrNull,
    )

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    /** ADC and label values are integers, but tolerate "100.0" style floats. */
    private fun JsonElement.asInt(): Int {
        val p = this as? JsonPrimitive ?: return 0
        return p.intOrNull ?: p.doubleOrNull?.toInt() ?: 0
    }
}
