package org.openmuscle.connect.protocol

/**
 * Matrix flatten helpers. This ordering is the single source of truth for
 * train/inference feature alignment and is verified end to end against the PC
 * recorder in tools/wireformat_check.py.
 */
object MatrixUtil {

    /**
     * Flatten a column-major matrix (`matrix[col][row]`) into the PC-compatible
     * row-major feature vector: iterate rows outer, cols inner, taking
     * `matrix[c][r]`. The value at output index `k` aligns with CSV column
     * `R{r}C{c}` where `r = k / cols` and `c = k % cols`.
     *
     * Mirrors the PC recorder in web/state.py:
     * ```
     * flat = [mat[c][r] for r in range(rows) for c in range(cols)]
     * ```
     * Do NOT swap to a column-major flatten (the `schema.flat_sensor_values`
     * shortcut). That was a real PC-side bug (commit 245cb8f) and would feed the
     * model transposed features.
     */
    fun flattenRowMajor(matrix: List<List<Int>>): IntArray {
        if (matrix.isEmpty() || matrix[0].isEmpty()) return IntArray(0)
        val cols = matrix.size
        val rows = matrix[0].size
        val out = IntArray(rows * cols)
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[k++] = matrix[c][r]
            }
        }
        return out
    }

    /** CSV sensor column names in the same order as [flattenRowMajor]. */
    fun sensorColumnNames(rows: Int, cols: Int): List<String> = buildList {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                add("R${r}C${c}")
            }
        }
    }
}
