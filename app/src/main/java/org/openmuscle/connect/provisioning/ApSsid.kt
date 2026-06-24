package org.openmuscle.connect.provisioning

/**
 * A factory-fresh device discovered by its SoftAP SSID (PROVISIONING.md 3): the
 * AP is named `OM-<dev>-<id-tail>` (e.g. `OM-flexgrid-d7af0b`, `OM-lask5-01`).
 */
data class UnprovisionedDevice(
    val ssid: String,
    val dev: String,
    val idTail: String,
) {
    /** The full device id the AP advertises, e.g. `flexgrid-d7af0b`. Matches the GET /info id. */
    val deviceId: String get() = "$dev-$idTail"
}

/** Parsing for the `OM-<dev>-<id-tail>` provisioning AP SSID. Pure; see ApSsidTest. */
object ApSsid {

    const val PREFIX = "OM-"

    /** Parse a SoftAP SSID into an [UnprovisionedDevice], or null if it is not an OM-* AP. */
    fun parse(ssid: String): UnprovisionedDevice? {
        if (!ssid.startsWith(PREFIX)) return null
        val rest = ssid.removePrefix(PREFIX)
        val dash = rest.indexOf('-')
        // Need both a non-empty dev prefix and a non-empty id tail.
        if (dash <= 0 || dash >= rest.length - 1) return null
        return UnprovisionedDevice(ssid = ssid, dev = rest.substring(0, dash), idTail = rest.substring(dash + 1))
    }
}
