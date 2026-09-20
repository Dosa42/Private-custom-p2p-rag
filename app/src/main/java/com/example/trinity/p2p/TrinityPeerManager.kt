package com.example.trinity.p2p

import com.example.trinity.dht.DHTNode
import com.example.trinity.dht.KademliaDHT
import com.example.trinity.model.NodeRole
import com.example.trinity.torrent.TorrentPieceCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.io.encoding.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.crypto.spec.SecretKeySpec

data class PeerNode(
    val peerId: String,
    val name: String,
    val address: String,
    val port: Int,
    val role: NodeRole = NodeRole.PEER,
    var isConnected: Boolean = false,
    var lastActive: Long = 0,
    val availablePiecesCount: Int = 0,
    var sharedTorrents: Set<String> = emptySet(),
    var isChoked: Boolean = false,
    var isInterested: Boolean = true
)

/** Authenticated private TCP swarm. Only configured, verified peers enter the routing index. */
class TrinityPeerManager(
    val localPeerId: String = "TRINITY-" + UUID.randomUUID().toString(),
    val torrentCache: TorrentPieceCache,
    val dht: KademliaDHT? = null,
    var nodeRole: NodeRole = NodeRole.PEER,
    val listenPort: Int = 6881
) {
    private class PeerOperationException(message: String) : IllegalStateException(message)
    private data class Network(val key: SecretKeySpec, val ownerId: String, val generation: String = UUID.randomUUID().toString())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _connectedPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = _connectedPeers.asStateFlow()
    private val _eventLogs = MutableStateFlow<List<String>>(emptyList())
    val eventLogs: StateFlow<List<String>> = _eventLogs.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    @Volatile private var network: Network? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var boundPort: Int = 0
        private set
    private var listenerJob: Job? = null
    private var syncJob: Job? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(8)
    private val syncMutex = Mutex()

    @Synchronized
    fun configureNetwork(sharedSecret: String, ownerId: String) {
        require(ownerId.isNotBlank()) { "Configure an authenticated owner before connecting peers" }
        val key = P2PCodec.deriveKey(sharedSecret)
        val current = network
        if (current != null && current.ownerId == ownerId && current.key.encoded.contentEquals(key.encoded)
            && serverSocket?.isClosed == false && _listening.value) return
        val config = Network(key, ownerId)
        closeNetwork()
        network = config
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress("0.0.0.0", listenPort))
        } catch (error: Exception) {
            listener.close()
            network = null
            logEvent("Peer listener failed: ${error.message}")
            throw error
        }
        serverSocket = listener
        boundPort = listener.localPort
        _listening.value = true
        logEvent("Authenticated TCP peer listener active on port $boundPort")
        listenerJob = scope.launch {
            try {
            while (isActive && !listener.isClosed) {
                try {
                    val socket = listener.accept()
                    if (!slots.tryAcquire()) {
                        socket.close()
                        logEvent("Peer connection rejected: listener at capacity")
                        continue
                    }
                    sockets.add(socket)
                    scope.launch {
                        try { socket.use { serve(it, config) } }
                        catch (error: Exception) { logEvent("Peer request rejected: ${error.javaClass.simpleName}: ${error.message}") }
                        finally { sockets.remove(socket); slots.release() }
                    }
                } catch (error: SocketException) {
                    if (!listener.isClosed) logEvent("Peer listener error: ${error.message}")
                }
            }
            } finally {
                if (serverSocket === listener) {
                    _listening.value = false
                    listener.close()
                }
            }
        }
        syncJob = scope.launch {
            while (isActive) {
                delay(30_000)
                syncAndReport()
            }
        }
    }

    private fun envelope(config: Network): JSONObject = JSONObject().put("protocol", 1)
        .put("peer_id", localPeerId).put("name", "Trinity ${localPeerId.takeLast(8)}")
        .put("owner_id", config.ownerId).put("listen_port", boundPort)

    private fun serve(socket: Socket, config: Network) {
        socket.soTimeout = IO_TIMEOUT
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val challenge = P2PCodec.challenge()
        output.writeInt(P2PCodec.MAGIC)
        output.write(challenge)
        output.flush()
        val request = P2PCodec.readFrame(input, config.key, challenge, "request")
        verifyEnvelope(request, config)
        check(network === config) { "Peer configuration changed" }
        recordVerifiedPeer(request, socket.inetAddress.hostAddress ?: error("Missing peer address"))
        val requestId = request.getString("request_id")
        require(requestId.length in 1..128) { "Missing peer request nonce" }
        val response = envelope(config).put("request_id", requestId)
        try {
            when (request.getString("op")) {
                "inventory" -> {
                    val offset = request.optInt("offset", 0)
                    require(offset >= 0)
                    val all = torrentCache.getAllTorrents()
                        .filter { it.metadata["owner_id"] == config.ownerId && torrentCache.isComplete(it.infoHash) }
                        .sortedBy { it.infoHash }
                    require(offset <= all.size)
                    val page = all.drop(offset).take(PAGE_SIZE)
                    response.put("hashes", JSONArray(page.map { it.infoHash }))
                    response.put("next", if (offset + page.size < all.size) offset + page.size else -1)
                    response.put("pieces", all.sumOf { it.pieceIds.size })
                }
                "metadata" -> response.put("torrent", TorrentPieceCache.toJson(ownedMeta(request, config)))
                "piece" -> {
                    val meta = ownedMeta(request, config)
                    val id = request.getString("piece_id")
                    require(id in meta.pieceIds) { "Piece is not in the authorized torrent" }
                    val data = checkNotNull(torrentCache.getPiece(id)) { "Piece unavailable" }
                    response.put("piece_id", id).put("data", Base64.encode(data))
                }
                else -> error("Unknown peer operation")
            }
            response.put("ok", true)
        } catch (error: Exception) {
            response.put("ok", false).put("error", error.message ?: "Peer request failed")
        }
        check(network === config) { "Peer configuration changed" }
        P2PCodec.writeFrame(output, config.key, challenge, "response", response)
    }

    private fun ownedMeta(request: JSONObject, config: Network) =
        checkNotNull(torrentCache.getTorrentMeta(request.getString("info_hash"))) { "Torrent not found" }.also {
            require(it.metadata["owner_id"] == config.ownerId) { "Torrent owner mismatch" }
        }

    private fun verifyEnvelope(json: JSONObject, config: Network) {
        require(json.getInt("protocol") == 1) { "Unsupported peer protocol" }
        require(json.getString("owner_id") == config.ownerId) { "Peer owner mismatch" }
        require(json.getString("peer_id").length in 1..128 && json.getString("peer_id") != localPeerId) { "Invalid peer identity" }
        require(json.getInt("listen_port") in 1..65535) { "Invalid peer listener port" }
    }

    private fun request(peer: PeerNode, operation: JSONObject, config: Network): JSONObject {
        check(network === config) { "Peer configuration changed" }
        val socket = Socket()
        sockets.add(socket)
        try {
            socket.use {
                it.connect(InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT)
                it.soTimeout = IO_TIMEOUT
                val input = DataInputStream(it.getInputStream())
                val output = DataOutputStream(it.getOutputStream())
                require(input.readInt() == P2PCodec.MAGIC) { "Not a Trinity private peer" }
                val challenge = ByteArray(32).also(input::readFully)
                val requestId = UUID.randomUUID().toString()
                val message = envelope(config).put("request_id", requestId)
                operation.keys().forEach { key -> message.put(key, operation.get(key)) }
                P2PCodec.writeFrame(output, config.key, challenge, "request", message)
                val response = P2PCodec.readFrame(input, config.key, challenge, "response")
                verifyEnvelope(response, config)
                require(response.getString("request_id") == requestId) { "Replayed or mismatched peer response" }
                require(response.getInt("listen_port") == peer.port) { "Peer listener changed" }
                check(network === config) { "Peer configuration changed" }
                recordVerifiedPeer(response, peer.address)
                if (!response.getBoolean("ok")) throw PeerOperationException(response.optString("error", "Peer operation failed"))
                return response
            }
        } catch (error: Exception) {
            if (error !is PeerOperationException) markDisconnected(peer.address, peer.port)
            throw error
        } finally { sockets.remove(socket) }
    }

    @Synchronized
    private fun recordVerifiedPeer(json: JSONObject, address: String): PeerNode {
        val peer = PeerNode(json.getString("peer_id"), json.optString("name", "Trinity Peer").take(128),
            address, json.getInt("listen_port"), NodeRole.PEER, true, System.currentTimeMillis())
        val previous = _connectedPeers.value.firstOrNull { it.address == address && it.port == peer.port }
        peer.sharedTorrents = previous?.sharedTorrents.orEmpty()
        _connectedPeers.value = _connectedPeers.value.filterNot {
            it.peerId == peer.peerId || (it.address == address && it.port == peer.port)
        } + peer
        dht?.updateRoutingTable(DHTNode(KademliaDHT.generateNodeId(peer.peerId), peer.name, address, peer.port, NodeRole.PEER))
        return peer
    }

    @Synchronized
    private fun markDisconnected(address: String, port: Int) {
        _connectedPeers.value = _connectedPeers.value.map {
            if (it.address == address && it.port == port) it.copy(isConnected = false) else it
        }
        dht?.markDisconnected(address, port)
    }

    @Synchronized
    fun addManualPeer(address: String, port: Int, name: String = "Manual Peer"): PeerNode {
        check(network != null) { "Configure the swarm owner and secret before adding peers" }
        require(address.isNotBlank() && port in 1..65535)
        val newPeer = PeerNode("pending-" + UUID.randomUUID(), name, address.trim(), port)
        _connectedPeers.value = _connectedPeers.value.filterNot { it.address == newPeer.address && it.port == port } + newPeer
        logEvent("Connecting to $name (${newPeer.address}:$port)")
        syncWithPeers()
        return newPeer
    }

    @Synchronized
    fun removePeer(peerId: String) {
        val peer = _connectedPeers.value.find { it.peerId == peerId }
        _connectedPeers.value = _connectedPeers.value.filterNot { it.peerId == peerId }
        peer?.let { dht?.markDisconnected(it.address, it.port) }
        logEvent("Removed peer $peerId")
    }

    fun syncWithPeers() { scope.launch { syncAndReport() } }

    private suspend fun syncAndReport() {
        try {
            syncNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logEvent("Sync failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    suspend fun syncNow(): Map<String, Any> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val config = checkNotNull(network) { "Private swarm is not configured" }
            var transferred = 0
            var contacted = 0
            val errors = mutableListOf<String>()
            for (peer in _connectedPeers.value.toList()) {
                try {
                    val hashes = fetchInventory(peer, config)
                    contacted++
                    for (hash in hashes) {
                        if (!torrentCache.isComplete(hash)) {
                            if (downloadFrom(peer, hash, config)) transferred++
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val message = "${peer.address}:${peer.port}: ${error.message ?: error.javaClass.simpleName}"
                    errors.add(message)
                    logEvent("Sync failed: $message")
                }
            }
            logEvent("Sync finished: $contacted authenticated peers, $transferred verified torrents, ${errors.size} errors")
            mapOf("peers_contacted" to contacted, "transferred_torrents" to transferred, "errors" to errors)
        }
    }

    private fun fetchInventory(peer: PeerNode, config: Network): Set<String> {
        val hashes = linkedSetOf<String>()
        var offset = 0
        var pieceCount = 0
        do {
            val response = request(peer, JSONObject().put("op", "inventory").put("offset", offset), config)
            val page = response.getJSONArray("hashes")
            require(page.length() <= PAGE_SIZE)
            repeat(page.length()) { index ->
                val hash = page.getString(index)
                require(Regex("[a-f0-9]{64}").matches(hash))
                hashes.add(hash)
            }
            pieceCount = response.getInt("pieces")
            val next = response.getInt("next")
            require(next == -1 || (page.length() > 0 && next == offset + page.length())) { "Invalid inventory pagination" }
            offset = next
        } while (offset != -1)
        synchronized(this) {
            _connectedPeers.value = _connectedPeers.value.map {
                if (it.address == peer.address && it.port == peer.port)
                    it.copy(sharedTorrents = hashes, availablePiecesCount = pieceCount) else it
            }
        }
        return hashes
    }

    private fun downloadFrom(peer: PeerNode, infoHash: String, config: Network): Boolean {
        val json = request(peer, JSONObject().put("op", "metadata").put("info_hash", infoHash), config).getJSONObject("torrent")
        val meta = TorrentPieceCache.fromJson(json)
        require(meta.infoHash == infoHash && meta.metadata["owner_id"] == config.ownerId) { "Torrent identity mismatch" }
        TorrentPieceCache.validateMeta(meta)
        if (torrentCache.isLocallyDeleted(meta)) return false
        torrentCache.registerTorrentMeta(meta)
        for (id in torrentCache.getMissingPieces(infoHash)) {
            val response = request(peer, JSONObject().put("op", "piece").put("info_hash", infoHash).put("piece_id", id), config)
            require(response.getString("piece_id") == id)
            val bytes = Base64.decode(response.getString("data"))
            check(torrentCache.storePiece(id, bytes)) { "Piece or torrent verification failed" }
        }
        check(torrentCache.reassembleChunk(infoHash) != null) { "Downloaded torrent failed full verification" }
        logEvent("Verified downloaded torrent ${infoHash.take(12)} (${meta.totalSize} bytes)")
        return true
    }

    fun downloadTorrent(infoHash: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                syncMutex.withLock {
                    val config = checkNotNull(network) { "Private swarm is not configured" }
                    if (!torrentCache.isComplete(infoHash)) {
                        val candidates = _connectedPeers.value.filter { infoHash in it.sharedTorrents }
                        check(candidates.isNotEmpty()) { "No authenticated peer advertises this torrent" }
                        var failure: Exception? = null
                        var downloaded = false
                        for (peer in candidates) {
                            try { downloaded = downloadFrom(peer, infoHash, config); if (downloaded) break }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failure = error }
                        }
                        check(downloaded) { failure?.message ?: "No peer completed transfer" }
                    }
                    check(torrentCache.reassembleChunk(infoHash) != null) { "Torrent is not verified" }
                    onComplete?.invoke()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logEvent("Download failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun announceTorrent(infoHash: String) {
        val config = network ?: return
        val meta = torrentCache.getTorrentMeta(infoHash) ?: return
        require(meta.metadata["owner_id"] == config.ownerId) { "Cannot announce another owner's torrent" }
        logEvent("Torrent ${infoHash.take(12)} available to authenticated peers at their next inventory request")
    }

    fun configuredOwnerId(): String? = network?.ownerId

    fun status(): Map<String, Any> = mapOf("configured" to (network != null), "listening" to _listening.value,
        "listen_port" to boundPort, "peer_count" to _connectedPeers.value.size,
        "connected_peers" to _connectedPeers.value.count { it.isConnected },
        "transport" to "trinity-tcp-aes256-gcm-v1",
        "peers" to _connectedPeers.value.map { mapOf("peer_id" to it.peerId, "address" to it.address,
            "port" to it.port, "connected" to it.isConnected, "last_active" to it.lastActive,
            "torrents" to it.sharedTorrents.size) })

    @Synchronized
    private fun logEvent(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        _eventLogs.value = (_eventLogs.value + "[$time] $message").takeLast(80)
    }

    private fun closeNetwork() {
        network = null
        syncJob?.cancel()
        listenerJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        _listening.value = false
        boundPort = 0
        _connectedPeers.value.forEach { dht?.markDisconnected(it.address, it.port) }
        _connectedPeers.value = emptyList()
    }

    @Synchronized fun stop() = closeNetwork()

    companion object {
        private const val CONNECT_TIMEOUT = 5_000
        private const val IO_TIMEOUT = 10_000
        private const val PAGE_SIZE = 128
    }
}
