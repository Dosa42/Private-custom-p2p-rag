package com.example.trinity.core

import com.example.trinity.model.SemanticVector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrinityVectorIndex(val dimension: Int = 384) {

    private val idMap = mutableListOf<String>()
    private val vectors = mutableMapOf<String, FloatArray>()

    @Synchronized
    fun add(chunkId: String, vector: FloatArray): Int {
        require(vector.size == dimension && vector.all { it.isFinite() }) { "Invalid indexed vector" }
        vectors[chunkId] = vector
        if (!idMap.contains(chunkId)) {
            idMap.add(chunkId)
        }
        return idMap.indexOf(chunkId)
    }

    @Synchronized
    fun search(queryVector: FloatArray, k: Int = 10, allowedIds: Set<String>? = null): List<Pair<String, Float>> {
        require(k >= 0) { "Result count must not be negative" }
        require(queryVector.size == dimension) { "Query vector dimension mismatch" }
        if (idMap.isEmpty() || vectors.isEmpty()) return emptyList()

        var qNormSum = 0.0
        for (f in queryVector) qNormSum += (f * f)
        val qNorm = Math.sqrt(qNormSum).toFloat().coerceAtLeast(1e-6f)

        val scored = mutableListOf<Pair<String, Float>>()
        for ((chunkId, vec) in vectors) {
            if (allowedIds != null && chunkId !in allowedIds) continue
            var dot = 0.0
            var vNormSum = 0.0
            for (i in 0 until dimension.coerceAtMost(vec.size)) {
                dot += (queryVector[i] * vec[i])
                vNormSum += (vec[i] * vec[i])
            }
            val vNorm = Math.sqrt(vNormSum).toFloat().coerceAtLeast(1e-6f)
            val similarity = (dot / (qNorm * vNorm)).toFloat()
            scored.add(Pair(chunkId, similarity))
        }

        scored.sortByDescending { it.second }
        return scored.take(k)
    }

    @Synchronized
    fun remove(chunkId: String) {
        vectors.remove(chunkId)
        idMap.remove(chunkId)
    }

    @Synchronized
    fun clear() {
        vectors.clear()
        idMap.clear()
    }

    val totalVectors: Int get() = idMap.size

    @Synchronized
    fun save(file: File) {
        try {
            file.parentFile?.mkdirs()
            val total = vectors.size
            val bufferSize = 4 + 4 + total * (64 + dimension * 4)
            val buffer = ByteBuffer.allocate(bufferSize.coerceAtLeast(1024)).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(dimension)
            buffer.putInt(total)

            for ((id, vec) in vectors) {
                val idBytes = id.toByteArray(Charsets.UTF_8)
                buffer.putInt(idBytes.size)
                buffer.put(idBytes)
                for (v in vec) {
                    buffer.putFloat(v)
                }
            }
            file.writeBytes(buffer.array().copyOf(buffer.position()))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun load(file: File) {
        if (!file.exists()) return
        try {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val dim = buffer.getInt()
            val total = buffer.getInt()
            vectors.clear()
            idMap.clear()

            for (i in 0 until total) {
                val idLen = buffer.getInt()
                val idBytes = ByteArray(idLen)
                buffer.get(idBytes)
                val id = String(idBytes, Charsets.UTF_8)
                val vec = FloatArray(dim)
                for (d in 0 until dim) {
                    vec[d] = buffer.getFloat()
                }
                vectors[id] = vec
                idMap.add(id)
            }
        } catch (_: Exception) {}
    }
}
