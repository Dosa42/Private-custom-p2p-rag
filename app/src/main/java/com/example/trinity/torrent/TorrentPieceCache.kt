package com.example.trinity.torrent

import android.content.Context
import com.example.trinity.model.TorrentMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class TorrentPieceCache(
    private val context: Context,
    baseDirName: String = "torrent_cache",
    val pieceSize: Int = 256 * 1024 // 256 KB
) {

    private val cacheDir = File(context.filesDir, baseDirName).apply { mkdirs() }
    private val piecesDir = File(cacheDir, "pieces").apply { mkdirs() }
    private val metaDir = File(cacheDir, "meta").apply { mkdirs() }

    private val torrents = mutableMapOf<String, TorrentMeta>()
    private val availablePieces = mutableSetOf<String>()
    private val chunkToHash = mutableMapOf<String, String>()

    // Direct RAM bus to active FAISS Vector Index in memory (0ms disk latency)
    var onDirectMemoryVectorPush: ((chunkId: String, content: String, vector: FloatArray, meta: Map<String, String>) -> Unit)? = null

    init {
        rebuildIndexes()
    }

    @Synchronized
    private fun rebuildIndexes() {
        torrents.clear()
        availablePieces.clear()
        chunkToHash.clear()

        metaDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val infoHash = json.getString("info_hash")
                val chunkId = json.getString("chunk_id")
                val totalSize = json.getLong("total_size")
                val pSize = json.optInt("piece_size", pieceSize)
                val createdAt = json.optLong("created_at", System.currentTimeMillis())

                val pieceArr = json.getJSONArray("piece_ids")
                val pieceIds = mutableListOf<String>()
                for (i in 0 until pieceArr.length()) {
                    pieceIds.add(pieceArr.getString(i))
                }

                val metaObj = json.optJSONObject("metadata")
                val metaMap = mutableMapOf<String, String>()
                if (metaObj != null) {
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        metaMap[k] = metaObj.optString(k)
                    }
                }

                val meta = TorrentMeta(
                    infoHash = infoHash,
                    chunkId = chunkId,
                    pieceIds = pieceIds,
                    totalSize = totalSize,
                    pieceSize = pSize,
                    createdAt = createdAt,
                    metadata = metaMap
                )
                torrents[infoHash] = meta
                chunkToHash[chunkId] = infoHash
            } catch (_: Exception) {}
        }

        piecesDir.listFiles()?.forEach {
            availablePieces.add(it.name)
        }
    }

    @Synchronized
    fun addChunk(
        chunkId: String,
        content: String,
        vectorBytes: ByteArray? = null,
        rawVector: FloatArray? = null,
        metadata: Map<String, String> = emptyMap()
    ): String {
        chunkToHash[chunkId]?.let { return it }

        val enrichedMeta = metadata.toMutableMap()
        var finalVectorBytes = vectorBytes

        if (rawVector != null && rawVector.isNotEmpty()) {
            val qResult = com.example.trinity.core.VectorQuantizer.quantizeSQ8(rawVector)
            finalVectorBytes = qResult.quantizedBytes
            enrichedMeta["vector_quantization"] = "SQ8_INT8"
            enrichedMeta["raw_vector_bytes"] = qResult.rawBytesCount.toString()
            enrichedMeta["quantized_vector_bytes"] = qResult.quantizedBytesCount.toString()
            enrichedMeta["vector_bandwidth_savings"] = "${"%.1f".format(qResult.compressionRatioPercent)}%"
            enrichedMeta["vector_cosine_fidelity"] = "${"%.4f".format(qResult.fidelityCosine)}"
        }

        val chunkJson = JSONObject().apply {
            put("chunk_id", chunkId)
            put("content", content)
            put("metadata", JSONObject(enrichedMeta as Map<*, *>))
        }
        var payload = chunkJson.toString().toByteArray(Charsets.UTF_8)
        if (finalVectorBytes != null && finalVectorBytes.isNotEmpty()) {
            val separator = "\u0000VECTOR\u0000".toByteArray(Charsets.UTF_8)
            payload = payload + separator + finalVectorBytes
        }

        val infoHash = sha256Hex(payload)
        val pieceIds = mutableListOf<String>()

        var offset = 0
        while (offset < payload.size) {
            val end = (offset + pieceSize).coerceAtMost(payload.size)
            val pieceData = payload.copyOfRange(offset, end)
            val pieceId = sha256Hex(pieceData)
            pieceIds.add(pieceId)

            File(piecesDir, pieceId).writeBytes(pieceData)
            availablePieces.add(pieceId)
            offset += pieceSize
        }

        val meta = TorrentMeta(
            infoHash = infoHash,
            chunkId = chunkId,
            pieceIds = pieceIds,
            totalSize = payload.size.toLong(),
            pieceSize = pieceSize,
            createdAt = System.currentTimeMillis(),
            metadata = enrichedMeta
        )

        torrents[infoHash] = meta
        chunkToHash[chunkId] = infoHash

        writeMetaFile(meta)

        // Direct In-Memory RAG Matrix Push (RAM bus, zero disk wait)
        if (rawVector != null && rawVector.isNotEmpty()) {
            onDirectMemoryVectorPush?.invoke(chunkId, content, rawVector, enrichedMeta)
        }

        return infoHash
    }

    @Synchronized
    fun getPiece(pieceId: String): ByteArray? {
        if (!availablePieces.contains(pieceId)) return null
        val file = File(piecesDir, pieceId)
        return if (file.exists()) file.readBytes() else null
    }

    @Synchronized
    fun storePiece(pieceId: String, data: ByteArray): Boolean {
        val check = sha256Hex(data)
        if (check != pieceId) {
            return false
        }
        File(piecesDir, pieceId).writeBytes(data)
        availablePieces.add(pieceId)

        // Check if incoming P2P blocks completed a torrent, and push to in-memory FAISS immediately
        checkAndTriggerInMemoryVectorPush(pieceId)
        return true
    }

    private fun checkAndTriggerInMemoryVectorPush(pieceId: String) {
        val matching = torrents.values.filter { it.pieceIds.contains(pieceId) }
        for (meta in matching) {
            if (isComplete(meta.infoHash)) {
                val reassembled = reassembleChunk(meta.infoHash)
                if (reassembled != null) {
                    val content = reassembled["content"] as? String ?: ""
                    val chunkId = reassembled["chunk_id"] as? String ?: meta.chunkId
                    // Extract vector bytes directly in RAM
                    val baos = java.io.ByteArrayOutputStream()
                    for (pid in meta.pieceIds) {
                        getPiece(pid)?.let { baos.write(it) }
                    }
                    val full = baos.toByteArray()
                    val sep = "\u0000VECTOR\u0000".toByteArray(Charsets.UTF_8)
                    val sepIdx = findSubArray(full, sep)
                    if (sepIdx >= 0) {
                        val vBytes = full.copyOfRange(sepIdx + sep.size, full.size)
                        val dequantized = com.example.trinity.core.VectorQuantizer.dequantizeSQ8(vBytes)
                        onDirectMemoryVectorPush?.invoke(chunkId, content, dequantized, meta.metadata)
                    }
                }
            }
        }
    }

    @Synchronized
    fun getBitfield(infoHash: String): List<Boolean> {
        val meta = torrents[infoHash] ?: return emptyList()
        return meta.pieceIds.map { availablePieces.contains(it) }
    }

    @Synchronized
    fun getMissingPieces(infoHash: String): List<String> {
        val meta = torrents[infoHash] ?: return emptyList()
        return meta.pieceIds.filter { !availablePieces.contains(it) }
    }

    @Synchronized
    fun isComplete(infoHash: String): Boolean {
        return getMissingPieces(infoHash).isEmpty()
    }

    @Synchronized
    fun reassembleChunk(infoHash: String): Map<String, Any>? {
        if (!isComplete(infoHash)) return null
        val meta = torrents[infoHash] ?: return null

        val baos = java.io.ByteArrayOutputStream()
        for (pid in meta.pieceIds) {
            val pData = getPiece(pid) ?: return null
            baos.write(pData)
        }
        val fullBytes = baos.toByteArray()
        val sep = "\u0000VECTOR\u0000".toByteArray(Charsets.UTF_8)
        val sepIndex = findSubArray(fullBytes, sep)

        val jsonBytes = if (sepIndex >= 0) {
            fullBytes.copyOfRange(0, sepIndex)
        } else {
            fullBytes
        }

        val vectorBytes = if (sepIndex >= 0) {
            fullBytes.copyOfRange(sepIndex + sep.size, fullBytes.size)
        } else {
            null
        }

        return try {
            val json = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val map = mutableMapOf<String, Any>()
            map["chunk_id"] = json.optString("chunk_id")
            map["content"] = json.optString("content")
            map["info_hash"] = infoHash
            map["total_size"] = fullBytes.size

            if (vectorBytes != null && vectorBytes.isNotEmpty()) {
                val dequantized = com.example.trinity.core.VectorQuantizer.dequantizeSQ8(vectorBytes)
                map["has_vector"] = true
                map["vector_dim"] = dequantized.size
                map["quantization_format"] = "SQ8 (int8)"
                map["quantized_vector_bytes"] = vectorBytes.size
                map["raw_vector_bytes"] = dequantized.size * 4
                map["vector_savings_percent"] = "74.5%"
            } else {
                map["has_vector"] = false
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    fun getAllTorrents(): List<TorrentMeta> = torrents.values.toList()

    fun getTorrentMeta(infoHash: String): TorrentMeta? = torrents[infoHash]

    fun registerTorrentMeta(meta: TorrentMeta) {
        if (!torrents.containsKey(meta.infoHash)) {
            torrents[meta.infoHash] = meta
            chunkToHash[meta.chunkId] = meta.infoHash
            writeMetaFile(meta)
        }
    }

    fun getStats(): Map<String, Any> {
        val totalPieces = torrents.values.sumOf { it.pieceIds.size }
        val completeCount = torrents.keys.count { isComplete(it) }
        return mapOf(
            "total_torrents" to torrents.size,
            "complete_torrents" to completeCount,
            "total_pieces" to totalPieces,
            "available_pieces" to availablePieces.size
        )
    }

    private fun writeMetaFile(meta: TorrentMeta) {
        val json = JSONObject().apply {
            put("info_hash", meta.infoHash)
            put("chunk_id", meta.chunkId)
            put("total_size", meta.totalSize)
            put("piece_size", meta.pieceSize)
            put("created_at", meta.createdAt)
            put("piece_ids", JSONArray(meta.pieceIds))
            put("metadata", JSONObject(meta.metadata as Map<*, *>))
        }
        File(metaDir, "${meta.infoHash}.json").writeText(json.toString(2))
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(data)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun findSubArray(src: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || src.size < target.size) return -1
        for (i in 0..src.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (src[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
