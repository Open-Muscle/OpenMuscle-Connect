package org.openmuscle.connect.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.TransportKind

/**
 * mDNS / DNS-SD discovery, the primary Wi-Fi discovery path
 * (docs/WIRE-FORMAT.md section 8.1, ARCHITECTURE-PROPOSAL section 3.7). Resolves
 * `_openmuscle._udp` services and emits a [DiscoveredDevice] per resolution.
 *
 * The UDP broadcast-beacon fallback (for networks that block multicast) lives in
 * WiFiTransport.discover(); the device-picker merges both. mDNS needs a Context,
 * so it is a separate component rather than a TransportLayer method.
 */
class NsdDiscovery(private val context: Context) {

    fun devices(): Flow<DiscoveredDevice> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                trySend(
                    DiscoveredDevice(
                        id = serviceInfo.serviceName,
                        deviceType = txt(serviceInfo, "dev") ?: "unknown",
                        transport = TransportKind.WIFI,
                        host = serviceInfo.host?.hostAddress,
                        port = serviceInfo.port,
                    ),
                )
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // resolveService is deprecated on API 34+ in favour of
                // registerServiceInfoCallback, but works across minSdk 26-33 and
                // still functions on newer versions. Revisit if we raise minSdk.
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, resolveListener)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        awaitClose {
            try {
                nsd.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
                // already stopped
            }
        }
    }

    private fun txt(info: NsdServiceInfo, key: String): String? =
        info.attributes[key]?.toString(Charsets.UTF_8)

    private companion object {
        const val SERVICE_TYPE = "_openmuscle._udp."
    }
}
