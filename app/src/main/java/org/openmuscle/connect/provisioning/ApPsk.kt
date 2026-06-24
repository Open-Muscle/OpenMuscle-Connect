package org.openmuscle.connect.provisioning

/**
 * The device's WPA2 provisioning passphrase (PROVISIONING.md, board #0127): a
 * per-device RANDOM 10-char code shown on the device OLED during AP mode. It is
 * NOT derived from the SSID or device id (a derivation living in open-source
 * firmware would give no real security, since anyone in radio range reads the same
 * code and computes the same key). The user reads it off the screen and types it
 * in; the charset omits ambiguous glyphs (no l/o/0/1) for clean reading + typing.
 */
object ApPsk {

    /** Allowed characters: lowercase a..z minus l/o, digits 2..9 minus 0/1. */
    const val CHARSET = "abcdefghijkmnpqrstuvwxyz23456789"
    const val LENGTH = 10

    /**
     * True if [psk] is a structurally valid provisioning PSK (right length + charset).
     * Checked before the join to reject early; a malformed passphrase otherwise costs
     * a ~20s OS-level WPA2 auth failure before it surfaces.
     */
    fun isValid(psk: String): Boolean = psk.length == LENGTH && psk.all { it in CHARSET }
}
