package com.example.trinity.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.trinity.model.ConfidenceLevel
import com.example.trinity.model.KnowledgeChunk
import com.example.trinity.model.SemanticVector
import com.example.trinity.model.StorageTier
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrinityMetadataStore(context: Context, dbName: String = "trinity_metadata.db") :
    SQLiteOpenHelper(context, dbName, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chunks (
                chunk_id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                vector_blob BLOB,
                tier TEXT DEFAULT 'warm',
                confidence TEXT DEFAULT 'medium',
                source TEXT DEFAULT 'hub',
                session_id TEXT DEFAULT '',
                kappa REAL DEFAULT 0.5,
                tessa_name TEXT DEFAULT 'BENEFIT_STABLE',
                access_count INTEGER DEFAULT 0,
                last_accessed REAL,
                created_at REAL,
                metadata_json TEXT DEFAULT '{}'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS query_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query_text TEXT,
                results TEXT,
                ts REAL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS query_log")
        onCreate(db)
    }

    fun storeChunk(chunk: KnowledgeChunk): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("chunk_id", chunk.chunkId)
                put("content", chunk.content)
                put("vector_blob", chunk.vector?.let { serializeVector(it.data) })
                put("tier", chunk.tier.value)
                put("confidence", chunk.confidence.name.lowercase())
                put("source", chunk.source)
                put("session_id", chunk.sessionId)
                put("kappa", chunk.kappa)
                put("tessa_name", chunk.tessaName)
                put("access_count", chunk.accessCount)
                put("last_accessed", chunk.lastAccessed.toDouble())
                put("created_at", chunk.createdAt.toDouble())
                put("metadata_json", JSONObject(chunk.metadata as Map<*, *>).toString())
            }
            db.insertWithOnConflict("chunks", null, values, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun getChunk(chunkId: String): KnowledgeChunk? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM chunks WHERE chunk_id = ?", arrayOf(chunkId))
        return cursor.use {
            if (it.moveToFirst()) rowToChunk(it) else null
        }
    }

    fun getAllChunks(limit: Int = 200): List<KnowledgeChunk> {
        val list = mutableListOf<KnowledgeChunk>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM chunks ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(rowToChunk(it))
            }
        }
        return list
    }

    fun getChunksByTier(tier: StorageTier, limit: Int = 100): List<KnowledgeChunk> {
        val list = mutableListOf<KnowledgeChunk>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM chunks WHERE tier = ? ORDER BY last_accessed DESC LIMIT ?",
            arrayOf(tier.value, limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(rowToChunk(it))
            }
        }
        return list
    }

    fun updateAccess(chunkId: String) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE chunks SET access_count = access_count + 1, last_accessed = ? WHERE chunk_id = ?",
            arrayOf(System.currentTimeMillis().toDouble(), chunkId)
        )
    }

    fun updateTier(chunkId: String, newTier: StorageTier) {
        val db = writableDatabase
        db.execSQL("UPDATE chunks SET tier = ? WHERE chunk_id = ?", arrayOf(newTier.value, chunkId))
    }

    fun logQuery(query: String, resultsJson: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("query_text", query)
            put("results", resultsJson)
            put("ts", System.currentTimeMillis().toDouble())
        }
        db.insert("query_log", null, values)
    }

    fun getStats(): Map<String, Any> {
        val db = readableDatabase
        var total = 0
        var avgKappa = 0.5
        val byTier = mutableMapOf<String, Int>()

        db.rawQuery("SELECT COUNT(*), AVG(kappa) FROM chunks", null).use {
            if (it.moveToFirst()) {
                total = it.getInt(0)
                avgKappa = if (!it.isNull(1)) it.getDouble(1) else 0.5
            }
        }

        db.rawQuery("SELECT tier, COUNT(*) FROM chunks GROUP BY tier", null).use {
            while (it.moveToNext()) {
                byTier[it.getString(0)] = it.getInt(1)
            }
        }

        return mapOf(
            "total_chunks" to total,
            "avg_kappa" to avgKappa,
            "by_tier" to byTier
        )
    }

    private fun serializeVector(vec: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in vec) buffer.putFloat(f)
        return buffer.array()
    }

    private fun deserializeVector(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.getFloat()
        return floats
    }

    private fun rowToChunk(cursor: android.database.Cursor): KnowledgeChunk {
        val chunkId = cursor.getString(cursor.getColumnIndexOrThrow("chunk_id"))
        val content = cursor.getString(cursor.getColumnIndexOrThrow("content"))
        val blob = cursor.getBlob(cursor.getColumnIndexOrThrow("vector_blob"))
        val tierStr = cursor.getString(cursor.getColumnIndexOrThrow("tier"))
        val confStr = cursor.getString(cursor.getColumnIndexOrThrow("confidence"))
        val source = cursor.getString(cursor.getColumnIndexOrThrow("source"))
        val sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id"))
        val kappa = cursor.getFloat(cursor.getColumnIndexOrThrow("kappa"))
        val tessaName = cursor.getString(cursor.getColumnIndexOrThrow("tessa_name"))
        val accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count"))
        val lastAccessed = cursor.getDouble(cursor.getColumnIndexOrThrow("last_accessed")).toLong()
        val createdAt = cursor.getDouble(cursor.getColumnIndexOrThrow("created_at")).toLong()
        val metaJson = cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))

        val metaMap = mutableMapOf<String, String>()
        try {
            val json = JSONObject(metaJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                metaMap[k] = json.optString(k, "")
            }
        } catch (_: Exception) {}

        val vector = if (blob != null && blob.isNotEmpty()) {
            val data = deserializeVector(blob)
            SemanticVector(data, data.size)
        } else null

        return KnowledgeChunk(
            chunkId = chunkId,
            content = content,
            vector = vector,
            tier = StorageTier.fromString(tierStr),
            confidence = ConfidenceLevel.entries.find { it.name.equals(confStr, ignoreCase = true) } ?: ConfidenceLevel.MEDIUM,
            source = source,
            sessionId = sessionId,
            kappa = kappa,
            tessaName = tessaName,
            accessCount = accessCount,
            lastAccessed = lastAccessed,
            createdAt = createdAt,
            metadata = metaMap
        )
    }
}
