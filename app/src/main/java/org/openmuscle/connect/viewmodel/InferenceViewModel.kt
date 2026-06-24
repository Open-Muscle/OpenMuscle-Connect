package org.openmuscle.connect.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openmuscle.connect.OmApp
import org.openmuscle.connect.ml.Inference
import org.openmuscle.connect.ml.ModelRunner
import org.openmuscle.connect.ml.OnnxInference

data class InferenceUiState(
    val modelLoaded: Boolean = false,
    val predicting: Boolean = false,
    val prediction: List<Float> = emptyList(),
    val latencyMs: Long = 0,
    val error: String? = null,
)

/**
 * Runs the PC-exported ONNX model on live FlexGrid frames (the "mirror PC"
 * inference path). Sampled to ~15 Hz for display; inference itself is cheap.
 * The model is loaded from a content Uri the user picks (a `.onnx` produced by
 * tools/export_onnx.py and copied to the phone).
 */
class InferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = getApplication<OmApp>().transport

    private val _state = MutableStateFlow(InferenceUiState())
    val state: StateFlow<InferenceUiState> = _state.asStateFlow()

    @Volatile
    private var runner: ModelRunner? = null
    private var job: Job? = null

    fun loadModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?: error("could not open model file")
                runner?.close()
                runner = OnnxInference.fromBytes(bytes)
                _state.update { it.copy(modelLoaded = true, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(modelLoaded = false, error = e.message ?: "load failed") }
            }
        }
    }

    fun start() {
        if (job != null || runner == null) return
        _state.update { it.copy(predicting = true) }
        job = viewModelScope.launch(Dispatchers.Default) {
            // Throttle to ~15 Hz with a manual time-gate (avoids the @FlowPreview
            // sample() operator).
            var lastMs = 0L
            transport.sensorFrames().collect { frame ->
                val now = System.currentTimeMillis()
                if (now - lastMs < SAMPLE_MS) return@collect
                lastMs = now
                val r = runner ?: return@collect
                val t0 = System.nanoTime()
                val pred = try {
                    Inference.predict(r, frame)
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message) }
                    return@collect
                }
                val latency = (System.nanoTime() - t0) / 1_000_000
                _state.update { it.copy(prediction = pred.toList(), latencyMs = latency, error = null) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(predicting = false) }
    }

    override fun onCleared() {
        stop()
        runner?.close()
        runner = null
        super.onCleared()
    }

    private companion object {
        const val SAMPLE_MS = 66L   // ~15 Hz display
    }
}
