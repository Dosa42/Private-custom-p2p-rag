package com.example.trinity.storage

import android.content.Context
import android.util.AtomicFile
import com.example.trinity.model.StorageQuota
import com.example.trinity.model.StorageTier
import com.example.trinity.model.StoredObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/** File-backed objects and durable ownership metadata. The device owner retains full access. */
class TrinityStorageManager(
    context: Context,
    baseDirName: String = "trinity_storage",
    private val ownerProvider: () -> String = { "default_user" }
) {
    private val baseDir = File(context.filesDir, baseDirName).apply { mkdirs() }
    private val manifest = AtomicFile(File(baseDir, "objects.json"))
    private val objects = mutableMapOf<String, StoredObject>()
    private val quotas = mutableMapOf<String, StorageQuota>()
    var onTierChangeListener: ((String, StorageTier, StorageTier) -> Unit)? = null

    init {
        StorageTier.entries.forEach { File(baseDir, it.value).mkdirs() }
        if (manifest.baseFile.exists() || File(baseDir, "objects.json.bak").exists()) {
            val records = JSONArray(manifest.readFully().toString(Charsets.UTF_8))
            for (i in 0 until records.length()) {
                val value = records.getJSONObject(i)
                val relativePath = value.getString("path")
                val file = File(baseDir, relativePath).canonicalFile
                require(file.path.startsWith(baseDir.canonicalPath + File.separator)) { "Invalid stored object path" }
                val obj = StoredObject(
                    id = value.getString("id"), path = file.path,
                    sizeBytes = value.getLong("size_bytes"), contentHash = value.getString("content_hash"),
                    ownerId = value.getString("owner_id"), tier = StorageTier.fromString(value.getString("tier")),
                    permissions = readPermissions(value.optJSONObject("permissions") ?: JSONObject()),
                    createdAt = value.getLong("created_at"), accessedAt = value.getLong("accessed_at"),
                    accessCount = value.getInt("access_count"), metadata = readStrings(value.getJSONObject("metadata")),
                    compressed = value.getBoolean("compressed"), encrypted = false,
                    tags = value.getJSONArray("tags").let { a -> (0 until a.length()).map(a::getString) }
                )
                objects[obj.id] = obj
            }
        } else {
            // Versions before the manifest stored payload files but lost their owner map on restart.
            // Recover those local files once, preserving content and assigning the stable local owner.
            for (tier in StorageTier.entries) {
                File(baseDir, tier.value).walkTopDown().filter { it.isFile }.forEach { file ->
                    val compressed = tier == StorageTier.COLD || tier == StorageTier.FROZEN
                    val raw = file.readBytes()
                    val data = if (compressed) decompressZlib(raw) else raw
                    val obj = StoredObject(
                        id = file.name, path = file.absolutePath, sizeBytes = data.size.toLong(),
                        contentHash = sha256Hex(data), ownerId = ownerProvider(), tier = tier,
                        compressed = compressed, createdAt = file.lastModified()
                    )
                    require(objects.put(obj.id, obj) == null) { "Duplicate legacy object id" }
                }
            }
            persist()
        }
        rebuildQuotas()
    }

    @Synchronized
    fun store(
        data: ByteArray,
        ownerId: String = ownerProvider(),
        tier: StorageTier = StorageTier.HOT,
        metadata: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList(),
        permissions: Map<String, List<String>> = emptyMap()
    ): StoredObject? {
        require(ownerId.isNotBlank()) { "Object owner is required" }
        if (!getQuota(ownerId).canStore(data.size.toLong())) return null
        val id = UUID.randomUUID().toString()
        val file = fileFor(id, tier, System.currentTimeMillis())
        val compressed = tier == StorageTier.COLD || tier == StorageTier.FROZEN
        writeAtomic(file, if (compressed) compressZlib(data) else data)
        val obj = StoredObject(
            id = id, path = file.absolutePath, sizeBytes = data.size.toLong(), contentHash = sha256Hex(data),
            ownerId = ownerId, tier = tier, metadata = metadata, compressed = compressed,
            permissions = permissions, tags = tags
        )
        objects[id] = obj
        try { persist() } catch (error: Exception) {
            objects.remove(id)
            file.delete()
            throw error
        }
        rebuildQuotas()
        return obj
    }

    private fun permits(obj: StoredObject, userId: String, operation: String): Boolean =
        userId == obj.ownerId || obj.permissions[userId].orEmpty().let { operation in it || "*" in it }

    @Synchronized
    fun retrieve(objId: String, userId: String = ownerProvider()): ByteArray? {
        val obj = objects[objId] ?: return null
        if (!permits(obj, userId, "read")) return null
        val file = File(obj.path)
        if (!file.exists()) return null
        val raw = file.readBytes()
        val data = if (obj.compressed) decompressZlib(raw) else raw
        check(sha256Hex(data) == obj.contentHash) { "Stored object content hash mismatch" }
        obj.accessCount++
        obj.accessedAt = System.currentTimeMillis()
        persist()
        return data
    }

    @Synchronized
    fun delete(objId: String, userId: String = ownerProvider()): Boolean {
        val obj = objects[objId] ?: return false
        if (!permits(obj, userId, "delete")) return false
        val file = File(obj.path)
        check(!file.exists() || file.delete()) { "Could not delete stored object" }
        objects.remove(objId)
        persist()
        rebuildQuotas()
        return true
    }

    fun moveTier(objId: String, newTier: StorageTier, userId: String = ownerProvider()): Boolean {
        val oldTier = synchronized(this) {
        val obj = objects[objId] ?: return false
        if (!permits(obj, userId, "write")) return false
        if (obj.tier == newTier) return true
        val oldFile = File(obj.path)
        if (!oldFile.exists()) return false
        val raw = oldFile.readBytes()
        val data = if (obj.compressed) decompressZlib(raw) else raw
        check(sha256Hex(data) == obj.contentHash) { "Stored object content hash mismatch" }
        val compressed = newTier == StorageTier.COLD || newTier == StorageTier.FROZEN
        val file = fileFor(objId, newTier, obj.createdAt)
        writeAtomic(file, if (compressed) compressZlib(data) else data)
        val previousTier = obj.tier
        obj.path = file.absolutePath
        obj.tier = newTier
        obj.compressed = compressed
        obj.encrypted = false
        persist()
        check(oldFile.delete()) { "Could not remove old tier payload" }
        previousTier
        }
        onTierChangeListener?.invoke(objId, oldTier, newTier)
        return true
    }

    @Synchronized
    fun adoptOwner(fromOwnerId: String, toOwnerId: String) {
        require(fromOwnerId.isNotBlank() && toOwnerId.isNotBlank())
        val adopted = objects.values.filter { it.ownerId == fromOwnerId }
        adopted.forEach { obj ->
            objects[obj.id] = obj.copy(ownerId = toOwnerId, metadata = obj.metadata + ("owner_id" to toOwnerId))
        }
        persist()
        rebuildQuotas()
    }

    @Synchronized
    fun getAllObjects(userId: String = ownerProvider()): List<StoredObject> =
        objects.values.filter { permits(it, userId, "read") }

    @Synchronized
    fun getQuota(userId: String = ownerProvider()): StorageQuota =
        quotas.getOrPut(userId) { StorageQuota(userId = userId) }

    @Synchronized
    fun getStats(userId: String = ownerProvider()): Map<String, Any> {
        val visible = getAllObjects(userId)
        val quota = getQuota(userId)
        return mapOf(
            "total_objects" to visible.size, "total_size_bytes" to visible.sumOf { it.sizeBytes },
            "tier_counts" to StorageTier.entries.associate { t -> t.value to visible.count { it.tier == t } },
            "quota_used_bytes" to quota.usedBytes, "quota_max_bytes" to quota.maxBytes,
            "quota_used_files" to quota.usedFiles
        )
    }

    private fun rebuildQuotas() {
        quotas.clear()
        objects.values.forEach { obj ->
            val quota = getQuota(obj.ownerId)
            quota.usedBytes += obj.sizeBytes
            quota.usedFiles++
        }
    }

    private fun fileFor(id: String, tier: StorageTier, createdAt: Long): File {
        val date = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(createdAt))
        return File(File(File(baseDir, tier.value), date).apply { mkdirs() }, id)
    }

    private fun persist() {
        val records = JSONArray()
        objects.values.forEach { obj ->
            records.put(JSONObject().apply {
                put("id", obj.id); put("path", File(obj.path).relativeTo(baseDir).path)
                put("size_bytes", obj.sizeBytes); put("content_hash", obj.contentHash)
                put("owner_id", obj.ownerId); put("tier", obj.tier.value)
                put("permissions", JSONObject(obj.permissions.mapValues { JSONArray(it.value) }))
                put("created_at", obj.createdAt); put("accessed_at", obj.accessedAt)
                put("access_count", obj.accessCount); put("metadata", JSONObject(obj.metadata))
                put("compressed", obj.compressed); put("tags", JSONArray(obj.tags))
            })
        }
        val stream = manifest.startWrite()
        try {
            stream.write(records.toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(stream)
        } catch (error: Exception) {
            manifest.failWrite(stream)
            throw error
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun readStrings(json: JSONObject): Map<String, String> =
        json.keys().asSequence().associateWith { json.getString(it) }

    private fun readPermissions(json: JSONObject): Map<String, List<String>> =
        json.keys().asSequence().associateWith { key ->
            val values = json.getJSONArray(key)
            (0 until values.length()).map(values::getString)
        }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data); deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            return output.toByteArray()
        } finally { deflater.end() }
    }

    private fun decompressZlib(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                check(count > 0 || inflater.finished()) { "Truncated or invalid compressed object" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally { inflater.end() }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
