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
import org.openmuscle.connect.capture.CsvV2Writer
import org.openmuscle.connect.capture.MultiSourceRecorder
import org.openmuscle.connect.capture.SessionInfo
import org.openmuscle.connect.capture.SessionStore
import org.openmuscle.connect.discovery.DeviceCache
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.domain.SensorFrame
import java.io.BufferedWriter
import java.io.FileWriter

/** A role-assigned source the user tagged, with the address needed to subscribe. */
data class RoleSource(
    val deviceId: String,
    val role: Role,
    val host: String?,
    val cmdPort: Int?,
) {
    val subscribable: Boolean get() = host != null && cmdPort != null
}

data class MultiCaptureUiState(
    val recording: Boolean = false,
    val rows: Long = 0,
    val matchRate: Float = 0f,
    val elapsedMs: Long = 0,
    val sources: List<RoleSource> = emptyList(),
    val activeFileName: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val mirror: Boolean = false,
    val status: String? = null,
)

private sealed interface Ev2 {
    data class S(val frame: SensorFrame) : Ev2
    data class L(val frame: LabelFrame) : Ev2
}

/**
 * Multi-device capture: subscribes to every role-tagged device at once and writes
 * one role-tagged schema-v2 CSV via [MultiSourceRecorder] (docs/CSV-SCHEMA-V2.md).
 * Distinct from the single-source [CaptureViewModel] (manual labels, v1 CSV), which
 * stays untouched.
 *
 * Sources come from the persisted device cache (host + cmd port) intersected with
 * the per-device role tags (Prefs). The recorder is created lazily on the first
 * sensor frame so the CSV header uses that band's real dimensions. Sensor + label
 * events are merged onto one IO collector so the (non-thread-safe) recorder is only
 * touched from one thread.
 */
class MultiCaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = getApplication<OmApp>().transport
    private val store = SessionStore(app)

    private val _state = MutableStateFlow(MultiCaptureUiState())
    val state: StateFlow<MultiCaptureUiState> = _state.asStateFlow()

    private var recorder: MultiSourceRecorder? = null
    private var collectJob: Job? = null
    private var tickerJob: Job? = null
    private var startElapsed = 0L

    // Captured at start, used to write the meta sidecar at finish.
    private var activeFile: java.io.File? = null
    private var activeRoles: Map<String, Role> = emptyMap()

    init {
        refreshSources()
        refreshSessions()
    }

    /** One-limb mirroring (PROTOCOL.md 8.5): tags the capture so training does not double-mirror. */
    fun setMirror(enabled: Boolean) = _state.update { it.copy(mirror = enabled) }

    /** Role-assigned devices = cached devices (host/cmd known) that carry a role tag. */
    fun refreshSources() {
        val app = getApplication<Application>()
        val sources = DeviceCache.decode(Prefs.deviceCacheJson(app)).mapNotNull { c ->
            val role = Prefs.role(app, c.id) ?: return@mapNotNull null
            RoleSource(c.id, role, c.host, c.cmdPort)
        }
        _state.update { it.copy(sources = sources) }
    }

    fun startCapture() {
        if (_state.value.recording) return
        val sources = _state.value.sources.filter { it.subscribable }
        if (sources.none { it.role != Role.LABELER }) {
            _state.update { it.copy(status = "Tag at least one band Left or Right (with a known address) first") }
            return
        }
        val file = store.file("capture_v2_${System.currentTimeMillis()}.csv")
        val roleById = sources.associate { it.deviceId to it.role }
        activeFile = file
        activeRoles = roleById
        recorder = null
        startElapsed = System.currentTimeMillis()
        _state.update {
            it.copy(recording = true, rows = 0, matchRate = 0f, elapsedMs = 0, activeFileName = file.name, status = "subscribing")
        }

        collectJob = viewModelScope.launch(Dispatchers.IO) {
            sources.forEach { s -> transport.subscribe(s.deviceId, s.host!!, s.cmdPort!!) }
            _state.update { it.copy(status = "recording") }
            merge(
                transport.labelFrames().map { Ev2.L(it) },
                transport.sensorFrames().map { Ev2.S(it) },
            ).collect { ev ->
                when (ev) {
                    is Ev2.L -> recorder?.onLabel(ev.frame)
                    is Ev2.S -> onSensorFrame(file, roleById, ev.frame)
                }
            }
        }
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val rec = recorder ?: continue
                _state.update {
                    it.copy(rows = rec.matched, matchRate = rec.matchRate, elapsedMs = System.currentTimeMillis() - startElapsed)
                }
            }
        }
    }

    private fun onSensorFrame(file: java.io.File, roleById: Map<String, Role>, frame: SensorFrame) {
        var rec = recorder
        if (rec == null) {
            val writer = CsvV2Writer(
                out = BufferedWriter(FileWriter(file)),
                rows = frame.rows,
                cols = frame.cols,
                labelCount = null,
            )
            rec = MultiSourceRecorder(writer, roleOf = { roleById[it] })
            recorder = rec
        }
        rec.onSensor(frame)
        if (rec.seen % FLUSH_EVERY == 0L) rec.flush()
    }

    fun stopCapture() {
        if (!_state.value.recording) return
        val rec = recorder
        val job = collectJob
        val subscribed = _state.value.sources.filter { it.subscribable }.map { it.deviceId }
        tickerJob?.cancel()
        collectJob = null
        tickerJob = null
        recorder = null
        _state.update { it.copy(recording = false, status = null) }
        viewModelScope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            subscribed.forEach { transport.unsubscribe(it) }
            rec?.finish()
            writeMetaSidecar()
            _state.update { it.copy(sessions = store.list()) }
        }
    }

    /** Write the `<capture>.meta.json` sidecar next to the CSV (mirror + role map + label source). */
    private fun writeMetaSidecar() {
        val csv = activeFile ?: return
        val meta = org.openmuscle.connect.capture.CaptureMeta(
            mirror = _state.value.mirror,
            labelSource = "lask5",
            roles = activeRoles,
            createdMs = System.currentTimeMillis(),
        )
        val sidecar = java.io.File(csv.parentFile, org.openmuscle.connect.capture.CaptureMetaCodec.sidecarName(csv.name))
        runCatching { sidecar.writeText(org.openmuscle.connect.capture.CaptureMetaCodec.encode(meta)) }
        activeFile = null
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
