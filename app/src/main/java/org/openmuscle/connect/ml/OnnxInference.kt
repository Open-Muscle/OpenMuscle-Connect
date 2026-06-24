package org.openmuscle.connect.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Runs a PC-exported RandomForest (ONNX) on FlexGrid frames: the "mirror PC"
 * inference path. The model and the feature contract come from
 * tools/export_onnx.py (verified: ONNX Runtime matches scikit-learn): 60 inputs
 * in row-major R0C0..R3C14 order (exactly what
 * [org.openmuscle.connect.protocol.MatrixUtil.flattenRowMajor] produces) and
 * 4 piston outputs.
 *
 * Untested in this environment (onnxruntime-android runs on device). Load the
 * `.onnx` the PC produced; predictions should match the golden.json emitted
 * alongside it.
 */
class OnnxInference private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
) : ModelRunner {
    /**
     * Predict the label vector for one frame. [features] is the flattened
     * row-major sensor vector (use MatrixUtil.flattenRowMajor on the frame's
     * matrix). Returns the model's output vector (4 piston values for the
     * current FlexGrid model).
     */
    override fun predict(features: IntArray): FloatArray {
        val input = FloatArray(features.size) { features[it].toFloat() }
        val shape = longArrayOf(1, features.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0]
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Load a model exported by tools/export_onnx.py from a file path. */
        fun fromFile(path: String): OnnxInference {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(path, OrtSession.SessionOptions())
            return OnnxInference(env, session, session.inputNames.iterator().next())
        }

        /** Load a model from raw bytes (e.g. read from a content Uri). */
        fun fromBytes(model: ByteArray): OnnxInference {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(model, OrtSession.SessionOptions())
            return OnnxInference(env, session, session.inputNames.iterator().next())
        }
    }
}
