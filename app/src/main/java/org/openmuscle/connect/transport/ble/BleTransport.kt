package org.openmuscle.connect.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.openmuscle.connect.domain.DeviceStatus
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.LabelFrame
import org.openmuscle.connect.domain.SensorFrame
import org.openmuscle.connect.transport.Ack
import org.openmuscle.connect.transport.Command
import org.openmuscle.connect.transport.DeviceInfo
import org.openmuscle.connect.transport.SessionMeta
import org.openmuscle.connect.transport.TransportLayer

/**
 * BLE transport: connects to a device's GATT server, subscribes to the sensor
 * notify characteristic, and decodes each notification with [BleFrameCodec] into
 * the same [SensorFrame] the Wi-Fi path produces, so everything above the
 * transport is unchanged.
 *
 * STATUS: skeleton. The sensor decode path is the verified part (BleFrameCodec).
 * The GATT plumbing here is UNTESTED in this environment (no Android/radio) and
 * firmware-gated: no firmware exposes this service yet (V4 onward). The control
 * plane on BLE (status char, command char, get_info, sessions) is left as TODO
 * for when a real device exists; per WIRE-FORMAT section 9 those will likely be
 * JSON over GATT. Runtime BLE permissions must be granted before [connect];
 * the @SuppressLint covers the lack of an inline permission check here.
 *
 * Discovery (BLE scan for [BleUuids.SERVICE]) is a separate BleScanner (TODO).
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val deviceId: String,
    private val scope: CoroutineScope,
) : TransportLayer {

    private val sensor = MutableSharedFlow<SensorFrame>(extraBufferCapacity = 64)
    private var gatt: BluetoothGatt? = null

    fun connect() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(deviceAddress)
        gatt = device.connectGatt(context, false, callback)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(BleUuids.SERVICE)
                ?.getCharacteristic(BleUuids.SENSOR_NOTIFY) ?: return
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(BleUuids.CCCD) ?: return
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }

        // The 2-arg form is still invoked on API 33+ (the 3-arg form is the new
        // default). Reading characteristic.value is deprecated but works across
        // minSdk 26+. Revisit when we raise minSdk past 33.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BleUuids.SENSOR_NOTIFY) return
            val bytes = characteristic.value ?: return
            BleFrameCodec.decode(bytes, deviceId, System.currentTimeMillis())
                ?.let { sensor.tryEmit(it) }
        }
    }

    override fun sensorFrames(): Flow<SensorFrame> = sensor.asSharedFlow()

    override fun labelFrames(): Flow<LabelFrame> = emptyFlow()       // BLE LASK5: TBD
    override fun status(): Flow<DeviceStatus> = emptyFlow()          // status char: TBD
    override fun discover(): Flow<DiscoveredDevice> = emptyFlow()    // see BleScanner: TBD

    override suspend fun getInfo(): DeviceInfo? = null               // get_info over GATT: TBD
    override suspend fun sendCommand(command: Command): Ack =
        Ack(ok = false, error = "BLE command channel not implemented yet")
    override suspend fun startSession(meta: SessionMeta) {}          // TBD
    override suspend fun endSession() {}                             // TBD

    override suspend fun close() {
        gatt?.close()
        gatt = null
    }
}
