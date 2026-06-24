package org.openmuscle.connect.transport.ble

import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.protocol.MatrixUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes/decodes the compact-binary BLE sensor frame (docs/WIRE-FORMAT.md
 * section 7). Cross-verified against tools/ble_frame.py: BleFrameCodecTest
 * decodes that tool's canonical hex and asserts [encode] reproduces it.
 *
 * On-wire order is row-major (R0C0..R3C14); the produced [SensorFrame] stores
 * the matrix column-major (matrix[col][row]) like the rest of the app, so
 * [MatrixUtil.flattenRowMajor] round-trips back to the wire order.
 */
object BleFrameCodec {

    const val FLEXGRID_ROWS = 4
    const val FLEXGRID_COLS = 15
    const val FRAME_SIZE = 8 + FLEXGRID_ROWS * FLEXGRID_COLS * 2   // 128

    fun decode(bytes: ByteArray, deviceId: String, receiveTimeMs: Long): SensorFrame? {
        if (bytes.size < FRAME_SIZE) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.get()                                   // version (reserved)
        bb.get()                                   // device_type (0 = flexgrid)
        val seq = bb.short.toInt() and 0xFFFF
        val ts = bb.int.toLong() and 0xFFFFFFFFL
        val rows = FLEXGRID_ROWS
        val cols = FLEXGRID_COLS
        val flat = IntArray(rows * cols) { bb.short.toInt() and 0xFFFF }
        // flat is row-major flat[r*cols + c]; store column-major matrix[c][r].
        val matrix = List(cols) { c -> List(rows) { r -> flat[r * cols + c] } }
        return SensorFrame(
            deviceId = deviceId,
            deviceType = "flexgrid",
            rows = rows,
            cols = cols,
            matrix = matrix,
            deviceTimestampMs = ts,
            seq = seq,
            receiveTimeMs = receiveTimeMs,
            status = null,
        )
    }

    /** Inverse of [decode], for tests and the in-memory mock. */
    fun encode(matrix: List<List<Int>>, seq: Int, tsMs: Long): ByteArray {
        val cols = matrix.size
        val rows = matrix[0].size
        val bb = ByteBuffer.allocate(8 + rows * cols * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(1.toByte())          // version
        bb.put(0.toByte())          // device_type flexgrid
        bb.putShort(seq.toShort())
        bb.putInt(tsMs.toInt())
        for (v in MatrixUtil.flattenRowMajor(matrix)) bb.putShort(v.toShort())
        return bb.array()
    }
}
