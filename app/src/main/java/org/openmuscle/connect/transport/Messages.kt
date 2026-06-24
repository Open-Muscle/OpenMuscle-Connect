package org.openmuscle.connect.transport

/**
 * Control-plane command verbs, matching the V4 firmware
 * (FlexGridV4-Firmware/lib/commands.py). Every command is sent as
 * `{"v":"1.0","type":"cmd","id":<hubId>,"msg_id":<n>,"data":{"verb":<verb>,...}}`
 * over the device's TCP command channel (services.cmd, default 8001), one JSON
 * object per line. The device replies with an ack (see [Ack]).
 */
sealed interface Command {
    val verb: String

    /** Ask the device for its capabilities/firmware info. */
    data object GetInfo : Command {
        override val verb get() = "get_info"
    }

    /**
     * Tell the device to start unicasting sensor frames to us. [port] is the UDP
     * port we listen on (default 3141); [host] may be omitted, in which case the
     * device uses the TCP source address. [hubId] identifies this hub.
     */
    data class Subscribe(val port: Int, val hubId: String, val host: String? = null) : Command {
        override val verb get() = "subscribe"
    }

    data class Unsubscribe(val port: Int) : Command {
        override val verb get() = "unsubscribe"
    }

    /** Keep our subscription alive (~1 Hz); the device drops us after ~5 s without one. */
    data class Heartbeat(val port: Int) : Command {
        override val verb get() = "heartbeat"
    }

    /** Set the sensor scan interval in milliseconds (firmware accepts 5..2000). */
    data class SetScanRate(val intervalMs: Int) : Command {
        override val verb get() = "set_scan_rate"
    }

    data object StartStream : Command {
        override val verb get() = "start_stream"
    }

    data object StopStream : Command {
        override val verb get() = "stop_stream"
    }

    data object Reboot : Command {
        override val verb get() = "reboot"
    }
}

/** Parsed command ack. The firmware puts success in a top-level `status` field. */
data class Ack(val ok: Boolean, val msgId: Int? = null, val verb: String? = null, val error: String? = null)

data class DeviceInfo(
    val deviceId: String,
    val deviceType: String,
    val firmware: String? = null,
    val rows: Int? = null,
    val cols: Int? = null,
    val caps: List<String> = emptyList(),
    val subscriberCount: Int? = null,
)

/**
 * Metadata for a labeled capture session. This is an app-local concept: the V4
 * firmware has no `session` verb, so the phone records this alongside the CSV it
 * builds, it is not sent to the device. Kept here because the transport layer
 * still exposes start/end hooks for the recording layer to call.
 */
data class SessionMeta(
    val sessionId: String,
    val user: String? = null,
    val location: String? = null,
    val intent: String? = null,
)
