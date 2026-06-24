package org.openmuscle.connect.ml

import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.protocol.MatrixUtil

/** A loaded model mapping a flat feature vector to an output vector. */
interface ModelRunner {
    fun predict(features: IntArray): FloatArray
    fun close()
}

/**
 * Inference feature preparation. The model is fed the SAME row-major flatten the
 * training CSV columns use (MatrixUtil.flattenRowMajor), so train-time and
 * infer-time features align. Kept as a tiny pure function so the wiring is
 * unit-testable with a fake [ModelRunner] (the real ONNX runtime only runs on
 * device). See InferenceTest.
 */
object Inference {
    fun predict(runner: ModelRunner, frame: SensorFrame): FloatArray =
        runner.predict(MatrixUtil.flattenRowMajor(frame.matrix))
}
