package org.openmuscle.connect.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixUtilTest {

    /** matrix[col][row] = col*100 + row, 15 cols x 4 rows. The off-diagonal
     *  values expose any row/col transpose. */
    private fun sample(): List<List<Int>> =
        (0 until 15).map { c -> (0 until 4).map { r -> c * 100 + r } }

    @Test
    fun flattenIsRowMajorNotTransposed() {
        val flat = MatrixUtil.flattenRowMajor(sample())
        assertEquals(60, flat.size)
        assertEquals(0, flat[0])     // R0C0 = matrix[0][0]
        assertEquals(100, flat[1])   // R0C1 = matrix[1][0] (col-major would be 1)
        assertEquals(1, flat[15])    // R1C0 = matrix[0][1] (index 1*15 + 0)
        assertEquals(1403, flat[59]) // R3C14 = matrix[14][3]
    }

    @Test
    fun columnNamesMatchFlattenOrder() {
        val names = MatrixUtil.sensorColumnNames(4, 15)
        assertEquals(60, names.size)
        assertEquals("R0C0", names[0])
        assertEquals("R0C1", names[1])
        assertEquals("R1C0", names[15])
        assertEquals("R3C14", names[59])
    }

    @Test
    fun emptyMatrixIsSafe() {
        assertArrayEquals(IntArray(0), MatrixUtil.flattenRowMajor(emptyList()))
        assertArrayEquals(IntArray(0), MatrixUtil.flattenRowMajor(listOf(emptyList())))
    }

    @Test
    fun flatten16ColLegacyMatrix() {
        val matrix = (0 until 16).map { c -> (0 until 4).map { r -> c * 100 + r } }
        val flat = MatrixUtil.flattenRowMajor(matrix)
        assertEquals(64, flat.size)
        val names = MatrixUtil.sensorColumnNames(4, 16)
        assertEquals(64, names.size)
        assertEquals("R0C15", names[15])           // last column of row 0
        assertEquals("R1C0", names[16])            // first column of row 1
        assertEquals(1500, flat[15])               // R0C15 = matrix[15][0]
        assertEquals(1, flat[16])                  // R1C0  = matrix[0][1]
    }
}
