package org.openmuscle.connect.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raw TCP control channel for a V4 FlexGrid device (services.cmd, default 8001).
 * Speaks newline-delimited JSON exactly as the firmware expects
 * (FlexGridV4-Firmware/lib/commands.py): one command object per line, one ack
 * line back, correlated by `msg_id`.
 *
 * Lifecycle: [connect] opens the socket, starts a reader, sends a `subscribe`
 * so the device begins unicasting sensor frames to our UDP port, then runs a
 * ~1 Hz `heartbeat` to hold the subscription (the device drops us after ~5 s of
 * silence). [close] best-effort `unsubscribe`s and tears the socket down.
 *
 * Only the control plane is TCP. Sensor frames still arrive over UDP on
 * [sensorPort]; this channel never touches them. The data path (UdpReceiver) is
 * unchanged and also works for V3 broadcast-style devices.
 *
 * Socket I/O is untestable in this environment (no Android/network); the message
 * shapes are covered by ControlCodecTest + tools/cmd_server.py, and the live
 * handshake is verified by tools/v4_probe.py against real hardware.
 */
class TcpControlChannel(
    private val host: String,
    private val cmdPort: Int,
    private val hubId: String,
    private val sensorPort: Int,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ControlCodec.AckLine>>()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    var connected: Boolean = false
        private set

    /**
     * Open the socket and subscribe. Returns the subscribe ack: `ok` means the
     * device accepted us and frames should start flowing to [sensorPort]. On a
     * connect failure returns a non-ok ack with the reason.
     */
    suspend fun connect(): Ack {
        try {
            withContext(dispatcher) {
                val s = Socket()
                s.connect(InetSocketAddress(host, cmdPort), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                socket = s
                writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                readerJob = scope.launch(dispatcher) { readLoop(s) }
            }
        } catch (e: Exception) {
            connected = false
            return Ack(ok = false, error = "connect failed: ${e.message}")
        }
        connected = true
        val ack = sendCommand(Command.Subscribe(port = sensorPort, hubId = hubId))
        if (ack.ok) startHeartbeat()
        return ack
    }

    suspend fun sendCommand(command: Command, ackTimeoutMs: Long = ACK_TIMEOUT_MS): Ack {
        val w = writer ?: return Ack(ok = false, error = "not connected", verb = command.verb)
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ControlCodec.AckLine>()
        pending[id] = deferred
        val line = ControlCodec.encode(hubId, id, command)
        val sent = try {
            withContext(dispatcher) {
                w.write(line)
                w.write("\n")
                w.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
        if (!sent) {
            pending.remove(id)
            return Ack(ok = false, msgId = id, error = "write failed", verb = command.verb)
        }
        val ack = withTimeoutOrNull(ackTimeoutMs) { deferred.await() }
        pending.remove(id)
        return if (ack == null) {
            Ack(ok = false, msgId = id, error = "ack timeout", verb = command.verb)
        } else {
            Ack(ok = ack.ok, msgId = ack.msgId, verb = ack.verb ?: command.verb, error = ack.message)
        }
    }

    suspend fun getInfo(ackTimeoutMs: Long = ACK_TIMEOUT_MS): DeviceInfo? {
        val w = writer ?: return null
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ControlCodec.AckLine>()
        pending[id] = deferred
        val line = ControlCodec.encode(hubId, id, Command.GetInfo)
        try {
            withContext(dispatcher) {
                w.write(line)
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            return null
        }
        val ack = withTimeoutOrNull(ackTimeoutMs) { deferred.await() }
        pending.remove(id)
        val data = ack?.data
        return if (ack != null && ack.ok && data != null) ControlCodec.parseInfo(data) else null
    }

    /** Best-effort unsubscribe, then tear down. Safe to call more than once. */
    fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        connected = false
        val w = writer
        val s = socket
        socket = null
        writer = null
        // Fire unsubscribe without awaiting an ack, then close the socket. The
        // device would drop us on heartbeat timeout anyway; this just frees the
        // slot immediately.
        scope.launch(dispatcher) {
            try {
                if (w != null) {
                    w.write(ControlCodec.encode(hubId, nextId.getAndIncrement(), Command.Unsubscribe(sensorPort)))
                    w.write("\n")
                    w.flush()
                }
            } catch (e: Exception) {
                // ignore; we are closing
            }
            try {
                s?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        readerJob?.cancel()
        readerJob = null
        // Fail any in-flight awaits so callers don't hang.
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val ack = ControlCodec.parseAckLine(line) ?: continue
                ack.msgId?.let { pending.remove(it)?.complete(ack) }
            }
        } catch (e: Exception) {
            // socket closed or read error; fall through to mark disconnected
        } finally {
            connected = false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(dispatcher) {
            while (true) {
                delay(HEARTBEAT_MS)
                sendCommand(Command.Heartbeat(port = sensorPort))
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4000
        const val ACK_TIMEOUT_MS = 2000L
        const val HEARTBEAT_MS = 1000L
    }
}
