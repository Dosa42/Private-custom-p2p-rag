package com.example.trinity.core

import android.content.Context
import com.example.trinity.model.KnowledgeChunk
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
import java.security.MessageDigest
import java.util.UUID

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
    val vectorQuantizedBytes: Int = 393,
    val storedObjectId: String = ""
)

data class QueryResultItem(val chunk: KnowledgeChunk, val similarityScore: Float, val mvsScore: Float)

/** Local RAG execution. Network adapters supply an authenticated owner independently of a session. */
class TrinityRAGServer(context: Context) {
    private val identity = context.getSharedPreferences("trinity_owner", Context.MODE_PRIVATE)
    private val localOwnerId: String = identity.getString("local_owner_id", null) ?: run {
        val generated = "local:" + UUID.randomUUID()
        check(identity.edit().putString("local_owner_id", generated).commit()) { "Cannot persist local owner" }
        generated
    }

    fun currentOwnerId(): String = identity.getString("current_owner_id", localOwnerId) ?: localOwnerId

    val semanticEngine = SemanticForceEngine(dimension = 384)
    val vectorIndex = TrinityVectorIndex(dimension = 384)
    val metadataStore = TrinityMetadataStore(context, legacyOwnerId = localOwnerId)
    val sdckCanon = SDCKCanon()
    val storageManager = TrinityStorageManager(context, ownerProvider = ::currentOwnerId)
    val torrentCache = TorrentPieceCache(context, pieceSize = 256 * 1024)
    val trackerServer = TrinityTrackerServer()
    val dht = com.example.trinity.dht.KademliaDHT(localPort = 6881)
    val peerManager = TrinityPeerManager(torrentCache = torrentCache, dht = dht)
    val inMemoryRAGCache = java.util.concurrent.ConcurrentHashMap<String, KnowledgeChunk>()

    private val _lastPipelineResult = MutableStateFlow<IngestPipelineResult?>(null)
    val lastPipelineResult: StateFlow<IngestPipelineResult?> = _lastPipelineResult.asStateFlow()
    private val _chunksFlow = MutableStateFlow<List<KnowledgeChunk>>(emptyList())
    val chunksFlow: StateFlow<List<KnowledgeChunk>> = _chunksFlow.asStateFlow()

    init {
        // SQLite stores complete vectors. Rebuild the derived in-memory index after every restart.
        val recoveredObjects = storageManager.getAllObjects(localOwnerId)
        for (storedChunk in metadataStore.getAllChunks()) {
            val recovered = if (storedChunk.ownerId == localOwnerId && "storage_obj_id" !in storedChunk.metadata)
                recoveredObjects.find { it.ownerId == localOwnerId && it.contentHash == sha256Hex(storedChunk.content) }
                else null
            val chunk = if (recovered == null) storedChunk else storedChunk.copy(metadata = storedChunk.metadata +
                mapOf("storage_obj_id" to recovered.id, "owner_id" to storedChunk.ownerId))
            if (recovered != null) check(metadataStore.storeChunk(chunk))
            chunk.vector?.let { vectorIndex.add(chunk.chunkId, it.data) }
            inMemoryRAGCache[chunk.chunkId] = chunk
        }
        torrentCache.adoptLegacyOwner(localOwnerId)
        torrentCache.onDirectMemoryVectorPush = { chunkId, content, vector, meta ->
            importVerifiedChunk(chunkId, content, vector, meta)
        }
        storageManager.onTierChangeListener = { objId, _, newTier ->
            for (chunk in metadataStore.getAllChunks()) {
                if (chunk.metadata["storage_obj_id"] == objId) {
                    metadataStore.updateTier(chunk.chunkId, newTier)
                    inMemoryRAGCache[chunk.chunkId]?.tier = newTier
                }
            }
            refreshChunks()
        }
        // Complete only an adoption previously initiated by a verified pairing before a crash.
        identity.getString("pending_adoption", null)?.let(::bindAuthenticatedOwner)
        refreshChunks()
    }

