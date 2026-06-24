package org.openmuscle.connect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openmuscle.connect.OmApp
import org.openmuscle.connect.Prefs
import org.openmuscle.connect.discovery.DeviceRegistry
import org.openmuscle.connect.discovery.NsdDiscovery
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind

/**
 * Merges the three Wi-Fi discovery signals into one deduplicated device list:
 *   1. mDNS (NsdDiscovery), the primary path,
 *   2. UDP announce beacons (WiFiTransport.discover()), the multicast-blocked
 *      fallback,
 *   3. any device id seen in an actual sensor frame, so the picker still
 *      populates today (current firmware does neither mDNS nor beacons yet).
 *
 * All collectors run on the viewModelScope's main dispatcher, so the backing
 * map is only ever touched from one thread.
 */
class DiscoveryViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = getApplication<OmApp>().transport
    private val nsd = NsdDiscovery(app)

    private val registry = DeviceRegistry()
    private val _state = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val state: StateFlow<List<DiscoveredDevice>> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()

    /**
     * Begin discovery. Called when the picker is shown so the shared UDP socket
     * and the mDNS/BLE scans only run while the user is choosing a device, not for
     * the whole app lifetime (battery). Known devices persist across stop/start.
     */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += viewModelScope.launch { transport.discover().collect { upsert(it) } }
        jobs += viewModelScope.launch { nsd.devices().collect { upsert(it) } }
        jobs += viewModelScope.launch {
            transport.sensorFrames().collect { frame ->
                upsert(
                    DiscoveredDevice(
                        id = frame.deviceId,
                        deviceType = frame.deviceType,
                        transport = TransportKind.WIFI,
                        rssi = frame.status?.rssi,
                    ),
                )
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Set or clear the friendly name for a device; persists and updates the list. */
    fun renameDevice(id: String, name: String) {
        val nick = name.trim().ifBlank { null }
        Prefs.setNickname(getApplication(), id, nick)
        _state.value = registry.rename(id, nick)
    }

    private fun upsert(found: DiscoveredDevice) {
        // Stored nickname is authoritative for display.
        _state.value = registry.upsert(found, Prefs.nickname(getApplication(), found.id))
    }
}
