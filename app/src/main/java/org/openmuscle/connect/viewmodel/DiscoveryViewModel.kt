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
import org.openmuscle.connect.discovery.CachedDevice
import org.openmuscle.connect.discovery.DeviceCache
import org.openmuscle.connect.discovery.DeviceRegistry
import org.openmuscle.connect.discovery.NsdDiscovery
import org.openmuscle.connect.discovery.toCached
import org.openmuscle.connect.discovery.toDiscovered
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind
import org.openmuscle.connect.transport.DeviceProbe

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

    /** Wall-clock of last contact per device id, for stamping the persisted cache. */
    private val lastSeen = mutableMapOf<String, Long>()

    /**
     * Begin discovery. Called when the picker is shown so the shared UDP socket
     * and the mDNS/BLE scans only run while the user is choosing a device, not for
     * the whole app lifetime (battery). Known devices persist across stop/start.
     */
    fun start() {
        if (jobs.isNotEmpty()) return
        // Surface previously-seen devices immediately, before any live beacon. A
        // device that another hub already subscribed has gone quiet (PROTOCOL.md
        // 5.3), so the cache is the only way the user can reach it from a cold open.
        loadCache()
        // Beacon + mDNS carry a host/cmd port worth persisting; frames do not.
        jobs += viewModelScope.launch { transport.discover().collect { upsert(it, persist = true) } }
        jobs += viewModelScope.launch { nsd.devices().collect { upsert(it, persist = true) } }
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

    private fun upsert(found: DiscoveredDevice, persist: Boolean = false) {
        lastSeen[found.id] = System.currentTimeMillis()
        // Stored nickname is authoritative for display.
        _state.value = registry.upsert(found, Prefs.nickname(getApplication(), found.id))
        if (persist) persistCache()
    }

    /** Load cached devices into the picker without re-stamping their last-seen time. */
    private fun loadCache() {
        val cached = DeviceCache.decode(Prefs.deviceCacheJson(getApplication()))
        cached.forEach { c ->
            lastSeen[c.id] = c.lastSeenMs
            _state.value = registry.upsert(c.toDiscovered(), Prefs.nickname(getApplication(), c.id))
        }
        reprobeCache(cached)
    }

    /**
     * Re-probe each cached device via get_info to confirm it is still reachable at
     * its stored address even when its beacon is silent because another hub is
     * subscribed (PROTOCOL.md 10.2). Responders refresh the registry + cache;
     * non-responders stay listed (the user can still try them, and a live beacon
     * will refresh them if the device comes back announcing). The network call runs
     * on IO inside DeviceProbe; the registry mutation stays on the VM main
     * dispatcher to keep the backing map single-threaded.
     */
    private fun reprobeCache(cached: List<CachedDevice>) {
        cached.forEach { c ->
            val cmd = c.cmdPort ?: return@forEach
            jobs += viewModelScope.launch {
                val info = DeviceProbe.getInfo(c.host, cmd, PROBE_HUB_ID) ?: return@launch
                lastSeen[c.id] = System.currentTimeMillis()
                _state.value = registry.upsert(
                    c.toDiscovered().copy(deviceType = info.deviceType),
                    Prefs.nickname(getApplication(), c.id),
                )
                persistCache()
            }
        }
    }

    /** Persist the current device list (those with a known host) to the cache. */
    private fun persistCache() {
        val now = System.currentTimeMillis()
        val cached = _state.value.mapNotNull { d -> d.toCached(lastSeen[d.id] ?: now) }
        Prefs.setDeviceCacheJson(getApplication(), DeviceCache.encode(cached))
    }

    private companion object {
        // Read-only get_info probe identity; never subscribes, so this is diagnostic only.
        const val PROBE_HUB_ID = "om-android-probe"
    }
}
