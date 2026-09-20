package com.example.trinity.p2p

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.trinity.torrent.TorrentPieceCache
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses real loopback sockets, AES-GCM, files and SHA-256, without a simulated peer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrinityPeerIntegrationTest {
    private val peers = mutableListOf<TrinityPeerManager>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secret = "test-only-swarm-secret-" + UUID.randomUUID()
    private val owner = "issuer|alice"

    private fun cache(pieceSize: Int = 1024) = TorrentPieceCache(context, "peer-test-" + UUID.randomUUID(), pieceSize)
    private fun peer(cache: TorrentPieceCache, key: String = secret, principal: String = owner) =
        TrinityPeerManager(torrentCache = cache, listenPort = 0).also {
            peers.add(it)
            it.configureNetwork(key, principal)
        }

    @After fun closePeers() { peers.forEach { it.stop() } }

    @Test fun twoAuthenticatedPeersTransferUtf8MetadataAndVerifiedPieces() = runBlocking {
        val source = cache(512)
        val destination = cache(512)
        val content = "Nederlands, Türkçe en 日本語: echte peergegevens.\n".repeat(180)
        val hash = source.addChunk("document-alice", content,
            rawVector = FloatArray(384) { (it + 1).toFloat() / 384 },
            metadata = mapOf("owner_id" to owner, "source" to "integration-test", "session_id" to "session-1"))
        val hidden = source.addChunk("document-bob", "Private content for another account",
            metadata = mapOf("owner_id" to "issuer|bob"))
        val imported = CountDownLatch(1)
        destination.onDirectMemoryVectorPush = { chunk, actual, vector, meta ->
            assertEquals("document-alice", chunk)
            assertEquals(content, actual)
            assertEquals(384, vector.size)
            assertEquals(owner, meta["owner_id"])
            imported.countDown()
        }
        val sender = peer(source)
        val receiver = peer(destination)
        receiver.addManualPeer("127.0.0.1", sender.boundPort)
        receiver.syncNow()
        assertTrue("Verified transfer must import the real vector", imported.await(10, TimeUnit.SECONDS))
        assertTrue(destination.isComplete(hash))
        assertEquals(content, destination.reassembleChunk(hash)?.get("content"))
        assertNull("Inventory must exclude another owner's content", destination.getTorrentMeta(hidden))
        assertTrue(receiver.connectedPeers.value.any { it.isConnected && hash in it.sharedTorrents })
        assertTrue(destination.removeChunk("document-alice", owner))
        val afterDelete = receiver.syncNow()
        assertEquals("A peer must not resurrect locally deleted content", 0, afterDelete["transferred_torrents"])
        assertNull(destination.getTorrentMeta(hash))
        receiver.stop()
        assertFalse(receiver.listening.value)
    }

    @Test fun wrongSecretCannotConnectOrFetchInventory() = runBlocking {
        val source = cache()
        source.addChunk("private", "Authenticated content", metadata = mapOf("owner_id" to owner))
        val destination = cache()
        val sender = peer(source)
        val receiver = peer(destination, "different-secret-" + UUID.randomUUID())
        receiver.addManualPeer("127.0.0.1", sender.boundPort)
        val result = receiver.syncNow()
        assertEquals(0, result["peers_contacted"])
        assertFalse(receiver.connectedPeers.value.any { it.isConnected })
        assertTrue(destination.getAllTorrents().isEmpty())
        assertTrue((result["errors"] as List<*>).isNotEmpty())
    }

    @Test fun correctSecretWithDifferentOwnerCannotReadInventory() = runBlocking {
        val source = cache()
        source.addChunk("alice-private", "Alice's document", metadata = mapOf("owner_id" to owner))
        val sender = peer(source)
        val destination = cache()
        val receiver = peer(destination, principal = "issuer|bob")
        receiver.addManualPeer("127.0.0.1", sender.boundPort)
        val result = receiver.syncNow()
        assertEquals(0, result["peers_contacted"])
        assertTrue(destination.getAllTorrents().isEmpty())
        assertFalse(receiver.connectedPeers.value.any { it.isConnected })
    }

    @Test fun modifiedPieceAndForgedMetadataNeverReachVectorCallback() {
        val source = cache(4096)
        val hash = source.addChunk("verified", "The exact original document",
            rawVector = floatArrayOf(1f, 0f, 0f), metadata = mapOf("owner_id" to owner, "source" to "original"))
        val meta = checkNotNull(source.getTorrentMeta(hash))
        assertEquals(1, meta.pieceIds.size)
        val original = checkNotNull(source.getPiece(meta.pieceIds.single()))
        val destination = cache(4096)
        var imported = false
        destination.onDirectMemoryVectorPush = { _, _, _, _ -> imported = true }
        destination.registerTorrentMeta(meta.copy(metadata = meta.metadata + ("source" to "forged")))
        val modified = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(destination.storePiece(meta.pieceIds.single(), modified))
        assertFalse(destination.storePiece(meta.pieceIds.single(), original))
        assertFalse(imported)
        assertNull(destination.reassembleChunk(hash))
    }

    @Test fun encryptedFrameRejectsTamperingAndReplayInAnotherConnection() {
        val key = P2PCodec.deriveKey(secret)
        val challenge = P2PCodec.challenge()
        val output = ByteArrayOutputStream()
        P2PCodec.writeFrame(DataOutputStream(output), key, challenge, "request", JSONObject().put("op", "inventory"))
        val frame = output.toByteArray()
        assertEquals("inventory", P2PCodec.readFrame(DataInputStream(ByteArrayInputStream(frame)), key, challenge, "request").getString("op"))
        val modified = frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            P2PCodec.readFrame(DataInputStream(ByteArrayInputStream(modified)), key, challenge, "request")
        }
        assertThrows(Exception::class.java) {
            P2PCodec.readFrame(DataInputStream(ByteArrayInputStream(frame)), key, P2PCodec.challenge(), "request")
        }
        assertThrows(Exception::class.java) {
            P2PCodec.readFrame(DataInputStream(ByteArrayInputStream(frame)), key, challenge, "response")
        }
    }
}
