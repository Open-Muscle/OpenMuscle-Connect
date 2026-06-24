package org.openmuscle.connect.provisioning

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager

/**
 * Scans for unprovisioned devices by their `OM-*` SoftAP SSID (PROVISIONING.md 6
 * step 1): kicks a Wi-Fi scan and filters the results through [ApSsid].
 *
 * The Wi-Fi scan needs ACCESS_FINE_LOCATION + location services on (all API levels
 * since 29); the onboarding flow requests the runtime permission first, so a
 * missing-permission SecurityException here is swallowed to an empty list.
 *
 * scanResults / startScan are deprecated since API 28 but remain the portable way
 * to enumerate nearby APs across minSdk 26+. Untestable in this environment; the
 * SSID parsing is covered by ApSsidTest.
 */
class ApScanner(context: Context) {

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Kick a fresh scan; results arrive asynchronously and are read via [devices]. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun triggerScan(): Boolean = runCatching { wifi?.startScan() ?: false }.getOrDefault(false)

    /** OM-* APs from the latest scan results, de-duplicated by SSID. Empty without permission. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun devices(): List<UnprovisionedDevice> =
        runCatching {
            (wifi?.scanResults ?: emptyList())
                .mapNotNull { ApSsid.parse(it.SSID ?: "") }
                .distinctBy { it.ssid }
        }.getOrElse { emptyList() }
}
