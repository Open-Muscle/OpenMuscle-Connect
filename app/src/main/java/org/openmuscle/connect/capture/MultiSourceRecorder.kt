package org.openmuscle.connect.capture

import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.protocol.MatrixUtil

/**
 * Multi-device capture: pairs each role-tagged sensor source with the labeler and
 * writes role-tagged schema-v2 rows (docs/CSV-SCHEMA-V2.md) via [CsvV2Writer].
 *
 * The hub subscribes to several devices at once (two FlexGrid bands + a labeler,
 * PROTOCOL.md 8). Frames arrive demultiplexed by device id; [roleOf] maps a device
 * id to its hub-assigned [Role]. Each `left`/`right` sensor frame is paired with the
 * nearest labeler label (the same [TemporalMatcher] used in v1) and written as one
 * long-format row tagged with its role + device id. The trainer pivots these rows
 * into the bilateral matrix later (CSV-SCHEMA-V2 section 3); this writer stays
 * one-row-per-source.
 *
 * Pure and synchronous so it is unit-testable without coroutines; the capture
 * screen drives [onSensor]/[onLabel] from the transport flows.
 */
class MultiSourceRecorder(
    private val writer: CsvV2Writer,
    private val roleOf: (String) -> Role?,
    private val matcher: TemporalMatcher = TemporalMatcher(),
) {
    @Volatile
    var seen: Long = 0
        private set

    @Volatile
    var matched: Long = 0
        private set

    val matchRate: Float
        get() = if (seen == 0L) 0f else matched.toFloat() / seen

    /** The labeler's frames (LASK5 or Quest) feed the matcher. */
    fun onLabel(label: LabelFrame) {
        matcher.addLabel(label)
    }

    fun onSensor(frame: SensorFrame) {
        // Only count sensor frames from devices the user tagged as a sensor band.
        val role = roleOf(frame.deviceId)
        if (role == null || role == Role.LABELER) return
        seen++
        val labels = matcher.match(frame.receiveTimeMs)?.values ?: return
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
