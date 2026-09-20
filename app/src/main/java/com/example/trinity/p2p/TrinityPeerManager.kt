package com.example.trinity.p2p

import com.example.trinity.model.NodeRole
import com.example.trinity.model.P2PMessage
import com.example.trinity.model.P2PMessageType
import com.example.trinity.torrent.TorrentPieceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class PeerNode(
    val peerId: String,
    val name: String,
    val address: String,
    val port: Int,
    val role: NodeRole,
    var isConnected: Boolean = true,
    var lastActive: Long = System.currentTimeMillis(),
    val availablePiecesCount: Int = 0,
    var sharedTorrents: Set<String> = emptySet(),
    var isChoked: Boolean = false,
    var isInterested: Boolean = true
)

class TrinityPeerManager(
    val localPeerId: String = "TRINITY-" + UUID.randomUUID().toString().substring(0, 8),
    val torrentCache: TorrentPieceCache,
    val dht: com.example.trinity.dht.KademliaDHT? = null,
    var nodeRole: NodeRole = NodeRole.MASTER,
    val listenPort: Int = 6881
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val _connectedPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = _connectedPeers.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<String>>(emptyList())
    val eventLogs: StateFlow<List<String>> = _eventLogs.asStateFlow()

    private val knownHashes = mutableSetOf<String>()
    private var syncJob: Job? = null

    init {
        // Automatically populate peers from Kademlia DHT bootstrap nodes (Zero external tracker dependency)
        val initialPeers = mutableListOf<PeerNode>()
        if (dht != null) {
            for (b in dht.bootstrapNodes) {
                initialPeers.add(
                    PeerNode(
                        peerId = b.nodeId.take(16),
                        name = b.name,
                        address = b.address,
                        port = b.port,
                        role = b.role,
                        sharedTorrents = setOf("swarm-alpha", "swarm-omega")
                    )
                )
            }
        } else {
            initialPeers.addAll(
                listOf(
                    PeerNode(
                        peerId = "PEER-GUARDIAN-01",
                        name = "Guardian Sentinel",
                        address = "192.168.1.101",
                        port = 6881,
                        role = NodeRole.RELAY,
                        sharedTorrents = setOf("swarm-alpha", "swarm-omega")
                    ),
                    PeerNode(
                        peerId = "PEER-KRAL-CORE",
                        name = "Kral Primary Hub",
                        address = "192.168.1.102",
                        port = 6882,
                        role = NodeRole.MASTER,
                        sharedTorrents = setOf("swarm-alpha")
                    )
                )
            )
        }
        _connectedPeers.value = initialPeers

        startSyncLoop()
    }

    private fun logEvent(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = "[$timestamp] $msg"
        val current = _eventLogs.value.toMutableList()
        if (current.size > 80) current.removeAt(0)
        current.add(entry)
        _eventLogs.value = current
    }

    private fun startSyncLoop() {
        syncJob = scope.launch {
            while (isActive) {
                delay(30_000L) // every 30 seconds
                syncWithPeers()
            }
        }
    }

    fun syncWithPeers() {
        val hashes = torrentCache.getAllTorrents().map { it.infoHash }
        knownHashes.addAll(hashes)

        val syncMessage = P2PMessage(
            msgType = P2PMessageType.SYNC_STATUS,
            payload = mapOf(
                "hashes" to hashes,
                "role" to nodeRole.value,
                "piece_count" to ((torrentCache.getStats()["available_pieces"] as? Int) ?: 0)
            ),
            peerId = localPeerId
        )

        logEvent("Broadcasting SYNC_STATUS (${hashes.size} torrents) to ${_connectedPeers.value.size} peers")
    }

    fun announceTorrent(infoHash: String) {
        knownHashes.add(infoHash)
        val bitfield = torrentCache.getBitfield(infoHash)
        logEvent("Announced torrent ${infoHash.take(12)}... with bitfield [${bitfield.count { it }}/${bitfield.size} pieces]")

        // Register into embedded Kademlia DHT (no external tracker required)
        dht?.store(
            infoHash = infoHash,
            peer = com.example.trinity.dht.DHTNode(
                nodeId = dht.localNodeId,
                name = "This AI Node",
                address = "127.0.0.1",
                port = listenPort,
                role = nodeRole
            )
        )

        // Update peer interested state
        val updated = _connectedPeers.value.map { peer ->
            peer.copy(
                sharedTorrents = peer.sharedTorrents + infoHash,
                lastActive = System.currentTimeMillis()
            )
        }
        _connectedPeers.value = updated
    }

    fun addManualPeer(address: String, port: Int, name: String = "Manual Peer"): PeerNode {
        val newPeer = PeerNode(
            peerId = "PEER-" + UUID.randomUUID().toString().take(8),
            name = name,
            address = address,
            port = port,
            role = NodeRole.PEER,
            isConnected = true
        )
        _connectedPeers.value = _connectedPeers.value + newPeer
        logEvent("Manually connected to peer $name ($address:$port)")
        return newPeer
    }

    fun removePeer(peerId: String) {
        _connectedPeers.value = _connectedPeers.value.filter { it.peerId != peerId }
        logEvent("Peer $peerId disconnected")
    }

    fun simulatePieceExchange(infoHash: String, onComplete: (() -> Unit)? = null) {
        val meta = torrentCache.getTorrentMeta(infoHash) ?: return
        val missing = torrentCache.getMissingPieces(infoHash)

        if (missing.isEmpty()) {
            logEvent("Torrent ${infoHash.take(12)}... is already 100% complete (Seeding)")
            onComplete?.invoke()
            return
        }

        scope.launch {
            logEvent("Requesting ${missing.size} missing pieces from peer swarm...")
            for ((idx, pieceId) in missing.withIndex()) {
                delay(350L) // Simulate network transmission
                val dummyPiece = ByteArray(meta.pieceSize) { ((idx * 37 + it) % 256).toByte() }
                // In a real swarm, the piece is received over TCP socket
                logEvent("Received piece ${pieceId.take(12)}... [Piece ${idx + 1}/${missing.size}]")
            }
            logEvent("Swarm piece download complete for ${infoHash.take(12)}!")
            onComplete?.invoke()
        }
    }

    fun stop() {
        syncJob?.cancel()
    }
}
