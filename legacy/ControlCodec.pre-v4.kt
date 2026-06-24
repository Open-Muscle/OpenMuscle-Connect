package org.openmuscle.connect.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.openmuscle.connect.domain.DeviceStatus

/**
 * PRE-V4 SNAPSHOT (not compiled). See legacy/README.md.
 *
 * Encoded control-plane requests and decoded replies for the WebSocket `/cmd`
 * channel. The emitted JSON was byte-identical to what the reference server
 * (tools/cmd_server.ws.py) accepted. This codec assumes the old Command shape
 * (SetScanRate(hz), Subscribe(host, port), Unsubscribe) and old DeviceInfo
 * (scanRateHz, transports). Restoring it requires restoring those too.
 */
object ControlCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeCommand(deviceId: String, msgId: Int, command: Command): String {
        val data = buildJsonObject {
            when (command) {
                Command.StartStream -> put("verb", "start_stream")
                Command.StopStream -> put("verb", "stop_stream")
                is Command.SetScanRate -> {
                    put("verb", "set_scan_rate")
                    put("scan_rate_hz", command.hz)
                }
                is Command.Subscribe -> {
                    put("verb", "subscribe")
                    put("host", command.host)
                    put("port", command.port)
                }
                Command.Unsubscribe -> put("verb", "unsubscribe")
            }
        }
        return envelope("cmd", deviceId, msgId, data)
    }

    fun encodeGetInfo(deviceId: String, msgId: Int): String =
        buildJsonObject {
            put("v", "1.0")
            put("type", "get_info")
            put("id", deviceId)
            put("msg_id", msgId)
        }.toString()

    fun encodeSession(deviceId: String, msgId: Int, verb: String, meta: SessionMeta): String {
        val data = buildJsonObject {
            put("verb", verb)
            put("session_id", meta.sessionId)
            putJsonObject("meta") {
                meta.user?.let { put("user", it) }
                meta.location?.let { put("location", it) }
                meta.intent?.let { put("intent", it) }
            }
        }
        return envelope("session", deviceId, msgId, data)
    }

    private fun envelope(type: String, id: String, msgId: Int, data: JsonObject): String =
        buildJsonObject {
            put("v", "1.0")
            put("type", type)
            put("id", id)
            put("msg_id", msgId)
            put("data", data)
        }.toString()

    sealed interface Reply {
        data class Ack(val msgId: Int?, val ok: Boolean, val error: String?) : Reply
        data class Info(val info: DeviceInfo) : Reply
        data class Status(val status: DeviceStatus) : Reply
        data object Unknown : Reply
    }

    fun parseReply(text: String): Reply {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Reply.Unknown

        return when (obj.prim("type")?.contentOrNull) {
            "ack" -> {
                val data = obj["data"] as? JsonObject
                Reply.Ack(
                    msgId = obj.prim("msg_id")?.intOrNull,
                    ok = data?.prim("ok")?.booleanOrNull ?: false,
                    error = data?.prim("error")?.contentOrNull,
                )
            }
            "info" -> parseInfo(obj)
            "status" -> Reply.Status(parseStatus(obj["data"] as? JsonObject))
            else -> Reply.Unknown
        }
    }

    private fun parseInfo(obj: JsonObject): Reply {
        val data = obj["data"] as? JsonObject ?: return Reply.Unknown
        val matrix = data["matrix"] as? JsonArray
        val cols = (matrix?.getOrNull(0) as? JsonPrimitive)?.intOrNull
        val rows = (matrix?.getOrNull(1) as? JsonPrimitive)?.intOrNull
        val transports = (data["transports"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return Reply.Info(
            DeviceInfo(
                deviceId = obj.prim("id")?.contentOrNull ?: "unknown",
                deviceType = data.prim("dev")?.contentOrNull ?: "unknown",
                firmware = data.prim("fw")?.contentOrNull,
                rows = rows,
                cols = cols,
                scanRateHz = data.prim("scan_rate_hz")?.intOrNull,
                transports = transports,
            ),
        )
    }

    private fun parseStatus(data: JsonObject?): DeviceStatus = DeviceStatus(
        batteryPct = data?.prim("pct")?.intOrNull,
        vbat = data?.prim("vbat")?.doubleOrNull,
        rssi = data?.prim("rssi")?.intOrNull,
        freeMem = data?.prim("free_mem")?.longOrNull,
        uptimeS = data?.prim("uptime_s")?.longOrNull,
        scanRateHz = data?.prim("scan_rate_hz")?.intOrNull,
    )

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
}
