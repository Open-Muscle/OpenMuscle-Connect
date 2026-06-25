package org.openmuscle.connect.domain

/**
 * Telemetry carried in a FlexGrid packet's `meta` object. Every field is
 * optional; firmware attaches `meta` only about once per second, so a given
 * frame may have no status at all (see docs/WIRE-FORMAT.md section 4).
 */
data class DeviceStatus(
    val batteryPct: Int? = null,
    val vbat: Double? = null,
    val rssi: Int? = null,
    val freeMem: Long? = null,
    val uptimeS: Long? = null,
    val scanRateHz: Int? = null,
    /** machine.reset_cause() integer; pairs with [resetCauseName] (PROTOCOL.md 7.4). */
    val resetCause: Int? = null,
    /** Human form of the last reset: pwr / hard / soft / wdt / deep. Logged on reconnect. */
    val resetCauseName: String? = null,
    /** ICM-42688-P snapshot from the status meta `imu` block, when the device has one. */
    val imu: ImuSnapshot? = null,
)

/**
 * IMU snapshot carried in the status meta `imu` block (PROTOCOL.md 7.4). accel/gyro
 * are raw signed-16-bit counts as the firmware emits them (gyro full-scale +/-1000 dps
 * on the InvenSense variant), refreshed at the status cadence (~5 s), not the frame rate.
 */
data class ImuSnapshot(
    val variant: String? = null,
    val accel: List<Int> = emptyList(),
    val gyro: List<Int> = emptyList(),
    val tempC: Double? = null,
)
