package com.example.trinity.model

import java.util.UUID

enum class StorageTier(val value: String, val label: String) {
    HOT("hot", "HOT (SSD Direct)"),
    WARM("warm", "WARM (Cached)"),
    COLD("cold", "COLD (Compressed)"),
    FROZEN("frozen", "FROZEN (Archived)");

    companion object {
        fun fromString(str: String): StorageTier =
            entries.find { it.value.equals(str, ignoreCase = true) } ?: WARM
    }
}

enum class ConfidenceLevel(val value: Double, val label: String) {
    CANON(0.95, "Canon (Uncontested)"),
    HIGH(0.85, "High Confidence"),
    MEDIUM(0.70, "Medium Confidence"),
    LOW(0.50, "Low Confidence"),
    UNCERTAIN(0.30, "Uncertain / Speculative");

    companion object {
        fun fromScore(score: Float): ConfidenceLevel = when {
            score >= 0.90f -> CANON
            score >= 0.75f -> HIGH
            score >= 0.50f -> MEDIUM
            score >= 0.30f -> LOW
            else -> UNCERTAIN
        }
    }
}

enum class NodeRole(val value: String, val level: String) {
    MASTER("master", "Level 0 - Primary Source"),
    RELAY("relay", "Level 5 - Regional Relay"),
    EDGE("edge", "Level X - Edge Cache"),
    PEER("peer", "Standard Swarm Node")
}

data class TessaResult(
    val kappa: Float,
    val name: String,
    val permission: String,
    val tier: StorageTier
)

data class SDCKClassification(
    val tier: StorageTier,
    val confidence: ConfidenceLevel,
    val score: Float,
    val reasoning: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SemanticVector(
    val data: FloatArray,
    val dimension: Int = 384,
    val norm: Float = calculateNorm(data)
) {
    companion object {
        fun calculateNorm(arr: FloatArray): Float {
            var sum = 0.0
            for (f in arr) sum += (f * f)
            val n = Math.sqrt(sum).toFloat()
            return if (n > 0f) n else 1.0f
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SemanticVector) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

data class KnowledgeChunk(
    val chunkId: String,
    val content: String,
    val vector: SemanticVector? = null,
    var tier: StorageTier = StorageTier.WARM,
    var confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val source: String = "hub",
    val sessionId: String = "",
    val kappa: Float = 0.5f,
    val tessaName: String = "BENEFIT_STABLE",
    var accessCount: Int = 0,
    var lastAccessed: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
    var mvsScore: Float = 0f
)

data class TorrentMeta(
    val infoHash: String,
    val chunkId: String,
    val pieceIds: List<String>,
    val totalSize: Long,
    val pieceSize: Int = 256 * 1024,
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

data class StoredObject(
    val id: String = UUID.randomUUID().toString(),
    var path: String,
    val sizeBytes: Long,
    val contentHash: String,
    val ownerId: String,
    var tier: StorageTier,
    val permissions: Map<String, List<String>> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    var accessedAt: Long = System.currentTimeMillis(),
    var accessCount: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
    var compressed: Boolean = false,
    var encrypted: Boolean = false,
    val tags: List<String> = emptyList()
)

data class StorageQuota(
    val userId: String,
    val maxBytes: Long = 10L * 1024L * 1024L * 1024L, // 10 GB
    var usedBytes: Long = 0L,
    val maxFiles: Int = 10000,
    var usedFiles: Int = 0
) {
    val availableBytes: Long get() = (maxBytes - usedBytes).coerceAtLeast(0L)
    val availableFiles: Int get() = (maxFiles - usedFiles).coerceAtLeast(0)
    fun canStore(size: Long): Boolean = size <= availableBytes && usedFiles < maxFiles
}

enum class P2PMessageType(val code: Int) {
    HANDSHAKE(0x00),
    WELCOME(0x01),
    KEEPALIVE(0x02),
    CHOKE(0x03),
    UNCHOKE(0x04),
    INTERESTED(0x05),
    NOT_INTERESTED(0x06),
    HAVE(0x07),
    BITFIELD(0x08),
    REQUEST(0x09),
    PIECE(0x0A),
    CANCEL(0x0B),
    QUERY(0x10),
    QUERY_RESPONSE(0x11),
    KNOWLEDGE_OFFER(0x12),
    KNOWLEDGE_REQUEST(0x13),
    KNOWLEDGE_DATA(0x14),
    SYNC_STATUS(0x20),
    SYNC_ACK(0x21),
    NEW_HASHES(0x22);

    companion object {
        fun fromCode(code: Int): P2PMessageType =
            entries.find { it.code == code } ?: KEEPALIVE
    }
}

data class P2PMessage(
    val msgType: P2PMessageType,
    val payload: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val peerId: String? = null
)

data class TrackedPeer(
    val peerId: String,
    val ip: String,
    val port: Int,
    var lastAnnounce: Long = System.currentTimeMillis(),
    var uploaded: Long = 0L,
    var downloaded: Long = 0L,
    var event: String = "started"
) {
    fun isExpired(): Boolean = (System.currentTimeMillis() - lastAnnounce) > 300_000L
}

data class AnnounceResponse(
    val interval: Int = 30,
    val peers: List<Map<String, Any>>,
    val complete: Int,
    val incomplete: Int
)

data class ScrapeStats(
    val infoHash: String,
    val complete: Int,
    val incomplete: Int,
    val downloaded: Long
)
