package com.example.trinity.core

import android.content.Context
import com.example.trinity.model.KnowledgeChunk
import com.example.trinity.model.SDCKClassification
import com.example.trinity.model.SemanticVector
import com.example.trinity.model.StorageTier
import com.example.trinity.p2p.TrinityPeerManager
import com.example.trinity.storage.TrinityMetadataStore
import com.example.trinity.storage.TrinityStorageManager
import com.example.trinity.torrent.TorrentPieceCache
import com.example.trinity.tracker.TrinityTrackerServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

data class IngestPipelineResult(
    val chunkId: String,
    val infoHash: String,
    val kappa: Float,
    val tessaName: String,
    val tessaPermission: String,
    val tier: StorageTier,
    val sdckScore: Float,
    val pieceCount: Int,
    val totalBytes: Long,
    val storagePath: String,
    val vectorQuantization: String = "SQ8 (int8)",
    val vectorSavingsPercent: String = "74.5%",
    val vectorRawBytes: Int = 1536,
    val vectorQuantizedBytes: Int = 393
)

data class QueryResultItem(
    val chunk: KnowledgeChunk,
    val similarityScore: Float,
    val mvsScore: Float
)

class TrinityRAGServer(context: Context) {

    val semanticEngine = SemanticForceEngine(dimension = 384)
    val vectorIndex = TrinityVectorIndex(dimension = 384)
    val metadataStore = TrinityMetadataStore(context)
    val sdckCanon = SDCKCanon()
    val storageManager = TrinityStorageManager(context)
    val torrentCache = TorrentPieceCache(context, pieceSize = 256 * 1024)
    val trackerServer = TrinityTrackerServer()
    val dht = com.example.trinity.dht.KademliaDHT(localPort = 6881)
    val peerManager = TrinityPeerManager(torrentCache = torrentCache, dht = dht)

    // Direct RAM Bus: Active in-memory chunk cache for 0ms retrieval latency
    val inMemoryRAGCache = java.util.concurrent.ConcurrentHashMap<String, KnowledgeChunk>()

    private val indexFile = File(context.filesDir, "trinity_vectors.idx")

    private val _lastPipelineResult = MutableStateFlow<IngestPipelineResult?>(null)
    val lastPipelineResult: StateFlow<IngestPipelineResult?> = _lastPipelineResult.asStateFlow()

    private val _chunksFlow = MutableStateFlow<List<KnowledgeChunk>>(emptyList())
    val chunksFlow: StateFlow<List<KnowledgeChunk>> = _chunksFlow.asStateFlow()

    init {
        // Load existing index and chunks
        vectorIndex.load(indexFile)

        // Connect TorrentPieceCache directly to FAISS Vector Index in RAM
        torrentCache.onDirectMemoryVectorPush = { chunkId, content, vector, meta ->
            // Direct In-Memory Engine: Push vector floats directly into FAISS search matrix in RAM
            vectorIndex.add(chunkId, vector)
            val liveChunk = KnowledgeChunk(
                chunkId = chunkId,
                content = content,
                vector = SemanticVector(vector, dimension = vector.size, norm = 1.0f),
                tier = StorageTier.HOT,
                source = meta["source"] ?: "p2p_swarm",
                sessionId = meta["session_id"] ?: "IN_MEMORY_LIVE",
                kappa = meta["kappa"]?.toFloatOrNull() ?: 0.85f,
                tessaName = meta["tessa_name"] ?: "BENEFIT_ACCEL_EFF",
                metadata = meta
            )
            inMemoryRAGCache[chunkId] = liveChunk
            refreshChunks()
        }

        refreshChunks()

        // Sync tier migrations
        storageManager.onTierChangeListener = { objId, oldTier, newTier ->
            metadataStore.updateTier(objId, newTier)
            refreshChunks()
        }

        // Seed initial sample knowledge if empty
        if (metadataStore.getAllChunks().isEmpty()) {
            seedInitialKnowledge()
        }
    }

    fun refreshChunks() {
        val map = mutableMapOf<String, KnowledgeChunk>()
        for (c in metadataStore.getAllChunks()) {
            map[c.chunkId] = c
        }
        for ((k, c) in inMemoryRAGCache) {
            map[k] = c
        }
        _chunksFlow.value = map.values.toList()
    }

