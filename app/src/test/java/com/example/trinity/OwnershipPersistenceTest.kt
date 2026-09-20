package com.example.trinity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.trinity.core.TrinityRAGServer
import com.example.trinity.model.StorageTier
import com.example.trinity.storage.TrinityMetadataStore
import com.example.trinity.storage.TrinityStorageManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnershipPersistenceTest {
    private lateinit var context: Context
    private val servers = mutableListOf<TrinityRAGServer>()

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        resetFiles()
    }

    @After fun cleanup() {
        servers.forEach { it.close() }
        servers.clear()
        resetFiles()
    }

    private fun resetFiles() {
        context.deleteDatabase("trinity_metadata.db")
        context.deleteDatabase("legacy-test.db")
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("trinity_owner", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun server() = TrinityRAGServer(context).also { servers.add(it) }

    @Test fun `first verified pairing adopts local data and later account switch does not transfer it`() {
        val first = server()
        assertTrue(first.query("anything").isEmpty()) // No automatic demo seed.
        val local = first.currentOwnerId()
        val item = first.ingest("private local document", sessionId = "arbitrary-session")
        assertEquals(local, first.metadataStore.getChunk(item.chunkId)?.ownerId)
        first.bindAuthenticatedOwner("issuer|alice")
        assertEquals("private local document", first.retrieve(item.storedObjectId)?.toString(Charsets.UTF_8))
        assertEquals("issuer|alice", first.metadataStore.getChunk(item.chunkId)?.ownerId)
        first.bindAuthenticatedOwner("issuer|bob")
        assertNull(first.retrieve(item.storedObjectId))
        assertFalse(first.delete(item.storedObjectId))
        assertTrue(first.query("private local document").isEmpty())
        assertEquals(1, first.query("private local document", ownerId = "issuer|alice").size)
        first.close(); servers.remove(first)
        val restarted = server()
        assertEquals("issuer|bob", restarted.currentOwnerId())
        assertNull(restarted.retrieve(item.storedObjectId))
        restarted.bindAuthenticatedOwner("issuer|alice")
        assertEquals("private local document", restarted.retrieve(item.storedObjectId)?.toString(Charsets.UTF_8))
        assertTrue(restarted.delete(item.storedObjectId))
        assertTrue(restarted.query("private local document").isEmpty())
        assertTrue(restarted.torrentCache.getAllTorrents().none { it.chunkId == item.chunkId })
    }

    @Test fun `owner filter is applied before top k so many unrelated owners cannot hide own hit`() {
        val rag = server()
        val mine = rag.ingest("own distant content", ownerId = "alice")
        repeat(20) { index -> rag.ingest("exact query", sessionId = index.toString(), ownerId = "bob") }
        val hits = rag.query("exact query", k = 1, ownerId = "alice")
        assertEquals(listOf(mine.chunkId), hits.map { it.chunk.chunkId })
        assertEquals("alice", hits.single().chunk.ownerId)
    }

    @Test fun `object owner permissions compression and quota survive restart`() {
        val first = TrinityStorageManager(context, ownerProvider = { "alice" })
        val value = checkNotNull(first.store("durable private data".toByteArray(), tier = StorageTier.COLD,
            permissions = mapOf("reader" to listOf("read"))))
        val reloaded = TrinityStorageManager(context, ownerProvider = { "alice" })
        assertEquals(1, reloaded.getQuota("alice").usedFiles)
        assertEquals(value.sizeBytes, reloaded.getQuota("alice").usedBytes)
        assertArrayEquals("durable private data".toByteArray(), reloaded.retrieve(value.id))
        assertArrayEquals("durable private data".toByteArray(), reloaded.retrieve(value.id, "reader"))
        assertNull(reloaded.retrieve(value.id, "stranger"))
        assertFalse(reloaded.delete(value.id, "reader"))
        assertFalse(reloaded.moveTier(value.id, StorageTier.HOT, "reader"))
        assertTrue(reloaded.delete(value.id))
        assertTrue(TrinityStorageManager(context, ownerProvider = { "alice" }).getAllObjects().isEmpty())
    }

    @Test fun `v1 database migrates content and query history without destructive recreation`() {
        val path = context.getDatabasePath("legacy-test.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE chunks (chunk_id TEXT PRIMARY KEY, content TEXT NOT NULL, vector_blob BLOB, " +
                "tier TEXT DEFAULT 'warm', confidence TEXT DEFAULT 'medium', source TEXT DEFAULT 'hub', " +
                "session_id TEXT DEFAULT '', kappa REAL DEFAULT 0.5, tessa_name TEXT DEFAULT 'BENEFIT_STABLE', " +
                "access_count INTEGER DEFAULT 0, last_accessed REAL, created_at REAL, metadata_json TEXT DEFAULT '{}')")
            db.execSQL("CREATE TABLE query_log (id INTEGER PRIMARY KEY AUTOINCREMENT, query_text TEXT, results TEXT, ts REAL)")
            db.execSQL("INSERT INTO chunks(chunk_id,content,session_id) VALUES('old','legacy knowledge','session-only')")
            db.execSQL("INSERT INTO query_log(query_text,results,ts) VALUES('earlier query','1 hit',1)")
            db.version = 1
        }
        TrinityMetadataStore(context, "legacy-test.db", "local:stable").use { store ->
            val item = checkNotNull(store.getChunk("old", "local:stable"))
            assertEquals("legacy knowledge", item.content)
            assertEquals("session-only", item.sessionId)
            assertNull(store.getChunk("old", "other-owner"))
            store.adoptOwner("local:stable", "alice")
            assertEquals("alice", store.getChunk("old")?.ownerId)
            store.readableDatabase.rawQuery("SELECT query_text,owner_id FROM query_log", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("earlier query", cursor.getString(0))
                assertEquals("alice", cursor.getString(1))
            }
        }
    }

    @Test fun `legacy payload without manifest is recovered once into stable local owner`() {
        val oldFile = File(context.filesDir, "trinity_storage/hot/2026/09/20/legacy-object")
        oldFile.parentFile?.mkdirs()
        oldFile.writeText("preserved bytes")
        val migrated = TrinityStorageManager(context, ownerProvider = { "local:stable" })
        assertEquals("preserved bytes", migrated.retrieve("legacy-object")?.toString(Charsets.UTF_8))
        migrated.adoptOwner("local:stable", "alice")
        val reopened = TrinityStorageManager(context, ownerProvider = { "bob" })
        assertNull(reopened.retrieve("legacy-object"))
        assertEquals("preserved bytes", reopened.retrieve("legacy-object", "alice")?.toString(Charsets.UTF_8))
    }
}
