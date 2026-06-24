package org.openmuscle.connect.domain

/**
 * A label / event frame. Today this is the LASK5: 4 piston values that become
 * training labels `label_0..label_3`, plus an optional thumb joystick that is
 * used for robot-hand control rather than as a regression label by default
 * (see docs/WIRE-FORMAT.md section 2.3).
 */
data class LabelFrame(
    val deviceId: String,
    val deviceType: String,
    val values: List<Int>,
    val joystickX: Int? = null,
    val joystickY: Int? = null,
    val deviceTimestampMs: Long,
    val receiveTimeMs: Long,
)
