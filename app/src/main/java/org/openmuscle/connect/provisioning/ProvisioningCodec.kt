package org.openmuscle.connect.provisioning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Parsed `GET /info` from an unprovisioned device's AP (PROVISIONING.md 4.1). */
data class ProvisionInfo(
    val id: String,
    val dev: String,
    val fw: String?,
    val state: String?,
    val caps: List<String>,
    val matrix: List<Int>?,
)

/** Result of `POST /provision` (PROVISIONING.md 4.2). */
sealed interface ProvisionResult {
    data class Ok(val id: String?, val rebootInMs: Long) : ProvisionResult
    data class Error(val message: String) : ProvisionResult
}

/**
 * Builds and parses the JSON for the SoftAP provisioning HTTP endpoints
 * (PROVISIONING.md 4). Pure Kotlin (no Android), so it is unit-testable on the
 * JVM; the OkHttp client + the WifiNetworkSpecifier AP-bind live in the (untestable)
 * ProvisioningClient.
 */
object ProvisioningCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * `POST /provision` body. `host`/`hub_host` are intentionally OMITTED: the
     * phone cannot know its future home-LAN IP while it holds a 192.168.4.x AP
     * address (board #0042). The device relies on post-join discovery instead (the
     * phone re-hears its 3140 announce and subscribes).
     */
    fun provisionRequest(ssid: String, password: String): String =
        buildJsonObject {
            put("ssid", ssid)
            put("password", password)
        }.toString()

    fun parseInfo(text: String): ProvisionInfo? {
        val o = obj(text) ?: return null
        val id = o.str("id") ?: return null
        return ProvisionInfo(
            id = id,
            dev = o.str("dev") ?: "unknown",
            fw = o.str("fw"),
            state = o.str("state"),
            caps = (o["caps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            matrix = (o["matrix"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull },
        )
    }

    fun parseProvisionAck(text: String): ProvisionResult? {
        val o = obj(text) ?: return null
        return when (o.str("status")) {
            "ok" -> ProvisionResult.Ok(
                id = o.str("id"),
                rebootInMs = (o["reboot_in_ms"] as? JsonPrimitive)?.longOrNull ?: 1500L,
            )
            "error" -> ProvisionResult.Error(
                message = (o["data"] as? JsonObject)?.str("message") ?: o.str("message") ?: "error",
            )
            else -> null
        }
    }

    /** Parse a `POST /reprovision` ack (PROVISIONING.md 4.3); true on status ok. */
    fun parseReprovisionAck(text: String): Boolean = obj(text)?.str("status") == "ok"

    private fun obj(text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
