package org.openmuscle.connect.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmuscle.connect.protocol.OpenMuscleParser
import org.openmuscle.connect.protocol.ParsedPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listens for OpenMuscle v1.0 JSON datagrams on a UDP port (default 3141) and
 * emits parsed packets. Stamps each packet with the phone's receive time, since
 * device timestamps are not a shared clock (docs/WIRE-FORMAT.md section 3).
 *
 * One socket per collected flow. Collect [packets] once and fan it out (e.g.
 * with `shareIn` in [WiFiTransport]) rather than collecting it many times.
 */
class UdpReceiver(
    private val port: Int = 3141,
    private val bufferSize: Int = 8192,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun packets(): Flow<ParsedPacket> = callbackFlow {
        // Bind explicitly (not via apply{}): inside apply{} the unqualified
        // `port` would resolve to DatagramSocket.getPort() (-1 when unbound),
        // not this receiver's port.
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = 1000   // wake periodically so cancellation is prompt
        socket.bind(InetSocketAddress(port))
        val job = launch(Dispatchers.IO) {
            val buf = ByteArray(bufferSize)
            while (isActive) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    val parsed = OpenMuscleParser.parse(dp.data, dp.length, nowMs())
                    // Announce beacons carry no host in the JSON; fill it from
                    // the datagram source so discovery knows where to connect.
                    val out = if (parsed is ParsedPacket.Announce) {
                        parsed.copy(host = dp.address?.hostAddress)
                    } else {
                        parsed
                    }
                    if (out !is ParsedPacket.Ignored) trySend(out)
                } catch (_: SocketTimeoutException) {
                    // loop and re-check isActive
                } catch (e: Exception) {
                    if (!isActive) break
                    // transient receive error; keep listening
                }
            }
        }
        awaitClose {
            job.cancel()
            socket.close()
        }
    }
}
