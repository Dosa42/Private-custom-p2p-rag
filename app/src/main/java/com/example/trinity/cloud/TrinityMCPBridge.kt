package com.example.trinity.cloud

import com.example.trinity.core.TrinityRAGServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Authenticated, stateless local MCP endpoint. The public OAuth endpoint lives in the gateway. */
class TrinityMCPBridge(
    private val ragServer: TrinityRAGServer,
    private val requestedPort: Int = MCP_PORT
) {
    companion object {
        const val MCP_VERSION = "2026-07-28"
        const val MCP_PORT = 1456
        const val SERVER_NAME = "Trinity-Core-MCP"
        const val SERVER_VERSION = "3.0.0"
        private val SUPPORTED_VERSIONS = setOf(MCP_VERSION, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_HEADER_BYTES = 65536
    }

    val localAccessToken: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val lifecycleLock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status.asStateFlow()
    val endpoint: String get() = "http://127.0.0.1:${serverSocket?.localPort ?: requestedPort}/mcp"

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            if (serverSocket?.isClosed == false) return@synchronized true
            val listener = ServerSocket()
            try {
                listener.reuseAddress = true
                listener.bind(InetSocketAddress("127.0.0.1", requestedPort))
                val workers = Executors.newCachedThreadPool()
                serverSocket = listener
                executor = workers
                _status.value = "Listening on 127.0.0.1:${listener.localPort} (authenticated local MCP)"
                workers.execute {
                    try {
                        while (!listener.isClosed) {
                            val client = listener.accept()
                            client.soTimeout = 30_000
                            clients.add(client)
                            workers.execute { handleHttpClient(client) }
                        }
                    } catch (e: Exception) {
                        if (!listener.isClosed) _status.value = "Listener failed: ${e.message}"
                    } finally {
                        if (!listener.isClosed) listener.close()
                    }
                }
                true
            } catch (e: Exception) {
                listener.close()
                _status.value = "Unable to start MCP: ${e.message}"
                throw IllegalStateException(_status.value, e)
            }
        }
    }

    fun stopServer() = synchronized(lifecycleLock) {
        serverSocket?.close()
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        executor?.shutdownNow()
        executor = null
        _status.value = "Stopped"
    }

    /** The chat agent obtains the actual tool catalog from the local HTTP server. */
    suspend fun listTools(): JSONArray = withContext(Dispatchers.IO) {
        initializeLocalClient()
        requestLocal("tools/list", JSONObject()).getJSONArray("tools")
    }

    /** No direct RAG shortcut: every model tool call goes through authenticated MCP HTTP. */
    suspend fun callTool(
        name: String,
        arguments: JSONObject,
        principal: String = ragServer.currentOwnerId()
    ): JSONObject = withContext(Dispatchers.IO) {
        require(principal == ragServer.currentOwnerId()) { "The active account changed; retry from its current session" }
        initializeLocalClient()
        requestLocal("tools/call", JSONObject().put("name", name).put("arguments", arguments), principal)
    }

    private fun initializeLocalClient() {
        val initialized = requestLocal("initialize", JSONObject()
            .put("protocolVersion", MCP_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Trinity-Android-Chat").put("version", SERVER_VERSION)))
        check(initialized.getString("protocolVersion") in SUPPORTED_VERSIONS) { "Unsupported MCP protocol version" }
        exchangeLocal(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
    }

    private fun requestLocal(method: String, params: JSONObject, principal: String? = null): JSONObject {
        val id = UUID.randomUUID().toString()
        val wire = exchangeLocal(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), principal)
        val response = JSONObject(wire)
        check(response.optString("id") == id) { "MCP response ID mismatch" }
        response.optJSONObject("error")?.let { throw IllegalStateException("MCP ${it.optInt("code")}: ${it.optString("message")}") }
        return response.getJSONObject("result")
    }

    private fun exchangeLocal(request: JSONObject, principal: String? = null): String {
        val listener = serverSocket ?: error("Local MCP server is stopped")
        check(!listener.isClosed) { "Local MCP server is stopped" }
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", listener.localPort), 5_000)
            socket.soTimeout = 120_000
            val identity = principal?.let { "X-Trinity-Local-Owner: ${Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}\r\n" }.orEmpty()
            val headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${listener.localPort}\r\nAuthorization: Bearer $localAccessToken\r\nMCP-Protocol-Version: $MCP_VERSION\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n${identity}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readHttpLine(input) ?: throw EOFException("MCP server closed without a response")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid MCP HTTP response")
            val responseHeaders = readHeaders(input)
            val length = responseHeaders["content-length"]?.toIntOrNull() ?: 0
            val body = readBytesExactly(input, length).toString(Charsets.UTF_8)
            check(code in 200..299) { "Local MCP HTTP $code: $body" }
            return body
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(' ')
            require(parts.size == 3 && parts[2].startsWith("HTTP/1.")) { "Invalid HTTP request line" }
            val headers = readHeaders(input)
            val authorization = headers["authorization"].orEmpty()
            val bearerParts = authorization.split(Regex("\\s+"), limit = 2)
            val suppliedToken = bearerParts.getOrNull(1).orEmpty()
            if (!bearerParts.firstOrNull().equals("Bearer", ignoreCase = true) ||
                !MessageDigest.isEqual(suppliedToken.toByteArray(Charsets.UTF_8), localAccessToken.toByteArray(Charsets.UTF_8))) {
                respond(socket, 401, "Unauthorized", JSONObject().put("error", "invalid_token").toString(),
                    mapOf("WWW-Authenticate" to "Bearer realm=\"trinity-local\", error=\"invalid_token\""))
                return
            }
            if (parts[1] != "/mcp") { respond(socket, 404, "Not Found", "{\"error\":\"not_found\"}"); return }
            if (parts[0] != "POST") { respond(socket, 405, "Method Not Allowed", "{\"error\":\"method_not_allowed\"}", mapOf("Allow" to "POST")); return }
            if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                respond(socket, 415, "Unsupported Media Type", "{\"error\":\"Content-Type must be application/json\"}"); return
            }
            if (headers.containsKey("transfer-encoding")) { respond(socket, 400, "Bad Request", "{\"error\":\"Content-Length required; transfer encoding unsupported\"}"); return }
            val version = headers["mcp-protocol-version"]
            if (version != null && version !in SUPPORTED_VERSIONS) { respond(socket, 400, "Bad Request", "{\"error\":\"Unsupported MCP protocol version\"}"); return }
            val length = headers["content-length"]?.toIntOrNull()
            if (length == null || length < 0) { respond(socket, 411, "Length Required", "{\"error\":\"content_length_required\"}"); return }
            val raw = readBytesExactly(input, length).toString(Charsets.UTF_8)
            val req = try { JSONObject(raw) } catch (_: Exception) {
                respond(socket, 400, "Bad Request", rpcError(JSONObject.NULL, -32700, "Invalid JSON").toString()); return
            }
            if (req.optString("jsonrpc") != "2.0" || req.opt("method") !is String) {
                respond(socket, 400, "Bad Request", rpcError(req.opt("id"), -32600, "Invalid JSON-RPC request").toString()); return
            }
            if (!req.has("id")) { respond(socket, 202, "Accepted", ""); return }
            val principal = ragServer.currentOwnerId()
            // This header can only be used with the unguessable local bearer. It prevents an
            // in-flight local request from silently crossing an account switch.
            val expectedOwner = headers["x-trinity-local-owner"]?.let {
                String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            }
            if (expectedOwner != null && expectedOwner != principal) {
                respond(socket, 409, "Conflict", "{\"error\":\"active_account_changed\"}"); return
            }
            respond(socket, 200, "OK", dispatchRpc(req, principal).toString())
        } catch (e: Exception) {
            runCatching { respond(socket, 400, "Bad Request", JSONObject().put("error", e.message ?: "Invalid request").toString()) }
        } finally {
            clients.remove(socket)
            socket.close()
        }
    }

    private fun dispatchRpc(req: JSONObject, principal: String): JSONObject {
        val id = req.opt("id")
        if (id == null || id == JSONObject.NULL || (id !is String && id !is Number)) return rpcError(id, -32600, "Invalid request ID")
        if (req.has("params") && req.opt("params") !is JSONObject) return rpcError(id, -32602, "params must be an object")
        val params = req.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (val method = req.getString("method")) {
                "initialize" -> JSONObject().put("protocolVersion", params.optString("protocolVersion").takeIf { it in SUPPORTED_VERSIONS } ?: MCP_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()).put("resources", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolsList())
                "tools/call" -> {
                    require(!params.has("arguments") || params.opt("arguments") is JSONObject) { "Tool arguments must be an object" }
                    handleTool(params.getString("name"), params.optJSONObject("arguments") ?: JSONObject(), principal)
                }
                "resources/list" -> JSONObject().put("resources", JSONArray().put(JSONObject().put("uri", "trinity://swarm/status")
                    .put("name", "Current local swarm status").put("mimeType", "application/json")))
                "resources/read" -> {
                    if (params.optString("uri") != "trinity://swarm/status") return rpcError(id, -32002, "Resource not found")
                    JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", "trinity://swarm/status")
                        .put("mimeType", "application/json").put("text", swarmStatus(principal).toString())))
                }
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) { return rpcError(id, -32602, e.message ?: "Invalid parameters") }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    fun toolsList(): JSONArray = JSONArray().apply {
        put(tool("trinity_query", "Search this authenticated user's locally indexed RAG chunks.", JSONObject()
            .put("query", stringSchema()).put("k", JSONObject().put("type", "integer").put("minimum", 1).put("default", 5))
            .put("min_kappa", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1).put("default", 0)), listOf("query"), true))
        put(tool("trinity_ingest", "Store and index knowledge for the authenticated user.", JSONObject().put("content", stringSchema()).put("source", stringSchema()), listOf("content"), false))
        put(tool("trinity_swarm_status", "Read measured local peer and cache status.", JSONObject(), emptyList(), true))
        put(tool("trinity_sync", "Synchronize with configured authenticated peers and report transfers and failures.", JSONObject(), emptyList(), false))
        put(tool("trinity_retrieve", "Retrieve an object belonging to the authenticated user.", JSONObject().put("obj_id", stringSchema()), listOf("obj_id"), true))
        put(tool("trinity_delete", "Delete the authenticated user's stored object, index entry and cached torrent.", JSONObject().put("obj_id", stringSchema()), listOf("obj_id"), false, true))
    }

    /** Shared local/relay dispatch. principal is derived from a trusted transport, never tool arguments. */
    @Synchronized
    internal fun handleTool(name: String, arguments: JSONObject, principal: String): JSONObject = try {
        require(principal.isNotBlank() && principal == ragServer.currentOwnerId()) { "Authenticated account does not match the active device owner" }
        validateArguments(name, arguments)
        val result = when (name) {
            "trinity_query" -> {
                val k = if (arguments.has("k")) arguments.getInt("k") else 5
                val min = if (arguments.has("min_kappa")) arguments.getDouble("min_kappa").toFloat() else 0f
                val hits = ragServer.query(requiredString(arguments, "query"), k = k, minKappa = min, ownerId = principal)
                JSONObject().put("results_count", hits.size).put("chunks", JSONArray().apply {
                    hits.forEach { h -> put(JSONObject().put("chunk_id", h.chunk.chunkId).put("content", h.chunk.content)
                        .put("kappa", h.chunk.kappa).put("tessa", h.chunk.tessaName).put("source", h.chunk.source)
                        .put("similarity", h.similarityScore).put("metadata", JSONObject(h.chunk.metadata))) }
                })
            }
            "trinity_ingest" -> {
                val ingest = ragServer.ingest(requiredString(arguments, "content"), source = arguments.optString("source", "mcp"), ownerId = principal)
                JSONObject().put("chunk_id", ingest.chunkId).put("info_hash", ingest.infoHash).put("obj_id", ingest.storedObjectId)
                    .put("bytes", ingest.totalBytes).put("piece_count", ingest.pieceCount).put("kappa", ingest.kappa)
            }
            "trinity_swarm_status" -> swarmStatus(principal)
            "trinity_sync" -> {
                require(ragServer.peerManager.configuredOwnerId() == principal) { "Configure the private swarm for this authenticated user before syncing" }
                JSONObject(runBlocking { ragServer.peerManager.syncNow() })
            }
            "trinity_retrieve" -> {
                val id = requiredString(arguments, "obj_id")
                val bytes = ragServer.retrieve(id, ownerId = principal) ?: error("Object not found for authenticated user")
                JSONObject().put("obj_id", id).put("bytes", bytes.size).put("encoding", "base64")
                    .put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            "trinity_delete" -> {
                val id = requiredString(arguments, "obj_id")
                check(ragServer.delete(id, ownerId = principal)) { "Object not found for authenticated user" }
                JSONObject().put("obj_id", id).put("deleted", true)
            }
            else -> error("Tool not found: $name")
        }
        toolResult(result, name == "trinity_sync" && (result.optJSONArray("errors")?.length() ?: 0) > 0)
    } catch (e: Exception) { toolError(e.message ?: "Tool execution failed") }

    private fun swarmStatus(principal: String): JSONObject {
        val peerOwner = ragServer.peerManager.configuredOwnerId()
        val peers = if (peerOwner == null || peerOwner == principal) JSONObject(ragServer.peerManager.status())
            else JSONObject().put("configured", false).put("listening", false).put("error", "Private swarm is configured for another owner")
        return JSONObject().put("peer_transport", peers).put("cache", JSONObject(ragServer.torrentCache.getStats(principal)))
            .put("storage", JSONObject(ragServer.storageManager.getStats(principal)))
            .put("index", JSONObject(ragServer.metadataStore.getStats(principal)))
    }

    private fun validateArguments(name: String, args: JSONObject) {
        val definitions = toolsList()
        val definition = (0 until definitions.length()).map { definitions.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
            ?: error("Tool not found: $name")
        val schema = definition.getJSONObject("inputSchema")
        val properties = schema.getJSONObject("properties")
        args.keys().forEach { key ->
            require(properties.has(key)) { "Unexpected argument: $key" }
            val rule = properties.getJSONObject(key)
            val value = args.get(key)
            when (rule.getString("type")) {
                "string" -> require(value is String) { "$key must be a string" }
                "integer" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 1..Int.MAX_VALUE.toLong()) { "$key must be a positive integer" }
                "number" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1.0) { "$key must be between 0 and 1" }
            }
        }
        val required = schema.getJSONArray("required")
        for (i in 0 until required.length()) requiredString(args, required.getString(i))
    }

    private fun requiredString(args: JSONObject, key: String): String {
        val value = args.opt(key)
        require(value is String && value.isNotBlank()) { "$key must be a non-empty string" }
        return value
    }

    private fun stringSchema() = JSONObject().put("type", "string")
    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>, readOnly: Boolean, destructive: Boolean = false) =
        JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("destructiveHint", destructive).put("openWorldHint", name == "trinity_sync"))

    private fun toolResult(value: JSONObject, isError: Boolean = false) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString())))
        .put("structuredContent", value).put("isError", isError)
    private fun toolError(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message))).put("isError", true)
    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun respond(socket: Socket, code: Int, reason: String, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var bytes = 0
        while (true) {
            val line = readHttpLine(input) ?: throw EOFException("Incomplete HTTP headers")
            bytes += line.length + 2
            require(bytes <= MAX_HEADER_BYTES) { "HTTP headers too large" }
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            require(colon > 0) { "Invalid HTTP header" }
            val key = line.substring(0, colon).trim().lowercase()
            require(!headers.containsKey(key)) { "Duplicate HTTP header: $key" }
            headers[key] = line.substring(colon + 1).trim()
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (bytes.size() == 0) null else throw EOFException("Incomplete HTTP line")
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
            bytes.write(value)
            require(bytes.size() <= MAX_HEADER_BYTES) { "HTTP line too long" }
        }
    }

    private fun readBytesExactly(input: InputStream, length: Int): ByteArray {
        require(length >= 0) { "Invalid Content-Length" }
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw EOFException("Incomplete HTTP body")
            offset += count
        }
        return result
    }
}
