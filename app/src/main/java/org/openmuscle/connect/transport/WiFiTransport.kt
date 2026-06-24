package org.openmuscle.connect.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.domain.TransportKind
import org.openmuscle.connect.protocol.ParsedPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi transport. The data plane is one shared UDP socket on [SENSOR_PORT] (via
 * [UdpReceiver], bound once and fanned out): discovery beacons and sensor frames
 * arrive on the same socket, and frames are demultiplexed by device id downstream.
 *
 * The control plane is a set of TCP command channels ([TcpControlChannel]), one
 * per subscribed device, keyed by device id. V4 devices unicast sensor frames only
 * to subscribers, so a device is silent until it has a channel; V3-style broadcast
 * devices still light up the heatmap with no channel (the UDP path is unchanged).
 *
 * Two entry points:
 *  - [connectControl] is the single-device case (the heatmap): subscribe to one
 *    device, dropping any others. This is the N=1 path.
 *  - [subscribe] / [unsubscribe] add and remove individual subscriptions without
 *    touching the others, for multi-device capture (two bands + a labeler at once).
 *
 * Subscription changes are serialized by [subLock] so concurrent open/close from
 * different view models cannot race the channel map.
 */
class WiFiTransport(
    private val scope: CoroutineScope,
    receiver: UdpReceiver = UdpReceiver(),
) : TransportLayer {

    private val shared = receiver.packets()
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)

    /** Stable-ish id this hub presents to devices (used as the subscription key). */
    private val hubId: String = "om-android-" + UUID.randomUUID().toString().take(8)

    private val controls = ConcurrentHashMap<String, TcpControlChannel>()
    private val subLock = Mutex()

    /** The device the single-device interface methods (getInfo/sendCommand) target. */
    @Volatile
    private var activeDeviceId: String? = null

    private var activeSessionId: String? = null

    val controlConnected: Boolean
        get() = activeDeviceId?.let { controls[it]?.connected } == true

    /**
     * Single-device select (the heatmap): subscribe to [deviceId] and drop every
     * other subscription, so exactly one device streams. Returns the subscribe ack.
     */
    suspend fun connectControl(
        deviceId: String,
        host: String,
        cmdPort: Int,
        sensorPort: Int = SENSOR_PORT,
    ): Ack = subLock.withLock {
        controls.keys.filter { it != deviceId }.forEach { id -> controls.remove(id)?.close() }
        activeDeviceId = deviceId
        ensureSubscribedLocked(deviceId, host, cmdPort, sensorPort)
    }

    /**
     * Add a subscription for [deviceId] without touching others (multi-device
     * capture). Idempotent: a device already subscribed returns ok.
     */
    suspend fun subscribe(
        deviceId: String,
        host: String,
        cmdPort: Int,
        sensorPort: Int = SENSOR_PORT,
    ): Ack = subLock.withLock {
        ensureSubscribedLocked(deviceId, host, cmdPort, sensorPort)
    }

    /** Drop one device's subscription. */
    suspend fun unsubscribe(deviceId: String) = subLock.withLock {
        controls.remove(deviceId)?.close()
        if (activeDeviceId == deviceId) activeDeviceId = null
    }

    /** Drop every subscription. */
    suspend fun unsubscribeAll() = subLock.withLock {
        controls.values.forEach { it.close() }
        controls.clear()
        activeDeviceId = null
    }

    /** Device ids currently subscribed. */
    fun subscribedIds(): Set<String> = controls.keys.toSet()

    /** Send a command to a specific subscribed device (multi-device control). */
    suspend fun sendCommandTo(deviceId: String, command: Command): Ack =
        controls[deviceId]?.sendCommand(command) ?: Ack(ok = false, error = "not subscribed: $deviceId")

    /** Must hold [subLock]. Opens a channel if the device is not already subscribed. */
    private suspend fun ensureSubscribedLocked(
        deviceId: String,
        host: String,
        cmdPort: Int,
        sensorPort: Int,
    ): Ack {
        if (controls.containsKey(deviceId)) return Ack(ok = true, verb = "subscribe")
        val channel = TcpControlChannel(host, cmdPort, hubId, sensorPort, scope)
        controls[deviceId] = channel
        val ack = channel.connect()
        if (!ack.ok) {
            controls.remove(deviceId)
            channel.close()
        }
        return ack
    }

    override fun sensorFrames(): Flow<SensorFrame> =
        shared.filterIsInstance<ParsedPacket.Sensor>().map { it.frame }

    override fun labelFrames(): Flow<LabelFrame> =
        shared.filterIsInstance<ParsedPacket.Label>().map { it.frame }

    /** Device status currently rides on sensor-frame `meta` (the V4 status loop). */
    override fun status(): Flow<DeviceStatus> =
        sensorFrames().mapNotNull { it.status }

    /** Beacon-based Wi-Fi discovery; mDNS is handled by NsdDiscovery. */
    override fun discover(): Flow<DiscoveredDevice> =
        shared.filterIsInstance<ParsedPacket.Announce>().map { a ->
            DiscoveredDevice(
                id = a.deviceId,
                deviceType = a.deviceType,
                transport = TransportKind.WIFI,
                host = a.host,
                port = a.sensorPort,
                cmdPort = a.cmdPort,
            )
        }

    override suspend fun getInfo(): DeviceInfo? = activeDeviceId?.let { controls[it]?.getInfo() }

    override suspend fun sendCommand(command: Command): Ack =
        activeDeviceId?.let { controls[it]?.sendCommand(command) }
            ?: Ack(ok = false, error = "no active control channel")

    // Sessions are app-local: the V4 firmware has no session verb, so these just
    // track the active session id for the recording/CSV layer to stamp.
    override suspend fun startSession(meta: SessionMeta) {
        activeSessionId = meta.sessionId
    }

    override suspend fun endSession() {
        activeSessionId = null
    }

    override suspend fun close() = unsubscribeAll()

    companion object {
        const val SENSOR_PORT = 3141
    }
}
