package org.openmuscle.connect.capture

import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.protocol.MatrixUtil

/**
 * Single-source capture: pairs incoming sensor frames with labels and writes
 * schema-v2 rows (docs/CSV-SCHEMA-V2.md) via [CsvV2Writer], tagged with the band's
 * hub-assigned [role] and the frame's device id. The everyday one-band capture
 * uses the same v2 schema as multi-device, so there is one CSV format in the wild.
 *
 * Two label modes:
 *  - External labels (LASK5, or later Quest): feed them via [onLabel]; each
 *    sensor frame pairs with the nearest one inside the matcher's window.
 *  - Manual labels: set [manualLabel] to a fixed vector (the user taps a target
 *    pose) and every sensor frame pairs with it until it changes or is cleared.
 *
 * Pure and synchronous so it is unit-testable without coroutines; the recording
 * screen drives [onSensor]/[onLabel] from the transport flows.
 */
class CaptureRecorder(
    private val writer: CsvV2Writer,
    private val role: Role,
    private val matcher: TemporalMatcher = TemporalMatcher(),
) {
    /** When non-null, every sensor frame is labeled with this vector. */
    @Volatile
    var manualLabel: List<Double>? = null

    // Volatile so the UI ticker can read live stats from a different thread than
    // the one writing rows.
    @Volatile
    var seen: Long = 0
        private set

    @Volatile
    var matched: Long = 0
        private set

    val matchRate: Float
        get() = if (seen == 0L) 0f else matched.toFloat() / seen

    fun onLabel(label: LabelFrame) {
        matcher.addLabel(label)
    }

    fun onSensor(frame: SensorFrame) {
        seen++
        val labels = manualLabel ?: matcher.match(frame.receiveTimeMs)?.values ?: return
        writer.writeRow(
            tsHubMs = frame.receiveTimeMs,
            role = role.wire,
            deviceId = frame.deviceId,
            sensorValues = MatrixUtil.flattenRowMajor(frame.matrix),
            labelValues = labels,
        )
        matched++
    }

    fun flush() = writer.flush()

    fun finish() = writer.close()
}
