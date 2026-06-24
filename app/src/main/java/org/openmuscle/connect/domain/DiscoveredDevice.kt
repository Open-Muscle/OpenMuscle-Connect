package org.openmuscle.connect.domain

enum class TransportKind { WIFI, BLE }

/**
 * A device surfaced by discovery (phase 1.5). The phone lists these by [id]
 * plus an optional user [nickname] so several FlexGrids can be told apart
 * (see docs/ARCHITECTURE-PROPOSAL.md section 3.7).
 */
data class DiscoveredDevice(
    val id: String,
    val deviceType: String,
    val transport: TransportKind,
    val host: String? = null,
    /** UDP port the device unicasts sensor frames to (announce `services.sensor`). */
    val port: Int? = null,
    /** TCP port of the device's command channel (announce `services.cmd`). */
    val cmdPort: Int? = null,
    val rssi: Int? = null,
    val nickname: String? = null,
    /** Hub-assigned capture role (left/right/labeler); null until the user tags it. */
    val role: Role? = null,
)
