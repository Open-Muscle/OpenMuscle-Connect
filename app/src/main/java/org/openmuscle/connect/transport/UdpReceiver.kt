package org.openmuscle.connect.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Listens for OpenMuscle v1.0 JSON datagrams and emits parsed packets. Stamps
 * each packet with the phone's receive time, since device timestamps are not a
 * shared clock (docs/WIRE-FORMAT.md section 3).
 *
 * Two sockets during the v1.0 port-split transition (PROTOCOL.md 2, 9.3):
 *  - [port] (3141): sensor/label data frames, plus legacy announces from
 *    pre-split firmware that still broadcasts on 3141.
 *  - [announcePort] (3140): v1.0 announce beacons.
 * Both feed one flow; the parser routes by frame `type`, so an announce is
 * handled the same whichever socket it arrives on. After firmware flips its
 * announce broadcast to 3140, announces simply stop arriving on 3141 with no
 * phone change. Set [announcePort] to null (or equal to [port]) to listen on a
 * single socket.
 *
 * Collect [packets] once and fan it out (e.g. with `shareIn` in [WiFiTransport])
 * rather than collecting it many times.
 */
class UdpReceiver(
    private val port: Int = 3141,
    private val announcePort: Int? = 3140,
    private val bufferSize: Int = 8192,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun packets(): Flow<ParsedPacket> = callbackFlow {
        val sockets = mutableListOf<DatagramSocket>()
        val jobs = mutableListOf<Job>()

        // Bind explicitly (not via apply{}): inside apply{} the unqualified port
        // would resolve to DatagramSocket.getPort() (-1 when unbound).
        fun listenOn(bindPort: Int) {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 1000   // wake periodically so cancellation is prompt
            socket.bind(InetSocketAddress(bindPort))
            sockets += socket
            jobs += launch(Dispatchers.IO) {
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
        }

        listenOn(port)
        if (announcePort != null && announcePort != port) listenOn(announcePort)

        awaitClose {
            jobs.forEach { it.cancel() }
            sockets.forEach { it.close() }
        }
    }
}
