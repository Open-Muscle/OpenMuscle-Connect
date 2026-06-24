package org.openmuscle.connect.provisioning

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Talks to a factory-fresh device's SoftAP for the WiFi provisioning handshake
 * (PROVISIONING.md 4). [connectToAp] joins the `OM-*` AP as a LOCAL-ONLY network
 * via WifiNetworkSpecifier (the corrected Android API, board #0042): the phone
 * keeps its home Wi-Fi / cellular as the default network, so background data
 * survives and it reverts automatically on [disconnect]. HTTP calls are bound to
 * that network's socket factory and hit 192.168.4.1 directly (no DNS).
 *
 * The AP is WPA2-PSK (board #0127); the passphrase is a per-device random 10-char
 * code the user reads off the device OLED and types in (see [ApPsk]). It is passed
 * straight to setWpa2Passphrase, never derived.
 *
 * WifiNetworkSpecifier is API 29+; the onboarding UI gates on it. Socket/radio I/O
 * is untestable in this environment; the JSON shapes are covered by
 * ProvisioningCodecTest, and the device side by tools (firmware AP mode pending).
 */
class ProvisioningClient(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Join the device's WPA2 AP [ssid] with the OLED passphrase [psk] (local-only).
     * Returns the bound network, or null on failure/timeout (a wrong PSK surfaces
     * here as a null after the OS auth attempt). The OS shows a one-time approval
     * dialog. Hold the returned network for the handshake, then call [disconnect].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun connectToAp(ssid: String, psk: String, timeoutMs: Long = CONNECT_TIMEOUT_MS): Network? =
        withTimeoutOrNull(timeoutMs) {
            release()   // drop any prior pending request so we never leak a second callback
            suspendCancellableCoroutine { cont ->
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(psk)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { release() }
            }
        }

    /** GET /info on the device AP (the identity gate the user confirms before sending creds). */
    suspend fun getInfo(network: Network): ProvisionInfo? = withContext(Dispatchers.IO) {
        exec(network, Request.Builder().url("$BASE/info").build())?.let { ProvisioningCodec.parseInfo(it) }
    }

    /** POST /provision with the user's home credentials. */
    suspend fun provision(network: Network, ssid: String, password: String): ProvisionResult? =
        withContext(Dispatchers.IO) {
            val body = ProvisioningCodec.provisionRequest(ssid, password).toRequestBody(JSON)
            exec(network, Request.Builder().url("$BASE/provision").post(body).build())
                ?.let { ProvisioningCodec.parseProvisionAck(it) }
        }

    /** Release the AP request so the phone reverts to its home Wi-Fi. Safe to call repeatedly. */
    fun disconnect() = release()

    private fun exec(network: Network, req: Request): String? =
        runCatching {
            httpClient(network).newCall(req).execute().use { it.body?.string() }
        }.getOrNull()

    private fun httpClient(network: Network): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    private fun release() {
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
    }

    private companion object {
        const val BASE = "http://192.168.4.1"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val HTTP_TIMEOUT_S = 6L
        val JSON = "application/json".toMediaType()
    }
}
