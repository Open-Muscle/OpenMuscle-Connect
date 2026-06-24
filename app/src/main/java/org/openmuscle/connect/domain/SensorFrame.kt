package org.openmuscle.connect.domain

/**
 * One FlexGrid sensor scan.
 *
 * [matrix] is stored exactly as it arrives on the wire: column-major, so
 * `matrix[col][row]`. To get the PC-compatible feature vector use
 * [org.openmuscle.connect.protocol.MatrixUtil.flattenRowMajor]; do not iterate
 * the raw matrix yourself for training or inference, or you risk the col-major
 * transpose bug the PC side already hit (see docs/WIRE-FORMAT.md section 2.2).
 */
data class SensorFrame(
    val deviceId: String,
    val deviceType: String,
    val rows: Int,
    val cols: Int,
    val matrix: List<List<Int>>,
    val deviceTimestampMs: Long,
    val seq: Int?,
    val receiveTimeMs: Long,
    val status: DeviceStatus?,
)
