package org.openmuscle.connect.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.openmuscle.connect.protocol.MatrixUtil

class BleFrameCodecTest {

    // Canonical frame from tools/ble_frame.py --selftest:
    // seq=7, ts=123456, matrix[col][row] = 100*col + row.
    private val canonicalHex =
        "0100070040e2010000006400c8002c019001f4015802bc0220038403e8034c04b0041405" +
            "780501006500c9002d019101f5015902bd0221038503e9034d04b1041505790502006600" +
            "ca002e019201f6015a02be0222038603ea034e04b20416057a0503006700cb002f019301" +
            "f7015b02bf0223038703eb034f04b30417057b05"

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun decodesCanonicalFrame() {
        val frame = BleFrameCodec.decode(hexToBytes(canonicalHex), "ble-01", 1000L)
        assertNotNull(frame)
        frame!!
        assertEquals(4, frame.rows)
        assertEquals(15, frame.cols)
        assertEquals(7, frame.seq)
        assertEquals(123456L, frame.deviceTimestampMs)
        assertEquals(1000L, frame.receiveTimeMs)
        assertEquals(0, frame.matrix[0][0])
        assertEquals(100, frame.matrix[1][0])    // matrix[col1][row0], catches a transpose
        assertEquals(1, frame.matrix[0][1])      // matrix[col0][row1]
        assertEquals(1403, frame.matrix[14][3])
        val flat = MatrixUtil.flattenRowMajor(frame.matrix)
        assertEquals(0, flat[0])
        assertEquals(100, flat[1])
        assertEquals(1403, flat[59])
    }

    @Test
    fun encodeMatchesPythonHex() {
        val matrix = List(15) { c -> List(4) { r -> c * 100 + r } }
        val bytes = BleFrameCodec.encode(matrix, seq = 7, tsMs = 123456L)
        assertEquals(BleFrameCodec.FRAME_SIZE, bytes.size)
        assertEquals(canonicalHex, bytes.toHex())
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val matrix = List(15) { c -> List(4) { r -> c * 100 + r } }
        val bytes = BleFrameCodec.encode(matrix, seq = 42, tsMs = 99L)
        val frame = BleFrameCodec.decode(bytes, "x", 5L)
        assertNotNull(frame)
        assertEquals(42, frame!!.seq)
        assertEquals(99L, frame.deviceTimestampMs)
        assertEquals(matrix, frame.matrix)
    }
}