    /** Called only with the principal returned by verified gateway pairing, never tool arguments. */
    @Synchronized
    fun bindAuthenticatedOwner(ownerId: String) {
        require(ownerId.isNotBlank()) { "Authenticated owner is required" }
        if (!identity.getBoolean("local_owner_adopted", false)) {
            val pending = identity.getString("pending_adoption", null)
            require(pending == null || pending == ownerId) { "A previous owner adoption must complete first" }
            check(identity.edit().putString("pending_adoption", ownerId).commit())
            storageManager.adoptOwner(localOwnerId, ownerId)
            metadataStore.adoptOwner(localOwnerId, ownerId)
            torrentCache.adoptOwner(localOwnerId, ownerId)
            check(identity.edit().putBoolean("local_owner_adopted", true)
                .putString("current_owner_id", ownerId).remove("pending_adoption").commit())
        } else {
            // Account switches select an owner; they do not transfer the previous owner's data.
            check(identity.edit().putString("current_owner_id", ownerId).commit())
        }
        inMemoryRAGCache.clear()
        metadataStore.getAllChunks().forEach { inMemoryRAGCache[it.chunkId] = it }
        refreshChunks()
    }

    @Synchronized
    fun refreshChunks() {
        _chunksFlow.value = metadataStore.getAllChunks(ownerId = currentOwnerId())
    }

    @Synchronized
    fun ingest(
        content: String,
        source: String = "hub",
        sessionId: String = "SESSION-01",
        g1: Float = 0.7f,
        g2: Float = 0.5f,
        g3: Float = 0.8f,
        g4: Float = 0.2f,
        metadata: Map<String, String> = emptyMap(),
        ownerId: String = currentOwnerId()
    ): IngestPipelineResult {
        require(ownerId.isNotBlank()) { "Chunk owner is required" }
        val kappa = TessaClassifier.kappaFromGVec(g1, g2, g3, g4)
        val tessa = TessaClassifier.classify(kappa)
        val chunkId = sha256Hex(listOf(ownerId, source, sessionId, content).joinToString("") {
            "${it.toByteArray(Charsets.UTF_8).size}:$it"
        })
        val vector = semanticEngine.embed(content)
        val meta = metadata + mapOf("owner_id" to ownerId, "source" to source, "session_id" to sessionId,
            "kappa" to kappa.toString(), "tessa_name" to tessa.name)
        val initial = KnowledgeChunk(chunkId = chunkId, content = content, vector = vector,
            tier = tessa.tier, source = source, sessionId = sessionId, kappa = kappa,
            tessaName = tessa.name, metadata = meta, ownerId = ownerId)
        val sdck = sdckCanon.classify(initial)
        val previous = metadataStore.getChunk(chunkId, ownerId)
        val previousObject = previous?.metadata?.get("storage_obj_id")?.let { id ->
            storageManager.getAllObjects(ownerId).find { it.id == id && it.ownerId == ownerId }
        }?.takeIf { storageManager.retrieve(it.id, ownerId)?.contentEquals(content.toByteArray(Charsets.UTF_8)) == true }
        val stored = previousObject ?: checkNotNull(storageManager.store(
            data = content.toByteArray(Charsets.UTF_8), ownerId = ownerId, tier = sdck.tier, metadata = meta
        )) { "Storage quota exceeded" }
        val chunk = initial.copy(tier = stored.tier, confidence = sdck.confidence,
            metadata = meta + mapOf("storage_obj_id" to stored.id, "tier" to stored.tier.value))
        check(metadataStore.storeChunk(chunk)) { "Could not persist knowledge chunk" }
        vectorIndex.add(chunkId, vector.data)
        inMemoryRAGCache[chunkId] = chunk
        val infoHash = torrentCache.addChunk(chunkId = chunkId, content = content,
            rawVector = vector.data, metadata = chunk.metadata)
        peerManager.announceTorrent(infoHash)
        refreshChunks()
        return IngestPipelineResult(
            chunkId = chunkId, infoHash = infoHash, kappa = kappa, tessaName = tessa.name,
            tessaPermission = tessa.permission, tier = chunk.tier, sdckScore = sdck.score,
            pieceCount = checkNotNull(torrentCache.getTorrentMeta(infoHash)).pieceIds.size,
            totalBytes = content.toByteArray(Charsets.UTF_8).size.toLong(), storagePath = stored.path,
            storedObjectId = stored.id
        ).also { _lastPipelineResult.value = it }
    }

