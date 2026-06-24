package org.openmuscle.connect.transport.ble

import java.util.UUID

/**
 * Provisional custom GATT UUIDs for the OpenMuscle BLE service
 * (docs/WIRE-FORMAT.md section 7). These are placeholders to be ratified with
 * the firmware BLE work (TECH-DECISIONS section 3); do not treat them as final.
 */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("a1b2c3d4-0001-4f4d-9d00-5345001a2b3c")
    val SENSOR_NOTIFY: UUID = UUID.fromString("a1b2c3d4-0002-4f4d-9d00-5345001a2b3c")
    val STATUS: UUID = UUID.fromString("a1b2c3d4-0003-4f4d-9d00-5345001a2b3c")
    val COMMAND: UUID = UUID.fromString("a1b2c3d4-0004-4f4d-9d00-5345001a2b3c")

    /** Standard Client Characteristic Configuration Descriptor (enables notify). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
