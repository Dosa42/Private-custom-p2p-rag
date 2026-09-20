package com.example.trinity.dht

import com.example.trinity.model.NodeRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

data class DHTNode(
    val nodeId: String, // 40-char hex string (160-bit SHA-1 / SHA-256 slice)
    val name: String,
    val address: String,
    val port: Int,
    val role: NodeRole = NodeRole.PEER,
    var isAlive: Boolean = true,
    var lastSeen: Long = System.currentTimeMillis()
)

data class BootstrapNode(
    val nodeId: String,
    val name: String,
    val address: String,
    val port: Int,
    val role: NodeRole
)

class KademliaDHT(
    val localNodeId: String = generateNodeId("LOCAL-" + UUID.randomUUID().toString()),
    val localPort: Int = 6881,
    private val k: Int = 8 // K-bucket capacity
) {

    // Manual peer transport inserts only peers after authenticated network exchange.
    val bootstrapNodes: List<BootstrapNode> = emptyList()

    // Routing Table: K-buckets organized by XOR distance
    private val routingTable = mutableListOf<DHTNode>()
    private val _routingTableFlow = MutableStateFlow<List<DHTNode>>(emptyList())
    val routingTableFlow: StateFlow<List<DHTNode>> = _routingTableFlow.asStateFlow()

    // DHT Value Store: infoHash -> Set of DHTNodes (swarm peers)
    private val valueStore = mutableMapOf<String, MutableSet<DHTNode>>()

    private val _dhtLogs = MutableStateFlow<List<String>>(emptyList())
    val dhtLogs: StateFlow<List<String>> = _dhtLogs.asStateFlow()

    private fun log(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = "[$time] [PEER-INDEX] $msg"
        val current = _dhtLogs.value.toMutableList()
        if (current.size > 80) current.removeAt(0)
        current.add(entry)
        _dhtLogs.value = current
    }

    /** This class is a local XOR routing index; network exchange lives in TrinityPeerManager. */
    fun bootstrap() {
        log("Local peer index ready; configure real peers in the swarm settings")
    }

    @Synchronized
    fun markDisconnected(address: String, port: Int) {
        routingTable.filter { it.address == address && it.port == port }.forEach { it.isAlive = false }
        valueStore.values.forEach { peers -> peers.removeAll { it.address == address && it.port == port } }
        _routingTableFlow.value = routingTable.map { it.copy() }
    }

    /**
     * Inserts or refreshes a node in the Kademlia routing table.
     */
    @Synchronized
    fun updateRoutingTable(node: DHTNode) {
        if (node.nodeId == localNodeId) return
        val existingIndex = routingTable.indexOfFirst { it.nodeId == node.nodeId }
        if (existingIndex >= 0) {
            routingTable[existingIndex] = node.copy(lastSeen = System.currentTimeMillis(), isAlive = true)
        } else {
            if (routingTable.size < k * 16) {
                routingTable.add(node)
            }
        }
        _routingTableFlow.value = routingTable.map { it.copy() }
    }

    /**
     * Calculates the XOR distance between two 160-bit hex node IDs.
     */
    fun xorDistance(id1: String, id2: String): BigInteger {
        val b1 = BigInteger(id1, 16)
        val b2 = BigInteger(id2, 16)
        return b1.xor(b2)
    }

    /**
     * Local nearest-node lookup:
     * Returns the k closest nodes to the target ID using XOR metric.
     */
    fun findNode(targetId: String): List<DHTNode> {
        val targetHex = if (targetId.length == 40) targetId else generateNodeId(targetId)
        return synchronized(this) {
            routingTable
                .filter { it.isAlive }
                .sortedBy { xorDistance(it.nodeId, targetHex) }
                .take(k)
        }
    }

    /**
     * Local torrent-to-peer registration:
     * Registers a peer for a given infoHash in this device's index.
     */
    @Synchronized
    fun store(infoHash: String, peer: DHTNode) {
        val peers = valueStore.getOrPut(infoHash) { mutableSetOf() }
        peers.add(peer)
        log("STORE: Registered peer '${peer.name}' for infoHash ${infoHash.take(12)}... in local peer index")
    }

    /**
     * Local torrent-to-peer lookup:
     * Retrieves peers storing a given infoHash, or returns closest nodes if not found.
     */
    @Synchronized
    fun findValue(infoHash: String): Pair<List<DHTNode>, Boolean> {
        val stored = valueStore[infoHash]
        return if (stored != null && stored.isNotEmpty()) {
            log("FIND_VALUE hit for ${infoHash.take(12)}... found ${stored.size} peers in local peer index")
            Pair(stored.toList(), true)
        } else {
            val closest = findNode(infoHash)
            log("FIND_VALUE miss for ${infoHash.take(12)}... returning ${closest.size} closest nodes")
            Pair(closest, false)
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "local_node_id" to localNodeId,
            "mode" to "local_authenticated_peer_index",
            "routing_table_size" to routingTable.size,
            "tracked_swarms" to valueStore.size,
            "bootstrap_nodes_count" to bootstrapNodes.size
        )
    }

    companion object {
        fun generateNodeId(input: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
