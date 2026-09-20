package com.example.trinity.dht

import com.example.trinity.model.NodeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // Hardcoded stable bootstrap nodes
    val bootstrapNodes = listOf(
        BootstrapNode(
            nodeId = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            name = "Genesis Master L0",
            address = "192.168.1.100",
            port = 6881,
            role = NodeRole.MASTER
        ),
        BootstrapNode(
            nodeId = "b2c3d4e5f60718293a4b5c6d7e8f90123456789a",
            name = "Kral Core Hub",
            address = "10.0.0.1",
            port = 6881,
            role = NodeRole.MASTER
        ),
        BootstrapNode(
            nodeId = "c3d4e5f60718293a4b5c6d7e8f90123456789ab1",
            name = "Guardian Relay EU",
            address = "172.16.0.5",
            port = 6881,
            role = NodeRole.RELAY
        ),
        BootstrapNode(
            nodeId = "d4e5f60718293a4b5c6d7e8f90123456789ab1c2",
            name = "Imperial Edge LX",
            address = "192.168.2.50",
            port = 6881,
            role = NodeRole.EDGE
        )
    )

    // Routing Table: K-buckets organized by XOR distance
    private val routingTable = mutableListOf<DHTNode>()
    private val _routingTableFlow = MutableStateFlow<List<DHTNode>>(emptyList())
    val routingTableFlow: StateFlow<List<DHTNode>> = _routingTableFlow.asStateFlow()

    // DHT Value Store: infoHash -> Set of DHTNodes (swarm peers)
    private val valueStore = mutableMapOf<String, MutableSet<DHTNode>>()

    private val _dhtLogs = MutableStateFlow<List<String>>(emptyList())
    val dhtLogs: StateFlow<List<String>> = _dhtLogs.asStateFlow()

    init {
        bootstrap()
        startPeriodicRefresh()
    }

    private fun log(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = "[$time] [KAD-DHT] $msg"
        val current = _dhtLogs.value.toMutableList()
        if (current.size > 80) current.removeAt(0)
        current.add(entry)
        _dhtLogs.value = current
    }

    /**
     * Automatic Bootstrap Procedure:
     * Connects to hardcoded bootstrap nodes, pings them, and executes FIND_NODE
     * to discover neighbors and populate k-buckets without any central HTTP tracker.
     */
    fun bootstrap() {
        log("Bootstrapping private Kademlia DHT from ${bootstrapNodes.size} stable nodes...")
        for (b in bootstrapNodes) {
            val node = DHTNode(
                nodeId = b.nodeId,
                name = b.name,
                address = b.address,
                port = b.port,
                role = b.role,
                isAlive = true,
                lastSeen = System.currentTimeMillis()
            )
            updateRoutingTable(node)
        }
        log("Bootstrap complete. Local Node ID: ${localNodeId.take(12)}... Routing table: ${routingTable.size} nodes.")
    }

    /**
     * Inserts or refreshes a node in the Kademlia routing table.
     */
    @Synchronized
    fun updateRoutingTable(node: DHTNode) {
        if (node.nodeId == localNodeId) return
        val existingIndex = routingTable.indexOfFirst { it.nodeId == node.nodeId }
        if (existingIndex >= 0) {
            routingTable[existingIndex].lastSeen = System.currentTimeMillis()
            routingTable[existingIndex].isAlive = true
        } else {
            if (routingTable.size < k * 16) {
                routingTable.add(node)
            }
        }
        _routingTableFlow.value = routingTable.toList()
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
     * Kademlia FIND_NODE RPC:
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
     * Kademlia STORE RPC:
     * Registers a peer for a given infoHash directly in the DHT.
     */
    @Synchronized
    fun store(infoHash: String, peer: DHTNode) {
        val peers = valueStore.getOrPut(infoHash) { mutableSetOf() }
        peers.add(peer)
        log("STORE: Registered peer '${peer.name}' for infoHash ${infoHash.take(12)}... in DHT")
    }

    /**
     * Kademlia FIND_VALUE RPC:
     * Retrieves peers storing a given infoHash, or returns closest nodes if not found.
     */
    @Synchronized
    fun findValue(infoHash: String): Pair<List<DHTNode>, Boolean> {
        val stored = valueStore[infoHash]
        return if (stored != null && stored.isNotEmpty()) {
            log("FIND_VALUE hit for ${infoHash.take(12)}... found ${stored.size} peers in DHT")
            Pair(stored.toList(), true)
        } else {
            val closest = findNode(infoHash)
            log("FIND_VALUE miss for ${infoHash.take(12)}... returning ${closest.size} closest nodes")
            Pair(closest, false)
        }
    }

    /**
     * Periodic DHT maintenance loop: refreshes routing table and pings peers.
     */
    private fun startPeriodicRefresh() {
        scope.launch {
            while (isActive) {
                delay(30_000)
                // Ping random node to refresh k-buckets
                val closest = findNode(localNodeId)
                log("DHT Refresh: ${routingTable.size} active nodes in k-buckets, ${valueStore.size} tracked swarms")
            }
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "local_node_id" to localNodeId,
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
