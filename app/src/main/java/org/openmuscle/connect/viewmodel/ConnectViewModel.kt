package org.openmuscle.connect.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openmuscle.connect.OmApp
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.transport.Command
import org.openmuscle.connect.transport.WiFiTransport
import kotlin.math.roundToInt

/** UI state for the home (heatmap) screen. */
data class ConnectUiState(
    val listening: Boolean = false,
    val frame: SensorFrame? = null,
    val status: DeviceStatus? = null,
    val hz: Float = 0f,
    val packetCount: Long = 0,
    val deviceId: String? = null,
    val controlConnected: Boolean = false,
    val scanRate: Int = 59,
    val lastCommand: String? = null,
)

/**
 * Collects FlexGrid frames from the shared Wi-Fi transport and exposes them as
 * UI state. When [setFilter] names a device, frames from other devices are
 * dropped so the heatmap shows only the selected one. Hz is computed over a
 * one-second sliding window of receive times.
 */
class ConnectViewModel(app: Application) : AndroidViewModel(app) {

    private val link: WiFiTransport = getApplication<OmApp>().transport

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val hzMeter = HzMeter()
    private var collectJob: Job? = null
    private var deviceFilter: String? = null

    // Last reset_cause_name logged for the current device/session, so we log once
    // per (re)connect or reboot instead of every 1 Hz status frame (PROTOCOL.md 7.4).
    private var lastResetCause: String? = null

    /** Limit the heatmap to one device id, or null for any device. */
    fun setFilter(deviceId: String?) {
        deviceFilter = deviceId
        hzMeter.reset()
        lastResetCause = null
        _state.update {
            it.copy(frame = null, hz = 0f, packetCount = 0, deviceId = deviceId)
        }
    }

    /**
     * Filter the heatmap to a device and, if its host and cmd port are known
     * (from a beacon), open the TCP control channel and subscribe so a V4 device
     * starts unicasting frames to us. V4 sends nothing until we subscribe; a
     * V3-style broadcast device lights up the heatmap even without this.
     */
    fun selectDevice(device: DiscoveredDevice) {
        setFilter(device.id)
        val host = device.host
        val cmdPort = device.cmdPort
        if (host != null && cmdPort != null) {
            _state.update { it.copy(lastCommand = "subscribing...") }
            viewModelScope.launch {
                val ack = link.connectControl(device.id, host, cmdPort, device.port ?: 3141)
                _state.update {
                    it.copy(
                        controlConnected = ack.ok,
                        lastCommand = if (ack.ok) "subscribed" else (ack.error ?: "subscribe failed"),
                    )
                }
            }
        } else {
            _state.update { it.copy(controlConnected = false) }
        }
    }

    fun startStream() = sendCmd(Command.StartStream, "start_stream")

    fun stopStream() = sendCmd(Command.StopStream, "stop_stream")

    /**
     * UI works in Hz; the V4 firmware takes a scan interval in ms (5..2000). Keep
     * the Hz for display and convert on the way out.
     */
    fun setScanRate(hz: Int) {
        val clampedHz = hz.coerceIn(1, 200)
        _state.update { it.copy(scanRate = clampedHz) }
        val intervalMs = (1000.0 / clampedHz).roundToInt().coerceIn(5, 2000)
        sendCmd(Command.SetScanRate(intervalMs), "set_scan_rate ${clampedHz}Hz (${intervalMs}ms)")
    }

    private fun sendCmd(command: Command, label: String) {
        viewModelScope.launch {
            val ack = link.sendCommand(command)
            val result = if (ack.ok) "ok" else (ack.error ?: "failed")
            _state.update { it.copy(lastCommand = "$label: $result") }
        }
    }

    fun start() {
        if (collectJob != null) return
        _state.update { it.copy(listening = true) }
        collectJob = viewModelScope.launch {
            link.sensorFrames().collect { onFrame(it) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        hzMeter.reset()
        lastResetCause = null
        _state.update { it.copy(listening = false, hz = 0f) }
    }

    private fun onFrame(frame: SensorFrame) {
        val filter = deviceFilter
        if (filter != null && frame.deviceId != filter) return

        logResetCauseOnce(frame)

        val hz = hzMeter.record(frame.receiveTimeMs)
        _state.update {
            it.copy(
                frame = frame,
                status = frame.status ?: it.status,
                hz = hz,
                packetCount = it.packetCount + 1,
                deviceId = frame.deviceId,
            )
        }
    }

    /**
     * Log the device's last reset cause once per (re)connect so a silent watchdog
     * reset is visible instead of looking like a mystery dropout (PROTOCOL.md 7.4
     * SHOULD). wdt/hard resets log at WARN so they stand out in logcat.
     */
    private fun logResetCauseOnce(frame: SensorFrame) {
        val status = frame.status ?: return
        val cause = status.resetCauseName ?: return
        if (cause == lastResetCause) return
        lastResetCause = cause
        val msg = "device ${frame.deviceId} last reset: $cause (code ${status.resetCause})"
        if (cause == "wdt" || cause == "hard") Log.w(TAG, msg) else Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "OmStatus"
    }
}
