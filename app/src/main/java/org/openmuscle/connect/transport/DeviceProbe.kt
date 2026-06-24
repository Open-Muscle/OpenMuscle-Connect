package org.openmuscle.connect.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One-shot read-only device probe: open the TCP cmd channel, send `get_info`,
 * read the ack, close. No subscribe, no heartbeat, so it leaves the device's
 * subscriber list untouched.
 *
 * This is the canonical way to confirm a cached device is still reachable at its
 * stored address when its announce beacon is silent because another hub is
 * subscribed (PROTOCOL.md 5.3 / 10.2). It mirrors the get_info step of
 * tools/v4_probe.py and reuses the unit-tested [ControlCodec] wire format.
 *
 * Socket I/O is untestable in this environment; correctness rests on ControlCodec
 * (covered by ControlCodecTest) plus the live-device check in v4_probe.py.
 */
object DeviceProbe {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 2500
    private const val PROBE_MSG_ID = 1

    /** Returns the device info on success, or null on connect/read failure or an error ack. */
    suspend fun getInfo(
        host: String,
        cmdPort: Int,
        hubId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DeviceInfo? = withContext(dispatcher) {
        var socket: Socket? = null
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, cmdPort), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            s.tcpNoDelay = true
            socket = s
            val writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
            writer.write(ControlCodec.encode(hubId, PROBE_MSG_ID, Command.GetInfo))
            writer.write("\n")
            writer.flush()
            // Read until the first ack line (skip any non-ack noise); that ack is
            // our get_info response since it's the only command we sent.
            var info: DeviceInfo? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val ack = ControlCodec.parseAckLine(line) ?: continue
                val data = ack.data
                if (ack.ok && data != null) info = ControlCodec.parseInfo(data)
                break
            }
            info
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
