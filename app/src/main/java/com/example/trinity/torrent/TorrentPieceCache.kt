package com.example.trinity.torrent

import android.content.Context
import com.example.trinity.core.VectorQuantizer
import com.example.trinity.model.TorrentMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class TorrentPieceCache(
    baseDirectory: File,
    val pieceSize: Int = 256 * 1024
) {
    constructor(context: Context, baseDirName: String = "torrent_cache", pieceSize: Int = 256 * 1024) :
        this(File(context.filesDir, baseDirName), pieceSize)

    private val cacheDir = baseDirectory.apply { mkdirs() }
    private val piecesDir = File(cacheDir, "pieces").apply { mkdirs() }
    private val metaDir = File(cacheDir, "meta").apply { mkdirs() }
    private val tombstoneDir = File(cacheDir, "deleted").apply { mkdirs() }
    private val deletedChunks = mutableSetOf<Pair<String, String>>()
    private val torrents = mutableMapOf<String, TorrentMeta>()
    private val availablePieces = mutableSetOf<String>()
    private val chunkToHash = mutableMapOf<String, String>()
    private val delivered = mutableSetOf<String>()
    private val delivering = mutableSetOf<String>()
    var onDirectMemoryVectorPush: ((String, String, FloatArray, Map<String, String>) -> Unit)? = null

    init {
        require(pieceSize in 1..MAX_PIECE_BYTES)
        rebuildIndexes()
    }

    @Synchronized
    private fun rebuildIndexes() {
        tombstoneDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                deletedChunks.add(json.getString("owner_id") to json.getString("chunk_id"))
            } catch (error: Exception) {
                logWarning("Invalid deletion record ${file.name}", error)
            }
        }
        metaDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val meta = fromJson(JSONObject(file.readText()))
                validateMeta(meta, requireOwner = false)
                require(file.nameWithoutExtension == meta.infoHash)
                if (isLocallyDeleted(meta)) return@forEach
                torrents[meta.infoHash] = meta
                chunkToHash[meta.chunkId] = meta.infoHash
            } catch (error: Exception) {
                logWarning("Rejected invalid cached metadata ${file.name}", error)
            }
        }
        piecesDir.listFiles()?.filter { HASH.matches(it.name) }?.forEach { file ->
            if (file.length() <= MAX_PIECE_BYTES && sha256(file.readBytes()) == file.name) {
                availablePieces.add(file.name)
            } else {
                logWarning("Rejected corrupt cached piece ${file.name}")
            }
        }
    }

    @Synchronized
    fun addChunk(chunkId: String, content: String, vectorBytes: ByteArray? = null,
                 rawVector: FloatArray? = null, metadata: Map<String, String> = emptyMap()): String {
        require(chunkId.isNotBlank())
        val enriched = metadata.toMutableMap()
        var bytes = vectorBytes
        if (rawVector != null && rawVector.isNotEmpty()) {
            require(rawVector.all { it.isFinite() })
            val quantized = VectorQuantizer.quantizeSQ8(rawVector)
            bytes = quantized.quantizedBytes
            enriched["vector_quantization"] = "SQ8_INT8"
            enriched["raw_vector_bytes"] = quantized.rawBytesCount.toString()
            enriched["quantized_vector_bytes"] = quantized.quantizedBytesCount.toString()
        }
        var payload = JSONObject().put("chunk_id", chunkId).put("content", content)
            .put("metadata", JSONObject(enriched)).toString().toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.isNotEmpty()) {
            validateVector(bytes)
            payload += SEPARATOR + bytes
        }
        require(payload.size.toLong() in 1..MAX_TORRENT_BYTES)
        val infoHash = sha256(payload)
        val pieceIds = mutableListOf<String>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + pieceSize, payload.size)
            val data = payload.copyOfRange(offset, end)
            val id = sha256(data)
            atomicWrite(File(piecesDir, id), data)
            availablePieces.add(id)
            pieceIds.add(id)
            offset = end
        }
        val meta = TorrentMeta(infoHash, chunkId, pieceIds, payload.size.toLong(), pieceSize,
            System.currentTimeMillis(), enriched)
        chunkToHash[chunkId]?.takeIf { it != infoHash }?.let { oldHash ->
            torrents.remove(oldHash)
            delivered.remove(oldHash)
            File(metaDir, "$oldHash.json").delete()
        }
        torrents[infoHash] = meta
        chunkToHash[chunkId] = infoHash
        writeMetaFile(meta)
        val owner = enriched["owner_id"]
        if (owner != null) {
            deletedChunks.remove(owner to chunkId)
            tombstoneFile(owner, chunkId).delete()
        }
        return infoHash
    }

    @Synchronized
    fun getPiece(pieceId: String): ByteArray? {
        if (!HASH.matches(pieceId) || pieceId !in availablePieces) return null
        val file = File(piecesDir, pieceId)
        if (!file.isFile || file.length() > MAX_PIECE_BYTES) return null
        val data = file.readBytes()
        if (sha256(data) != pieceId) {
            availablePieces.remove(pieceId)
            return null
        }
        return data
    }

    fun storePiece(pieceId: String, data: ByteArray): Boolean {
        val ready = try {
            synchronized(this) {
                if (!HASH.matches(pieceId) || data.size > MAX_PIECE_BYTES || sha256(data) != pieceId) return false
                val matching = torrents.values.filter { pieceId in it.pieceIds }
                if (matching.isEmpty()) return false
                if (matching.any { meta -> meta.pieceIds.withIndex().any { (index, id) ->
                        id == pieceId && data.size.toLong() != expectedPieceSize(meta, index)
                    } }) return false
                atomicWrite(File(piecesDir, pieceId), data)
                availablePieces.add(pieceId)
                matching.filter(::hasAllPieces).mapNotNull(::prepareVector)
            }
        } catch (error: Exception) {
            logWarning("Rejected assembled torrent", error)
            return false
        }
        return try {
            ready.forEach(::deliverVector)
            true
        } catch (error: Exception) {
            logWarning("Vector import failed", error)
            false
        }
    }

    @Synchronized
    fun getBitfield(infoHash: String): List<Boolean> = torrents[infoHash]?.pieceIds?.map { it in availablePieces } ?: emptyList()

    @Synchronized
    fun getMissingPieces(infoHash: String): List<String> = torrents[infoHash]?.pieceIds?.filter { it !in availablePieces } ?: emptyList()

    @Synchronized
    fun isComplete(infoHash: String): Boolean {
        val meta = torrents[infoHash] ?: return false
        if (!hasAllPieces(meta)) return false
        return try { readPayload(meta); true } catch (_: Exception) { false }
    }

    private fun hasAllPieces(meta: TorrentMeta): Boolean = meta.pieceIds.all { it in availablePieces }

    private data class Payload(val content: String, val vector: ByteArray?)

    private fun readPayload(meta: TorrentMeta): Payload {
        val output = ByteArrayOutputStream()
        meta.pieceIds.forEachIndexed { index, id ->
            val piece = checkNotNull(getPiece(id)) { "Missing piece $id" }
            require(piece.size.toLong() == expectedPieceSize(meta, index)) { "Incorrect piece length" }
            output.write(piece)
        }
        val bytes = output.toByteArray()
        require(bytes.size.toLong() == meta.totalSize && sha256(bytes) == meta.infoHash) { "Torrent SHA-256 mismatch" }
        val separatorIndex = findSubArray(bytes, SEPARATOR)
        val json = JSONObject(String(if (separatorIndex >= 0) bytes.copyOfRange(0, separatorIndex) else bytes, Charsets.UTF_8))
        require(json.getString("chunk_id") == meta.chunkId) { "Torrent chunk ID mismatch" }
        val payloadMetadata = stringMap(json.getJSONObject("metadata"))
        require(payloadMetadata == meta.metadata) { "Torrent metadata does not match hashed payload" }
        val vector = if (separatorIndex >= 0) bytes.copyOfRange(separatorIndex + SEPARATOR.size, bytes.size) else null
        if (vector != null) validateVector(vector)
        return Payload(json.getString("content"), vector)
    }

    private data class Delivery(val meta: TorrentMeta, val payload: Payload,
        val callback: (String, String, FloatArray, Map<String, String>) -> Unit)

    // Called under the cache lock; callbacks run only after that lock is released.
    private fun prepareVector(meta: TorrentMeta): Delivery? {
        val payload = readPayload(meta)
        if (meta.infoHash in delivered || meta.infoHash in delivering) return null
        val callback = onDirectMemoryVectorPush ?: return null
        require(!meta.metadata["owner_id"].isNullOrBlank()) { "Received torrent has no authenticated owner" }
        return Delivery(meta, payload, callback)
    }

    private fun deliverVector(delivery: Delivery) {
        synchronized(this) {
            val hash = delivery.meta.infoHash
            if (hash in delivered || hash in delivering) return
            check(!isLocallyDeleted(delivery.meta)) { "Torrent deleted before import" }
            delivering.add(hash)
        }
        try {
            val meta = delivery.meta
            val vector = delivery.payload.vector?.let(VectorQuantizer::dequantizeSQ8) ?: FloatArray(0)
            delivery.callback(meta.chunkId, delivery.payload.content, vector, meta.metadata)
            synchronized(this) { delivered.add(meta.infoHash) }
        } finally {
            synchronized(this) { delivering.remove(delivery.meta.infoHash) }
        }
    }

    @Synchronized
    fun reassembleChunk(infoHash: String): Map<String, Any>? {
        if (!isComplete(infoHash)) return null
        val meta = torrents[infoHash] ?: return null
        return try {
            val payload = readPayload(meta)
            mutableMapOf<String, Any>("chunk_id" to meta.chunkId, "content" to payload.content,
                "info_hash" to infoHash, "total_size" to meta.totalSize,
                "metadata" to meta.metadata, "has_vector" to (payload.vector != null)).apply {
                payload.vector?.let { bytes ->
                    val dim = VectorQuantizer.dequantizeSQ8(bytes).size
                    put("vector_dim", dim)
                    put("quantization_format", "SQ8 (int8)")
                    put("quantized_vector_bytes", bytes.size)
                    put("raw_vector_bytes", dim * 4)
                    put("vector_savings_percent", if (dim == 0) "0" else "${100f * (1f - bytes.size / (dim * 4f))}")
                }
            }
        } catch (error: Exception) {
            logWarning("Cannot reassemble verified torrent", error)
            null
        }
    }

    @Synchronized
    fun getAllTorrents(ownerId: String? = null): List<TorrentMeta> =
        torrents.values.filter { ownerId == null || it.metadata["owner_id"] == ownerId }

    @Synchronized
    fun getTorrentMeta(infoHash: String): TorrentMeta? = torrents[infoHash]

    fun registerTorrentMeta(meta: TorrentMeta) {
        val ready = synchronized(this) {
            validateMeta(meta, requireOwner = true)
            require(!isLocallyDeleted(meta)) { "Torrent was deleted locally by its owner" }
            val existingMeta = torrents[meta.infoHash]
            if (existingMeta != null) {
                require(existingMeta.copy(createdAt = meta.createdAt) == meta) { "Conflicting torrent metadata" }
            } else {
                chunkToHash[meta.chunkId]?.let { existingHash ->
                    val existing = torrents.getValue(existingHash)
                    require(existing.metadata["owner_id"] == meta.metadata["owner_id"]) { "Chunk belongs to another owner" }
                    require(existingHash == meta.infoHash) { "Conflicting content for an existing chunk ID" }
                }
                torrents[meta.infoHash] = meta
                chunkToHash[meta.chunkId] = meta.infoHash
                writeMetaFile(meta)
            }
            if (hasAllPieces(meta)) prepareVector(meta) else null
        }
        ready?.let(::deliverVector)
    }

    @Synchronized
    fun removeChunk(chunkId: String, ownerId: String): Boolean {
        val owned = torrents.values.filter { it.chunkId == chunkId && it.metadata["owner_id"] == ownerId }
        if (owned.isEmpty()) return false
        recordDeletion(ownerId, chunkId)
        for (meta in owned) {
            torrents.remove(meta.infoHash)
            delivered.remove(meta.infoHash)
            val metadataFile = File(metaDir, "${meta.infoHash}.json")
            check(!metadataFile.exists() || metadataFile.delete()) { "Cannot remove torrent metadata" }
        }
        chunkToHash.remove(chunkId)
        val referenced = torrents.values.flatMap { it.pieceIds }.toSet()
        owned.flatMap { it.pieceIds }.distinct().filter { it !in referenced }.forEach {
            val pieceFile = File(piecesDir, it)
            check(!pieceFile.exists() || pieceFile.delete()) { "Cannot remove torrent piece" }
            availablePieces.remove(it)
        }
        return true
    }

    @Synchronized
    fun isLocallyDeleted(meta: TorrentMeta): Boolean = (meta.metadata["owner_id"] to meta.chunkId) in deletedChunks

    @Synchronized
    fun isChunkDeleted(ownerId: String, chunkId: String): Boolean = (ownerId to chunkId) in deletedChunks

    private fun tombstoneFile(ownerId: String, chunkId: String): File =
        File(tombstoneDir, sha256((ownerId + "\u0000" + chunkId).toByteArray(Charsets.UTF_8)) + ".json")

    private fun recordDeletion(ownerId: String, chunkId: String) {
        atomicWrite(tombstoneFile(ownerId, chunkId), JSONObject().put("owner_id", ownerId)
            .put("chunk_id", chunkId).toString().toByteArray(Charsets.UTF_8))
        deletedChunks.add(ownerId to chunkId)
    }

    fun restoreOwnedVectors(ownerId: String) {
        val ready = synchronized(this) {
            torrents.values.filter { it.metadata["owner_id"] == ownerId && isComplete(it.infoHash) }
                .mapNotNull(::prepareVector)
        }
        ready.forEach(::deliverVector)
    }

    @Synchronized
    fun adoptOwner(fromOwnerId: String, toOwnerId: String) {
        require(toOwnerId.isNotBlank())
        if (fromOwnerId == toOwnerId) return
        val adopted = torrents.values.filter { it.metadata["owner_id"].orEmpty() == fromOwnerId }.map { meta ->
            require(isComplete(meta.infoHash)) { "Cannot change owner of incomplete torrent ${meta.infoHash}" }
            meta to readPayload(meta)
        }
        deletedChunks.filter { it.first == fromOwnerId }.toList().forEach { (_, chunkId) ->
            recordDeletion(toOwnerId, chunkId)
            deletedChunks.remove(fromOwnerId to chunkId)
            tombstoneFile(fromOwnerId, chunkId).delete()
        }
        adopted.forEach { (meta, payload) ->
            addChunk(meta.chunkId, payload.content, vectorBytes = payload.vector,
                metadata = meta.metadata + ("owner_id" to toOwnerId))
        }
    }

    fun adoptLegacyOwner(ownerId: String) = adoptOwner("", ownerId)

    @Synchronized
    fun getStats(ownerId: String? = null): Map<String, Any> {
        val owned = getAllTorrents(ownerId)
        val referencedPieces = owned.flatMap { it.pieceIds }.toSet()
        return mapOf("total_torrents" to owned.size,
            "complete_torrents" to owned.count { isComplete(it.infoHash) },
            "total_pieces" to owned.sumOf { it.pieceIds.size },
            "available_pieces" to referencedPieces.count { it in availablePieces })
    }

    private fun writeMetaFile(meta: TorrentMeta) = atomicWrite(File(metaDir, "${meta.infoHash}.json"), toJson(meta).toString().toByteArray(Charsets.UTF_8))

    companion object {
        private fun logWarning(message: String, error: Exception? = null) {
            java.util.logging.Logger.getLogger("TorrentPieceCache").log(java.util.logging.Level.WARNING, message, error)
        }
        private val HASH = Regex("[a-f0-9]{64}")
        private val SEPARATOR = "\u0000VECTOR\u0000".toByteArray(Charsets.UTF_8)
        const val MAX_PIECE_BYTES = 1024 * 1024
        const val MAX_TORRENT_BYTES = 64L * 1024 * 1024

        fun toJson(meta: TorrentMeta): JSONObject = JSONObject().put("info_hash", meta.infoHash)
            .put("chunk_id", meta.chunkId).put("total_size", meta.totalSize).put("piece_size", meta.pieceSize)
            .put("created_at", meta.createdAt).put("piece_ids", JSONArray(meta.pieceIds))
            .put("metadata", JSONObject(meta.metadata))

        fun fromJson(json: JSONObject): TorrentMeta {
            val ids = json.getJSONArray("piece_ids")
            require(ids.length() <= 65536) { "Too many pieces" }
            return TorrentMeta(json.getString("info_hash"), json.getString("chunk_id"),
                List(ids.length()) { ids.getString(it) }, json.getLong("total_size"),
                json.getInt("piece_size"), json.getLong("created_at"), stringMap(json.getJSONObject("metadata")))
        }

        private fun stringMap(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.getString(it) }

        fun validateMeta(meta: TorrentMeta, requireOwner: Boolean = true) {
            require(HASH.matches(meta.infoHash) && meta.chunkId.isNotBlank() && meta.chunkId.length <= 1024)
            require(meta.totalSize in 1..MAX_TORRENT_BYTES && meta.pieceSize in 1..MAX_PIECE_BYTES)
            require(meta.pieceIds.size.toLong() == (meta.totalSize + meta.pieceSize - 1) / meta.pieceSize)
            require(meta.pieceIds.size <= 65536 && meta.pieceIds.all(HASH::matches))
            if (requireOwner) require(!meta.metadata["owner_id"].isNullOrBlank()) { "Owner metadata required" }
        }

        private fun expectedPieceSize(meta: TorrentMeta, index: Int): Long = minOf(meta.pieceSize.toLong(), meta.totalSize - index.toLong() * meta.pieceSize)
        private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        private fun validateVector(bytes: ByteArray) {
            require(bytes.size >= 9 && bytes[0] == 0x51.toByte()) { "Invalid SQ8 vector format" }
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            input.get()
            val scale = input.float
            val dim = input.int
            require(scale.isFinite() && scale > 0 && dim in 1..1_000_000 && bytes.size == dim + 9) { "Invalid SQ8 dimensions or scale" }
        }
        private fun atomicWrite(file: File, bytes: ByteArray) {
            val temporary = File.createTempFile("piece-", ".tmp", file.parentFile)
            try {
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                check(temporary.renameTo(file)) { "Cannot write ${file.name}" }
            } finally { temporary.delete() }
        }
        private fun findSubArray(src: ByteArray, target: ByteArray): Int {
            for (i in 0..src.size - target.size) if (target.indices.all { src[i + it] == target[it] }) return i
            return -1
        }
    }
}
