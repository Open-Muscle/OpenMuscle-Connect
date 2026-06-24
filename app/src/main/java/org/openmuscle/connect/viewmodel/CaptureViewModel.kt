package org.openmuscle.connect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openmuscle.connect.OmApp
import org.openmuscle.connect.Prefs
import org.openmuscle.connect.capture.CaptureRecorder
import org.openmuscle.connect.capture.CsvV2Writer
import org.openmuscle.connect.capture.SessionInfo
import org.openmuscle.connect.capture.SessionStore
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.SensorFrame
import java.io.BufferedWriter
import java.io.FileWriter

data class CaptureUiState(
    val recording: Boolean = false,
    val rows: Long = 0,
    val matchRate: Float = 0f,
    val elapsedMs: Long = 0,
    val manualMode: Boolean = true,
    val manualLabel: List<Int> = listOf(0, 0, 0, 0),
    val sessions: List<SessionInfo> = emptyList(),
    val activeFileName: String? = null,
)

private sealed interface Ev {
    data class S(val frame: SensorFrame) : Ev
    data class L(val frame: LabelFrame) : Ev
}

/**
 * Drives a [CaptureRecorder] from the shared transport flows and writes a
 * PC-compatible CSV under app storage. Sensor and label events are merged into a
 * single collector coroutine on the IO dispatcher, so the (non-thread-safe)
 * recorder is only ever touched from one thread and file I/O stays off the main
 * thread. The recorder is created lazily on the first sensor frame so the CSV
 * header can use that frame's real matrix dimensions.
 */
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = getApplication<OmApp>().transport
    private val store = SessionStore(app)

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var recorder: CaptureRecorder? = null
    private var collectJob: Job? = null
    private var tickerJob: Job? = null
    private var startElapsed = 0L

    init {
        refreshSessions()
    }

    fun setManualMode(enabled: Boolean) = _state.update { it.copy(manualMode = enabled) }

    fun setManualLabel(index: Int, value: Int) = _state.update {
        val next = it.manualLabel.toMutableList()
        if (index in next.indices) next[index] = value
        it.copy(manualLabel = next)
    }

    fun startRecording() {
        if (_state.value.recording) return
        val file = store.newSessionFile()
        recorder = null
        startElapsed = System.currentTimeMillis()
        _state.update {
            it.copy(recording = true, rows = 0, matchRate = 0f, elapsedMs = 0, activeFileName = file.name)
        }

        collectJob = viewModelScope.launch(Dispatchers.IO) {
            merge(
                transport.labelFrames().map { Ev.L(it) },
                transport.sensorFrames().map { Ev.S(it) },
            ).collect { ev ->
                when (ev) {
                    is Ev.L -> recorder?.onLabel(ev.frame)
                    is Ev.S -> onSensorFrame(file, ev.frame)
                }
            }
        }
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val rec = recorder ?: continue
                _state.update {
                    it.copy(
                        rows = rec.matched,
                        matchRate = rec.matchRate,
                        elapsedMs = System.currentTimeMillis() - startElapsed,
                    )
                }
            }
        }
    }

    private fun onSensorFrame(file: java.io.File, frame: SensorFrame) {
        var rec = recorder
        if (rec == null) {
            val writer = CsvV2Writer(
                out = BufferedWriter(FileWriter(file)),
                rows = frame.rows,
                cols = frame.cols,
                labelCount = 4,
            )
            // The band's role tag (from the picker), defaulting to left for an
            // untagged single band; the trainer ignores role for single-role v2.
            val role = Prefs.role(getApplication(), frame.deviceId) ?: Role.LEFT
            rec = CaptureRecorder(writer, role)
            recorder = rec
        }
        // UI manual labels are ints (slider); the recorder/CSV path is float, so
        // convert at the boundary. Note: manual labels are 0..4095 (raw slider
        // scale) while LASK5 labels are [0,1] - a known scale mismatch flagged to
        // the team, not fixed here.
        rec.manualLabel =
            if (_state.value.manualMode) _state.value.manualLabel.map { it.toDouble() } else null
        rec.onSensor(frame)
        if (rec.seen % FLUSH_EVERY == 0L) rec.flush()
    }

    fun stopRecording() {
        if (!_state.value.recording) return
        val rec = recorder
        val job = collectJob
        tickerJob?.cancel()
        collectJob = null
        tickerJob = null
        recorder = null
        _state.update { it.copy(recording = false) }
        // Join the collector before finishing so the final flush/close never
        // races an in-flight row write.
        viewModelScope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            rec?.finish()
            _state.update { it.copy(sessions = store.list()) }
        }
    }

    fun deleteSession(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(name)
            _state.update { it.copy(sessions = store.list()) }
        }
    }

    private fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.list()
            _state.update { it.copy(sessions = list) }
        }
    }

    private companion object {
        const val FLUSH_EVERY = 30L
    }
}
