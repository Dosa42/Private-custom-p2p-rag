package com.example.trinity.cloud

import com.example.trinity.core.TrinityRAGServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outbound device relay. Device credentials never enter model messages or tool arguments. */
class TrinityGatewayClient(
    private val ragServer: TrinityRAGServer,
    private val mcpBridge: TrinityMCPBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val lock = Any()
    private var generation = 0L
    private var socket: WebSocket? = null
    private var target: String? = null
    private var token: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var readyPrincipal: String? = null
    private var closed = false
    private val requests = mutableMapOf<String, Job>()
    private val responses = LinkedHashMap<String, String>()
    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _principal = MutableStateFlow<String?>(null)
    val principal: StateFlow<String?> = _principal.asStateFlow()

    fun connect(publicUrl: String, deviceToken: String) {
        require(deviceToken.isNotBlank()) { "Enter an enrolled device token" }
        require(!deviceToken.any { it == '\r' || it == '\n' }) { "Invalid device token" }
        val url = gatewayUrl(publicUrl)
        synchronized(lock) {
            check(!closed) { "Gateway client is closed" }
            disconnectLocked()
            target = url
            token = deviceToken
            reconnectAttempt = 0
            _status.value = "Connecting to gateway"
            openSocket(generation)
        }
    }

    fun disconnect() = synchronized(lock) {
        disconnectLocked()
        _status.value = "Disconnected"
    }

    fun close() {
        synchronized(lock) {
            disconnectLocked()
            closed = true
            _status.value = "Disconnected"
        }
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun disconnectLocked() {
        generation++
        target = null
        token = null
        reconnectJob?.cancel()
        reconnectJob = null
        requests.values.forEach { it.cancel() }
        requests.clear()
        responses.clear()
        readyPrincipal = null
        _principal.value = null
        socket?.close(1000, "Device disconnected")
        socket?.cancel()
        socket = null
    }

    /** Called under lock. Each listener belongs to one socket generation. */
    private fun openSocket(epoch: Long) {
        if (closed || epoch != generation) return
        val url = target ?: return
        val bearer = token ?: return
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                if (!isCurrent(webSocket, epoch)) { webSocket.cancel(); return@synchronized }
                _status.value = "Connected; awaiting authenticated device identity"
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    if (!isCurrent(webSocket, epoch)) return
                    try {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "ready" -> {
                                check(readyPrincipal == null) { "Unexpected repeated identity handshake" }
                                val identity = message.getString("principal")
                                val deviceId = message.getString("device_id")
                                require(identity.isNotBlank() && deviceId.isNotBlank()) { "Empty gateway identity" }
                                ragServer.bindAuthenticatedOwner(identity)
                                readyPrincipal = identity
                                _principal.value = identity
                                reconnectAttempt = 0
                                _status.value = "Connected: authenticated device $deviceId"
                            }
                            "request" -> executeRequest(webSocket, epoch, message)
                            "error" -> {
                                _status.value = "Gateway error: ${message.optString("message", "Request rejected") }"
                            }
                            else -> throw IllegalArgumentException("Unsupported gateway message type")
                        }
                    } catch (e: Exception) {
                        failProtocol(webSocket, e.message ?: "Invalid gateway message")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
                if (!isCurrent(webSocket, epoch)) return@synchronized
                if (code == 1008 || code == 4001 || code == 4003) {
                    target = null
                    token = null
                    readyPrincipal = null
                    _principal.value = null
                    socket = null
                    _status.value = "Gateway rejected the connection (code $code); reconnect after correcting enrollment"
                } else scheduleReconnect(epoch, "Connection closed (code $code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                if (!isCurrent(webSocket, epoch)) return@synchronized
                val statusCode = response?.code
                response?.close()
                if (statusCode == 401 || statusCode == 403) {
                    target = null
                    token = null
                    readyPrincipal = null
                    _principal.value = null
                    socket = null
                    _status.value = "Device authentication rejected (HTTP $statusCode); check enrollment token"
                } else scheduleReconnect(epoch, "Network connection failed${statusCode?.let { " (HTTP $it)" }.orEmpty()}: ${t.javaClass.simpleName}")
            }
        })
    }

    private fun executeRequest(webSocket: WebSocket, epoch: Long, message: JSONObject) {
        val id = message.getString("id")
        require(id.isNotBlank()) { "Missing request ID" }
        val paired = readyPrincipal ?: error("Device handshake is incomplete")
        if (message.optString("principal") != paired || paired != ragServer.currentOwnerId()) {
            sendError(webSocket, id, "principal_mismatch", "Request identity does not match the enrolled device owner")
            return
        }
        if (message.optString("method") != "tools/call") {
            sendError(webSocket, id, "method_not_found", "Only tools/call is supported by the device relay")
            return
        }
        responses[id]?.let { webSocket.send(it); return }
        if (requests.containsKey(id)) return
        val params = message.getJSONObject("params")
        val name = params.getString("name")
        require(!params.has("arguments") || params.opt("arguments") is JSONObject) { "Tool arguments must be an object" }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        // Prevent a repeated request ID in one connection from executing a mutation twice.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = runInterruptible { mcpBridge.handleTool(name, arguments, paired) }
            val reply = JSONObject().put("type", "response").put("id", id).put("result", result).toString()
            synchronized(lock) {
                if (epoch == generation && !closed) {
                    requests.remove(id)
                    responses[id] = reply
                    if (responses.size > 256) responses.remove(responses.keys.first())
                    val activeSocket = socket
                    if (activeSocket != null && readyPrincipal == paired && !activeSocket.send(reply)) {
                        _status.value = "Gateway send failed; connection is closing"
                    }
                }
            }
        }
        requests[id] = job
        job.start()
    }

    private fun sendError(socket: WebSocket, id: String, code: String, message: String) {
        val error = JSONObject().put("type", "response").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
        if (!socket.send(error.toString())) _status.value = "Gateway send failed; connection is closing"
    }

    private fun failProtocol(webSocket: WebSocket, reason: String) {
        target = null
        token = null
        readyPrincipal = null
        _principal.value = null
        _status.value = "Gateway protocol error: $reason"
        webSocket.close(1008, "Invalid gateway protocol")
    }

    private fun isCurrent(webSocket: WebSocket, epoch: Long) = !closed && epoch == generation && socket === webSocket

    private fun scheduleReconnect(epoch: Long, reason: String) {
        socket = null
        readyPrincipal = null
        _principal.value = null
        // Preserve in-flight operations across network reconnects. Replayed request IDs
        // attach to their existing execution instead of repeating a write.
        if (target == null || closed || reconnectJob?.isActive == true) return
        reconnectAttempt++
        val delayMs = (1_000L shl (reconnectAttempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        _status.value = "$reason; retrying in ${delayMs / 1000}s"
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                reconnectJob = null
                if (epoch == generation && target != null && !closed) openSocket(epoch)
            }
        }
    }

    companion object {
        internal fun gatewayUrl(value: String): String {
            val normalized = value.trim().replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
            val url = normalized.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a gateway URL without credentials, query or fragment" }
            require(url.isHttps || url.host in setOf("127.0.0.1", "localhost", "::1")) { "Remote gateway connections require HTTPS/WSS" }
            val path = url.encodedPath.trimEnd('/').removeSuffix("/mcp").removeSuffix("/device/ws")
            return url.newBuilder().encodedPath("$path/device/ws").build().toString()
        }
    }
}
