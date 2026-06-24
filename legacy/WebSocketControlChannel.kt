package org.openmuscle.connect.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.openmuscle.connect.domain.DeviceStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * PRE-V4 SNAPSHOT (not compiled). See legacy/README.md.
 *
 * OkHttp WebSocket client for a device's `/cmd` channel. Sends commands and
 * awaits acks keyed by msg_id, exposes a status flow, and runs a ~1 Hz heartbeat
 * that doubles as the Wi-Fi subscription keep-alive.
 *
 * The message shapes were verified by ControlCodecTest and tools/cmd_server.py;
 * the socket plumbing itself was untested (no Android / network). Reconnection is
 * intentionally minimal: call [connect] again after a failure. Drive suspend
 * calls from a coroutine.
 */
class WebSocketControlChannel(
    private val deviceId: String,
    private val url: String,                 // ws://host:port/cmd
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val nextId = AtomicInteger(1)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<ControlCodec.Reply.Ack>>()

    private val _status = MutableSharedFlow<DeviceStatus>(extraBufferCapacity = 16)
    val status: SharedFlow<DeviceStatus> = _status.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var pendingInfo: CompletableDeferred<DeviceInfo>? = null

    private var heartbeatJob: Job? = null

    fun connect() {
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        heartbeatJob?.cancel()
        socket?.close(NORMAL_CLOSURE, "client closing")
        socket = null
    }

    suspend fun sendCommand(command: Command, ackTimeoutMs: Long = ACK_TIMEOUT_MS): Ack {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ControlCodec.Reply.Ack>()
        pendingAcks[id] = deferred
        val sent = socket?.send(ControlCodec.encodeCommand(deviceId, id, command)) ?: false
        if (!sent) {
            pendingAcks.remove(id)
            return Ack(ok = false, msgId = id, error = "not connected")
        }
        return awaitAck(id, deferred, ackTimeoutMs)
    }

    suspend fun session(verb: String, meta: SessionMeta, ackTimeoutMs: Long = ACK_TIMEOUT_MS): Ack {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ControlCodec.Reply.Ack>()
        pendingAcks[id] = deferred
        val sent = socket?.send(ControlCodec.encodeSession(deviceId, id, verb, meta)) ?: false
        if (!sent) {
            pendingAcks.remove(id)
            return Ack(ok = false, msgId = id, error = "not connected")
        }
        return awaitAck(id, deferred, ackTimeoutMs)
    }

    suspend fun getInfo(timeoutMs: Long = ACK_TIMEOUT_MS): DeviceInfo? {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<DeviceInfo>()
        pendingInfo = deferred
        val sent = socket?.send(ControlCodec.encodeGetInfo(deviceId, id)) ?: false
        if (!sent) {
            pendingInfo = null
            return null
        }
        val info = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingInfo = null
        return info
    }

    private suspend fun awaitAck(
        id: Int,
        deferred: CompletableDeferred<ControlCodec.Reply.Ack>,
        timeoutMs: Long,
    ): Ack {
        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingAcks.remove(id)
        return if (reply == null) {
            Ack(ok = false, msgId = id, error = "ack timeout")
        } else {
            Ack(ok = reply.ok, msgId = reply.msgId, error = reply.error)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    webSocket.send("""{"v":"1.0","type":"heartbeat","id":"$deviceId"}""")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val reply = ControlCodec.parseReply(text)) {
                is ControlCodec.Reply.Ack -> reply.msgId?.let { pendingAcks.remove(it)?.complete(reply) }
                is ControlCodec.Reply.Info -> pendingInfo?.complete(reply.info)
                is ControlCodec.Reply.Status -> _status.tryEmit(reply.status)
                ControlCodec.Reply.Unknown -> {}
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val ACK_TIMEOUT_MS = 2000L
        const val HEARTBEAT_MS = 1000L
    }
}
