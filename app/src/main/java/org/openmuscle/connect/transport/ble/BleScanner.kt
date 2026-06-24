package org.openmuscle.connect.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind

/**
 * Scans for devices advertising the OpenMuscle BLE service
 * (docs/WIRE-FORMAT.md section 8.1) and emits a [DiscoveredDevice] per hit, with
 * the MAC address in [DiscoveredDevice.host] so a [BleTransport] can connect to it.
 *
 * UNTESTED here (no Android/radio) and firmware-gated: nothing advertises the
 * service yet (V4 onward). Requires the BLUETOOTH_SCAN runtime permission; if it
 * is not granted, startScan throws and the flow simply stays empty. Not yet
 * wired into the device picker, which needs the connection-mode (Wi-Fi vs BLE)
 * selection; see BUILD-LOG.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    fun devices(): Flow<DiscoveredDevice> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                trySend(
                    DiscoveredDevice(
                        id = device.name ?: device.address,
                        deviceType = "flexgrid",
                        transport = TransportKind.BLE,
                        host = device.address,
                        rssi = result.rssi,
                    ),
                )
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // permission revoked or adapter turned off mid-scan
            }
        }
    }
}
