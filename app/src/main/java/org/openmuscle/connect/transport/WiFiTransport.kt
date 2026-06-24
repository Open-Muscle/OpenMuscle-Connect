package org.openmuscle.connect.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.domain.TransportKind
import org.openmuscle.connect.protocol.ParsedPacket
import java.util.UUID

/**
 * Wi-Fi transport. The data plane is one shared UDP socket on [SENSOR_PORT] (via
 * [UdpReceiver], bound once and fanned out): discovery beacons and sensor frames
 * arrive on the same socket. The control plane is an optional TCP command channel
 * ([TcpControlChannel]) to a discovered device, opened by [connectControl] once a
 * host and cmd port are known.
 *
 * V4 devices unicast sensor frames only to subscribers, so [connectControl] both
 * opens the control channel and subscribes us; without it, a V4 device sends
 * nothing. V3-style broadcast devices still light up the heatmap with no control
 * channel at all (the UDP path is unchanged).
 */
class WiFiTransport(
    private val scope: CoroutineScope,
    receiver: UdpReceiver = UdpReceiver(),
) : TransportLayer {

    private val shared = receiver.packets()
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)

    /** Stable-ish id this hub presents to devices (used as the subscription key). */
    private val hubId: String = "om-android-" + UUID.randomUUID().toString().take(8)

    private var control: TcpControlChannel? = null
    private var activeSessionId: String? = null

    val controlConnected: Boolean
        get() = control?.connected == true

    /**
     * Open the TCP command channel to a device and subscribe so it starts
     * unicasting sensor frames to us. [cmdPort] comes from the announce's
     * `services.cmd` (default 8001); [sensorPort] is the UDP port we receive on.
     * Suspends until the subscribe is acked. Safe to call again to switch devices.
     */
    suspend fun connectControl(
        host: String,
        cmdPort: Int,
        sensorPort: Int = SENSOR_PORT,
    ): Ack {
        closeControl()
        val channel = TcpControlChannel(host, cmdPort, hubId, sensorPort, scope)
        control = channel
        return channel.connect()
    }

    private fun closeControl() {
        control?.close()
        control = null
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

    override suspend fun getInfo(): DeviceInfo? = control?.getInfo()

    override suspend fun sendCommand(command: Command): Ack =
        control?.sendCommand(command) ?: Ack(ok = false, error = "no control channel")

    // Sessions are app-local: the V4 firmware has no session verb, so these just
    // track the active session id for the recording/CSV layer to stamp.
    override suspend fun startSession(meta: SessionMeta) {
        activeSessionId = meta.sessionId
    }

    override suspend fun endSession() {
        activeSessionId = null
    }

    override suspend fun close() {
        closeControl()
    }

    companion object {
        const val SENSOR_PORT = 3141
    }
}
