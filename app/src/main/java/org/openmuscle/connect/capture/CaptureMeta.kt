package org.openmuscle.connect.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.openmuscle.connect.domain.Role

/**
 * Sidecar metadata for one capture, written as `<capture>.meta.json` next to the
 * CSV (docs/CSV-SCHEMA-V2.md section 5). It carries what the role-tagged CSV rows
 * cannot self-describe: whether the capture is mirrored (so the trainer does not
 * double-apply one-limb mirroring, section 8.5), which device played which role,
 * and the label source. Additive to and coexists with the PC's
 * `<capture>.labels.schema.json` (confirmed with vrpc).
 */
data class CaptureMeta(
    val mirror: Boolean,
    val labelSource: String,        // "lask5" | "quest" | "manual"
    val roles: Map<String, Role>,   // device_id -> assigned role
    val createdMs: Long,
)

/** Pure JSON serialization for the meta sidecar. JVM-testable; see CaptureMetaTest. */
object CaptureMetaCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(meta: CaptureMeta): String =
        buildJsonObject {
            put("schema", "v2")
            put("mirror", meta.mirror)
            put("label_source", meta.labelSource)
            putJsonObject("roles") {
                // Stable order for byte-stable output: by device id.
                meta.roles.toSortedMap().forEach { (id, role) -> put(id, role.wire) }
            }
            put("created_ms", meta.createdMs)
        }.toString()

    /** The sidecar filename for a capture CSV, e.g. `capture_v2_123.csv` -> `capture_v2_123.meta.json`. */
    fun sidecarName(csvName: String): String = csvName.removeSuffix(".csv") + ".meta.json"
}
