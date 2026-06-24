package org.openmuscle.connect.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.openmuscle.connect.domain.SensorFrame

class InferenceTest {

    /** The model must be fed the row-major flatten (matrix[c][r], rows outer),
     *  the same order the training CSV columns use. */
    @Test
    fun feedsRowMajorFeatureVector() {
        var captured: IntArray? = null
        val runner = object : ModelRunner {
            override fun predict(features: IntArray): FloatArray {
                captured = features
                return floatArrayOf(1f, 2f, 3f, 4f)
            }
            override fun close() {}
        }
        val frame = SensorFrame(
            deviceId = "d",
            deviceType = "flexgrid",
            rows = 2,
            cols = 3,
            matrix = listOf(listOf(0, 1), listOf(10, 11), listOf(20, 21)),
            deviceTimestampMs = 0,
            seq = null,
            receiveTimeMs = 0,
            status = null,
        )
        val out = Inference.predict(runner, frame)
        assertArrayEquals(intArrayOf(0, 10, 20, 1, 11, 21), captured)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), out, 0f)
    }
}
