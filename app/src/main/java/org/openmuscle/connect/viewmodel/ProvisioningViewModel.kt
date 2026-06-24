package org.openmuscle.connect.viewmodel

import android.app.Application
import android.net.Network
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmuscle.connect.OmApp
import org.openmuscle.connect.provisioning.ApPsk
import org.openmuscle.connect.provisioning.ApScanner
import org.openmuscle.connect.provisioning.ProvisionInfo
import org.openmuscle.connect.provisioning.ProvisionResult
import org.openmuscle.connect.provisioning.ProvisioningClient
import org.openmuscle.connect.provisioning.UnprovisionedDevice

enum class ProvisioningStep {
    IDLE, NEED_PERMISSION, SCANNING, PICK_DEVICE, ENTER_PSK, CONNECTING, CONFIRM_IDENTITY,
    ENTER_CREDS, PROVISIONING, WAITING_JOIN, SUCCESS, FAILURE
}

data class ProvisioningUiState(
    val step: ProvisioningStep = ProvisioningStep.IDLE,
    val devices: List<UnprovisionedDevice> = emptyList(),
    val selected: UnprovisionedDevice? = null,
    val info: ProvisionInfo? = null,
    val psk: String = "",
    val ssid: String = "",
    val password: String = "",
    val message: String? = null,
)

/**
 * Drives the WiFi provisioning onboarding (PROVISIONING.md 6): scan for the
 * device's `OM-*` SoftAP, join it (local-only), confirm identity via GET /info,
 * push the user's home credentials via POST /provision, then confirm the device
 * rejoined the LAN by hearing its announce on UDP 3140 (the dual-listen from P3).
 *
 * The activity owns the runtime location permission (needed for the Wi-Fi scan)
 * and the API-29 gate; this VM exposes [onLocationPermission] for the result. The
 * radio/HTTP work is in ProvisioningClient/ApScanner (untestable here); this is
 * the orchestration.
 */
class ProvisioningViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = getApplication<OmApp>().transport
    private val client = ProvisioningClient(app)
    private val scanner = ApScanner(app)

    private val _state = MutableStateFlow(ProvisioningUiState())
    val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

    private var network: Network? = null
    private var job: Job? = null

    /** The activity reports the location-permission result; granted starts the scan. */
    fun onLocationPermission(granted: Boolean) {
        if (granted) startScan()
        else _state.update {
            it.copy(step = ProvisioningStep.NEED_PERMISSION, message = "Location permission is needed to scan for the device's setup Wi-Fi.")
        }
    }

    fun startScan() {
        _state.update { it.copy(step = ProvisioningStep.SCANNING, message = null, devices = emptyList()) }
        job?.cancel()
        job = viewModelScope.launch {
            repeat(SCAN_ROUNDS) {
                scanner.triggerScan()
                delay(SCAN_INTERVAL_MS)
                val found = scanner.devices()
                if (found.isNotEmpty()) {
                    _state.update {
                        // Don't stomp a later step if the user already picked a device.
                        if (it.step == ProvisioningStep.SCANNING || it.step == ProvisioningStep.PICK_DEVICE) {
                            it.copy(devices = found, step = ProvisioningStep.PICK_DEVICE)
                        } else {
                            it
                        }
                    }
                }
            }
            // Show the picker even if empty, so the user gets a "none found, retry".
            _state.update { if (it.step == ProvisioningStep.SCANNING) it.copy(step = ProvisioningStep.PICK_DEVICE) else it }
        }
    }

    fun selectDevice(d: UnprovisionedDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.update { it.copy(step = ProvisioningStep.FAILURE, message = "WiFi provisioning needs Android 10 or newer.") }
            return
        }
        // The AP is WPA2-PSK (board #0127): collect the OLED code before joining.
        _state.update {
            it.copy(selected = d, psk = "", step = ProvisioningStep.ENTER_PSK, message = "Read the 10-character code on the device's screen.")
        }
    }

    fun setPsk(p: String) = _state.update { it.copy(psk = p) }

    fun submitPsk() {
        val s = _state.value
        val d = s.selected ?: return
        if (!ApPsk.isValid(s.psk)) {
            _state.update { it.copy(message = "Enter the 10-character code shown on the device screen.") }
            return
        }
        _state.update { it.copy(step = ProvisioningStep.CONNECTING, message = null) }
        job?.cancel()
        job = viewModelScope.launch {
            val net = client.connectToAp(d.ssid, s.psk)
            if (net == null) {
                client.disconnect()   // clean up the request on the onUnavailable/timeout path
                _state.update { it.copy(step = ProvisioningStep.FAILURE, message = "Could not join ${d.ssid}. Check the code and try again.") }
                return@launch
            }
            network = net
            val info = client.getInfo(net)
            if (info == null) {
                client.disconnect(); network = null
                _state.update { it.copy(step = ProvisioningStep.FAILURE, message = "Joined ${d.ssid} but the device did not respond.") }
                return@launch
            }
            // Spec 6.3: warn (not block) if the device's /info id disagrees with the
            // id-tail its setup AP advertised; a spoofed OM-* AP would fail this.
            val mismatch = info.id != d.deviceId
            _state.update {
                it.copy(
                    info = info,
                    step = ProvisioningStep.CONFIRM_IDENTITY,
                    message = if (mismatch) {
                        "Warning: this device reports id ${info.id}, but its setup network named ${d.deviceId}. Continue only if you trust it."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun confirmIdentity() = _state.update { it.copy(step = ProvisioningStep.ENTER_CREDS) }
    fun setSsid(s: String) = _state.update { it.copy(ssid = s) }
    fun setPassword(p: String) = _state.update { it.copy(password = p) }

    fun provision() {
        val net = network ?: return
        val s = _state.value
        if (s.ssid.isBlank()) {
            _state.update { it.copy(message = "Enter your Wi-Fi name (SSID).") }
            return
        }
        _state.update { it.copy(step = ProvisioningStep.PROVISIONING, message = null) }
        job?.cancel()
        job = viewModelScope.launch {
            val result = client.provision(net, s.ssid, s.password)
            client.disconnect()   // release the AP; the phone reverts to its home Wi-Fi
            network = null
            when (result) {
                is ProvisionResult.Ok -> waitForJoin(s.selected?.deviceId)
                is ProvisionResult.Error ->
                    _state.update { it.copy(step = ProvisioningStep.FAILURE, message = "Device rejected the credentials: ${result.message}") }
                null ->
                    _state.update { it.copy(step = ProvisioningStep.FAILURE, message = "No response to the credentials. Try again.") }
            }
        }
    }

    private suspend fun waitForJoin(deviceId: String?) {
        _state.update { it.copy(step = ProvisioningStep.WAITING_JOIN, message = "Waiting for the device to join your Wi-Fi...") }
        val joined = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            transport.discover().first { d -> deviceId == null || d.id == deviceId }
        }
        _state.update {
            if (joined != null) it.copy(step = ProvisioningStep.SUCCESS, message = "${joined.id} joined your Wi-Fi.")
            else it.copy(step = ProvisioningStep.FAILURE, message = "Did not see the device on your Wi-Fi within 60s. Check the password and try again.")
        }
    }

    /** Reset to the start (also releases any held AP network). */
    fun reset() {
        job?.cancel()
        client.disconnect()
        network = null
        _state.value = ProvisioningUiState()
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val SCAN_ROUNDS = 6
        const val SCAN_INTERVAL_MS = 1500L
        const val JOIN_TIMEOUT_MS = 60_000L
    }
}