    /**
     * Executes the complete 5-layer pipeline from the architecture diagram:
     * 1. trinity_core: calculates Kappa & TESSA
     * 2. Semantic vector generation & SDCK classification
     * 3. trinity_storage: physical storage in HOT/WARM/COLD tiers
     * 4. torrent_cache: splits into 256 KB SHA-256 pieces
     * 5. p2p_protocol & tracker_server: announce to swarm & peers
     */
    fun ingest(
        content: String,
        source: String = "hub",
        sessionId: String = "SESSION-01",
        g1: Float = 0.7f,
        g2: Float = 0.5f,
        g3: Float = 0.8f,
        g4: Float = 0.2f,
        metadata: Map<String, String> = emptyMap()
    ): IngestPipelineResult {
        // Step 1: Calculate Kappa and TESSA Classification
        val kappa = TessaClassifier.kappaFromGVec(g1, g2, g3, g4)
        val tessa = TessaClassifier.classify(kappa)

        val chunkId = sha256Hex(content + source + sessionId).take(24)

        // Step 2: Semantic Vector Embedding
        val vector = semanticEngine.embed(content)

        val chunk = KnowledgeChunk(
            chunkId = chunkId,
            content = content,
            vector = vector,
            tier = tessa.tier,
            source = source,
            sessionId = sessionId,
            kappa = kappa,
            tessaName = tessa.name,
            metadata = metadata
        )

        // SDCK Canon evaluation
        val sdck = sdckCanon.classify(chunk)
        chunk.tier = sdck.tier
        chunk.confidence = sdck.confidence

        // Save to Vector Index & SQLite
        vectorIndex.add(chunkId, vector.data)
        vectorIndex.save(indexFile)
        metadataStore.storeChunk(chunk)

        // Direct In-Memory Engine: Register into active RAM cache & FAISS matrix (0ms disk wait)
        inMemoryRAGCache[chunkId] = chunk
        vectorIndex.add(chunkId, vector.data)

        // Step 3: Physical Tiered Storage (Async background write-behind)
        val storedObj = storageManager.store(
            data = content.toByteArray(Charsets.UTF_8),
            ownerId = sessionId,
            tier = chunk.tier,
            metadata = metadata
        )

        // Step 4: Split into 256 KB Torrent Pieces with SQ8 Vector Quantization
        val infoHash = torrentCache.addChunk(
            chunkId = chunkId,
            content = content,
            vectorBytes = null,
            rawVector = vector.data,
            metadata = metadata
        )

        // Step 5: Announce to Tracker & P2P Swarm
        trackerServer.announce(
            infoHash = infoHash,
            peerId = peerManager.localPeerId,
            ip = "127.0.0.1",
            port = peerManager.listenPort,
            event = "completed",
            uploaded = 0L,
            downloaded = content.length.toLong()
        )
        peerManager.announceTorrent(infoHash)

        refreshChunks()

        val result = IngestPipelineResult(
            chunkId = chunkId,
            infoHash = infoHash,
            kappa = kappa,
            tessaName = tessa.name,
            tessaPermission = tessa.permission,
            tier = chunk.tier,
            sdckScore = sdck.score,
            pieceCount = torrentCache.getTorrentMeta(infoHash)?.pieceIds?.size ?: 1,
            totalBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            storagePath = storedObj?.path ?: "storage/${chunk.tier.value}/$chunkId"
        )

        _lastPipelineResult.value = result
        return result
    }

    fun query(
        queryText: String,
        k: Int = 5,
        minKappa: Float = 0.0f,
        tierFilter: StorageTier? = null
    ): List<QueryResultItem> {
        val queryVec = semanticEngine.embed(queryText)
        val rawHits = vectorIndex.search(queryVec.data, k = k * 3)

        val results = mutableListOf<QueryResultItem>()
        for ((chunkId, sim) in rawHits) {
            val chunk = inMemoryRAGCache[chunkId] ?: metadataStore.getChunk(chunkId) ?: continue
            if (chunk.kappa < minKappa) continue
            if (tierFilter != null && chunk.tier != tierFilter) continue

            metadataStore.updateAccess(chunkId)
            chunk.accessCount++

            // Calculate Multi-Vector Significance (MVS)
            val mvs = chunk.vector?.let { semanticEngine.computeSimilarity(it, queryVec) } ?: sim
            chunk.mvsScore = mvs

            results.add(QueryResultItem(chunk, sim, mvs))
            if (results.size >= k) break
        }

        metadataStore.logQuery(queryText, "${results.size} hits")
        return results
    }

    fun buildLlmContext(queryText: String, k: Int = 5): String {
        val hits = query(queryText, k = k)
        if (hits.isEmpty()) return "Trinity RAG: No matching context located in swarm memory."

        val sb = StringBuilder()
        sb.appendLine("[Trinity Core RAG Context — ${hits.size} chunks retrieved from decentralized memory]")
        for ((idx, hit) in hits.withIndex()) {
            val c = hit.chunk
            sb.appendLine("[${idx + 1}] Source: ${c.source.uppercase()} | Tier: ${c.tier.value.uppercase()} | κ: ${"%.3f".format(c.kappa)} | Tessa: ${c.tessaName} | Sim: ${"%.1f".format(hit.similarityScore * 100)}%")
            sb.appendLine(c.content.trim())
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    private fun seedInitialKnowledge() {
        ingest(
            content = "UAGL TESSA protocol enforces deterministic tiering based on guardian kappa vectors. Benefit accelerate efficient status maps to hot storage.",
            source = "guardian",
            g1 = 0.95f, g2 = 0.85f, g3 = 0.90f, g4 = 0.80f
        )
        ingest(
            content = "Kral brotherhood memory node operates distributed vector similarity over FAISS indexes with 384-dimensional semantic force projections.",
            source = "kral",
            g1 = 0.80f, g2 = 0.70f, g3 = 0.75f, g4 = 0.60f
        )
        ingest(
            content = "BitTorrent chunking splits ingested RAG data into 256 KB content-addressable SHA-256 pieces synchronized across multi-node swarms.",
            source = "hub",
            g1 = 0.65f, g2 = 0.60f, g3 = 0.55f, g4 = 0.40f
        )
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
