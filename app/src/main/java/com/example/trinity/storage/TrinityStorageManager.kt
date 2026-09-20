package com.example.trinity.storage

import android.content.Context
import com.example.trinity.model.StorageQuota
import com.example.trinity.model.StorageTier
import com.example.trinity.model.StoredObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

class TrinityStorageManager(
    private val context: Context,
    baseDirName: String = "trinity_storage"
) {

    private val baseDir = File(context.filesDir, baseDirName).apply { mkdirs() }
    private val objects = mutableMapOf<String, StoredObject>()
    private val userObjects = mutableMapOf<String, MutableSet<String>>()
    private val quotas = mutableMapOf<String, StorageQuota>()

    var onTierChangeListener: ((String, StorageTier, StorageTier) -> Unit)? = null

    init {
        for (tier in StorageTier.entries) {
            File(baseDir, tier.value).mkdirs()
        }
        // Initialize default quota
        quotas["default_user"] = StorageQuota(userId = "default_user")
    }

    @Synchronized
    fun store(
        data: ByteArray,
        ownerId: String = "default_user",
        tier: StorageTier = StorageTier.HOT,
        metadata: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList()
    ): StoredObject? {
        val size = data.size.toLong()
        val quota = getQuota(ownerId)
        if (!quota.canStore(size)) {
            return null
        }

        val objId = UUID.randomUUID().toString()
        val contentHash = sha256Hex(data)

        val datePath = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val tierDir = File(File(baseDir, tier.value), datePath).apply { mkdirs() }
        val targetFile = File(tierDir, objId)

        val isCompressed = (tier == StorageTier.COLD || tier == StorageTier.FROZEN)
        val finalPayload = if (isCompressed) compressZlib(data) else data

        targetFile.writeBytes(finalPayload)

        val stored = StoredObject(
            id = objId,
            path = targetFile.absolutePath,
            sizeBytes = size,
            contentHash = contentHash,
            ownerId = ownerId,
            tier = tier,
            metadata = metadata,
            compressed = isCompressed,
            encrypted = (tier == StorageTier.FROZEN),
            tags = tags
        )

        objects[objId] = stored
        userObjects.getOrPut(ownerId) { mutableSetOf() }.add(objId)

        quota.usedBytes += size
        quota.usedFiles += 1

        return stored
    }

    @Synchronized
    fun retrieve(objId: String, userId: String = "default_user"): ByteArray? {
        val obj = objects[objId] ?: return null
        val file = File(obj.path)
        if (!file.exists()) return null

        val raw = file.readBytes()
        val result = if (obj.compressed) decompressZlib(raw) else raw

        obj.accessCount++
        obj.accessedAt = System.currentTimeMillis()
        return result
    }

    @Synchronized
    fun delete(objId: String, userId: String = "default_user"): Boolean {
        val obj = objects[objId] ?: return false
        val file = File(obj.path)
        if (file.exists()) {
            file.delete()
        }
        val quota = getQuota(obj.ownerId)
        quota.usedBytes = (quota.usedBytes - obj.sizeBytes).coerceAtLeast(0L)
        quota.usedFiles = (quota.usedFiles - 1).coerceAtLeast(0)

        objects.remove(objId)
        userObjects[obj.ownerId]?.remove(objId)
        return true
    }

    @Synchronized
    fun moveTier(objId: String, newTier: StorageTier): Boolean {
        val obj = objects[objId] ?: return false
        if (obj.tier == newTier) return true

        val oldTier = obj.tier
        val currentData = retrieve(objId) ?: return false

        val datePath = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(obj.createdAt))
        val newTierDir = File(File(baseDir, newTier.value), datePath).apply { mkdirs() }
        val newFile = File(newTierDir, objId)

        val isCompressed = (newTier == StorageTier.COLD || newTier == StorageTier.FROZEN)
        val payload = if (isCompressed) compressZlib(currentData) else currentData

        newFile.writeBytes(payload)

        // Delete old file
        File(obj.path).delete()

        obj.path = newFile.absolutePath
        obj.tier = newTier
        obj.compressed = isCompressed
        obj.encrypted = (newTier == StorageTier.FROZEN)

        onTierChangeListener?.invoke(objId, oldTier, newTier)
        return true
    }

    fun getAllObjects(): List<StoredObject> = objects.values.toList()

    fun getQuota(userId: String = "default_user"): StorageQuota {
        return quotas.getOrPut(userId) { StorageQuota(userId = userId) }
    }

    fun getStats(): Map<String, Any> {
        val totalSize = objects.values.sumOf { it.sizeBytes }
        val tierCounts = StorageTier.entries.associate { tier ->
            tier.value to objects.values.count { it.tier == tier }
        }
        val quota = getQuota("default_user")

        return mapOf(
            "total_objects" to objects.size,
            "total_size_bytes" to totalSize,
            "tier_counts" to tierCounts,
            "quota_used_bytes" to quota.usedBytes,
            "quota_max_bytes" to quota.maxBytes,
            "quota_used_files" to quota.usedFiles
        )
    }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            baos.write(buffer, 0, count)
        }
        deflater.end()
        return baos.toByteArray()
    }

    private fun decompressZlib(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            baos.write(buffer, 0, count)
        }
        inflater.end()
        return baos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(data)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
