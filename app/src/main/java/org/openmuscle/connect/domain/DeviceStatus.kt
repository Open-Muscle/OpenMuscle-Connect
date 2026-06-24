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
)