    /** The piece transport validates origin, owner, payload and hashes before invoking this callback. */
    @Synchronized
    private fun importVerifiedChunk(chunkId: String, content: String, data: FloatArray, meta: Map<String, String>) {
        val ownerId = requireNotNull(meta["owner_id"]) { "Received chunk has no authenticated owner" }
        require(ownerId == currentOwnerId()) { "Received chunk belongs to another owner" }
        // delete() holds the same RAG monitor; a delivery captured before deletion cannot recreate it.
        check(!torrentCache.isChunkDeleted(ownerId, chunkId)) { "Received chunk was deleted locally" }
        // Content-only peers intentionally omit a vector; generate it with the same local engine.
        val vectorData = if (data.isEmpty()) semanticEngine.embed(content).data else data
        require(vectorData.size == semanticEngine.dimension && vectorData.all { it.isFinite() }) { "Invalid received vector" }
        val existing = metadataStore.getChunk(chunkId)
        require(existing == null || existing.ownerId == ownerId) { "Received chunk id belongs to another owner" }
        if (existing != null) {
            require(existing.content == content) { "Received chunk id has conflicting content" }
            return
        }
        val tier = StorageTier.fromString(meta["tier"] ?: "warm")
        val stored = checkNotNull(storageManager.store(content.toByteArray(Charsets.UTF_8), ownerId, tier, meta)) {
            "Storage quota exceeded while importing peer data"
        }
        val chunk = KnowledgeChunk(chunkId = chunkId, content = content, vector = SemanticVector(vectorData),
            tier = tier, source = meta["source"] ?: "p2p_swarm", sessionId = meta["session_id"] ?: "",
            kappa = meta["kappa"]?.toFloatOrNull() ?: 0.5f, tessaName = meta["tessa_name"] ?: "BENEFIT_STABLE",
            metadata = meta + ("storage_obj_id" to stored.id), ownerId = ownerId)
        check(metadataStore.storeChunk(chunk)) { "Could not persist received knowledge chunk" }
        vectorIndex.add(chunkId, vectorData)
        inMemoryRAGCache[chunkId] = chunk
        refreshChunks()
    }

    @Synchronized
    fun query(
        queryText: String,
        k: Int = 5,
        minKappa: Float = 0.0f,
        tierFilter: StorageTier? = null,
        ownerId: String = currentOwnerId()
    ): List<QueryResultItem> {
        require(ownerId.isNotBlank())
        require(k >= 0)
        val allowed = metadataStore.getAllChunks(ownerId = ownerId)
            .filter { it.kappa >= minKappa && (tierFilter == null || it.tier == tierFilter) }
            .associateBy { it.chunkId }
        val queryVector = semanticEngine.embed(queryText)
        // Filter eligible owners before ranking/top-k, so other owners cannot displace valid hits.
        val hits = vectorIndex.search(queryVector.data, k, allowed.keys)
        val results = hits.map { (id, similarity) ->
            val chunk = checkNotNull(allowed[id])
            metadataStore.updateAccess(id)
            chunk.accessCount++
            val mvs = chunk.vector?.let { semanticEngine.computeSimilarity(it, queryVector) } ?: similarity
            chunk.mvsScore = mvs
            QueryResultItem(chunk, similarity, mvs)
        }
        metadataStore.logQuery(queryText, "${results.size} hits", ownerId)
        return results
    }

    fun retrieve(objId: String, ownerId: String = currentOwnerId()): ByteArray? =
        storageManager.retrieve(objId, ownerId)

    @Synchronized
    fun delete(objId: String, ownerId: String = currentOwnerId()): Boolean {
        val affected = metadataStore.getAllChunks().filter { it.metadata["storage_obj_id"] == objId }
        if (!storageManager.delete(objId, ownerId)) return false
        for (chunk in affected) {
            check(metadataStore.removeChunk(chunk.chunkId, chunk.ownerId))
            vectorIndex.remove(chunk.chunkId)
            inMemoryRAGCache.remove(chunk.chunkId)
            torrentCache.removeChunk(chunk.chunkId, chunk.ownerId)
        }
        refreshChunks()
        return true
    }

    fun buildLlmContext(queryText: String, k: Int = 5, ownerId: String = currentOwnerId()): String {
        val hits = query(queryText, k = k, ownerId = ownerId)
        if (hits.isEmpty()) return "Trinity RAG: No matching context located."
        return buildString {
            appendLine("[Trinity Core RAG Context — ${hits.size} chunks]")
            hits.forEachIndexed { idx, hit ->
                appendLine("[${idx + 1}] Source: ${hit.chunk.source} | Tier: ${hit.chunk.tier.value} | Sim: ${hit.similarityScore}")
                appendLine(hit.chunk.content.trim())
                appendLine()
            }
        }.trim()
    }

    fun close() {
        peerManager.stop()
        metadataStore.close()
    }

    private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
