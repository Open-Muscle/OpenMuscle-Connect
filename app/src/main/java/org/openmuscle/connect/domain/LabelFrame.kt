package org.openmuscle.connect.domain

/**
 * A label / event frame. Today this is the LASK5: 4 calibrated finger-target
 * values that become training labels `label_0..label_3`, plus an optional thumb
 * joystick that is used for robot-hand control rather than as a regression label
 * by default (see docs/WIRE-FORMAT.md section 2.3).
 *
 * [values] are floats. Per PROTOCOL.md section 7.2 the LASK5 emits calibrated
 * finger targets in [0.0, 1.0]; the phone preserves them as-is so the gradient
 * survives into the CSV the PC trainer reads. (The joystick x/y stay integers:
 * they are raw ADC counts, not [0,1] labels.)
 */
data class LabelFrame(
    val deviceId: String,
    val deviceType: String,
    val values: List<Double>,
    val joystickX: Int? = null,
    val joystickY: Int? = null,
    val deviceTimestampMs: Long,
    val receiveTimeMs: Long,
)
