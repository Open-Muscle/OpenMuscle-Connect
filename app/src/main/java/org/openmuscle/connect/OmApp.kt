package org.openmuscle.connect

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openmuscle.connect.transport.WiFiTransport

/**
 * Application holder for the single app-scoped Wi-Fi transport. Discovery and
 * the heatmap must share ONE inbound UDP socket on 3141; binding it twice would
 * race for packets. The socket only stays open while something collects (the
 * transport uses SharingStarted.WhileSubscribed), so an idle app holds nothing.
 */
class OmApp : Application() {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport: WiFiTransport by lazy { WiFiTransport(appScope) }
}
