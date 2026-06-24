package org.openmuscle.connect.transport

import kotlinx.coroutines.flow.Flow
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame

/**
 * Transport-agnostic link to a device. Both [WiFiTransport] and (phase 3)
 * BleTransport implement this, so everything above the transport (heatmap,
 * capture, inference) never knows which radio is active.
 *
 * Phase 1 implements [discover] (stub), [sensorFrames], [labelFrames], and
 * [status]. The command and session methods are specced now
 * (docs/WIRE-FORMAT.md section 8) and are implemented in phase 2.5 on Wi-Fi and
 * phase 3 on BLE.
 */
interface TransportLayer {
    fun discover(): Flow<DiscoveredDevice>
    fun sensorFrames(): Flow<SensorFrame>
    fun labelFrames(): Flow<LabelFrame>
    fun status(): Flow<DeviceStatus>

    suspend fun getInfo(): DeviceInfo?
    suspend fun sendCommand(command: Command): Ack
    suspend fun startSession(meta: SessionMeta)
    suspend fun endSession()
    suspend fun close()
}
