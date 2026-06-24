package org.openmuscle.connect.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Encodes control-plane requests and decodes acks for the V4 TCP command channel
 * (services.cmd, default 8001). Wire format matches the firmware exactly
 * (FlexGridV4-Firmware/lib/commands.py): every request is one JSON object per
 * line, `{"v":"1.0","type":"cmd","id":<hubId>,"msg_id":<n>,"data":{"verb":<verb>,...}}`,
 * and every reply is one ack line, `{"v":"1.0","type":"ack","status":"ok"|"error",
 * "msg_id":<n>,"data":{"verb":<verb>,...}}`.
 *
 * Pure Kotlin (no Android), so it is unit-testable on the JVM. tools/cmd_server.py
 * mirrors the firmware and ControlCodecTest asserts the exact strings.
 */
object ControlCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Build one command line (no trailing newline; the channel appends it). The
     * `data.verb` is taken from [command]; per-verb fields follow. Key order is
     * fixed so the output is byte-stable and assertable.
     */
    fun encode(hubId: String, msgId: Int, command: Command): String {
        val data = buildJsonObject {
            put("verb", command.verb)
            when (command) {
                Command.GetInfo,
                Command.StartStream,
                Command.StopStream,
                Command.Reboot -> {
                    // verb-only
                }
                is Command.Subscribe -> {
                    put("port", command.port)
                    put("transport", "wifi")
                    put("hub_id", command.hubId)
                    command.host?.let { put("host", it) }
                }
                is Command.Unsubscribe -> {
                    put("port", command.port)
                    put("transport", "wifi")
                }
                is Command.Heartbeat -> {
                    put("port", command.port)
                    put("transport", "wifi")
                }
                is Command.SetScanRate -> put("interval_ms", command.intervalMs)
            }
        }
        return buildJsonObject {
            put("v", "1.0")
            put("type", "cmd")
            put("id", hubId)
            put("msg_id", msgId)
            put("data", data)
        }.toString()
    }

    /** A parsed ack line. [data] is kept raw so get_info acks can be mapped to [DeviceInfo]. */
    data class AckLine(
        val ok: Boolean,
        val msgId: Int?,
        val verb: String?,
        val message: String?,
        val data: JsonObject?,
    )

    /** Parse one ack line. Returns null if the text is not a valid ack object. */
    fun parseAckLine(text: String): AckLine? {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (obj.prim("type")?.contentOrNull != "ack") return null
        val data = obj["data"] as? JsonObject
        return AckLine(
            ok = obj.prim("status")?.contentOrNull == "ok",
            msgId = obj.prim("msg_id")?.intOrNull,
            verb = data?.prim("verb")?.contentOrNull,
            message = data?.prim("message")?.contentOrNull,
            data = data,
        )
    }

    /**
     * Map the `data` of a successful get_info ack to [DeviceInfo]. The firmware
     * returns `{verb:"get_info", id, dev, fw, matrix:[cols,rows], caps:[...],
     * subscribers:[...]}`.
     */
    fun parseInfo(data: JsonObject): DeviceInfo {
        val matrix = data["matrix"] as? JsonArray
        val cols = (matrix?.getOrNull(0) as? JsonPrimitive)?.intOrNull
        val rows = (matrix?.getOrNull(1) as? JsonPrimitive)?.intOrNull
        val caps = (data["caps"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val subscriberCount = (data["subscribers"] as? JsonArray)?.size
        return DeviceInfo(
            deviceId = data.prim("id")?.contentOrNull ?: "unknown",
            deviceType = data.prim("dev")?.contentOrNull ?: "unknown",
            firmware = data.prim("fw")?.contentOrNull,
            rows = rows,
            cols = cols,
            caps = caps,
            subscriberCount = subscriberCount,
        )
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
}
