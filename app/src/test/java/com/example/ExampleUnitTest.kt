package com.example

import com.example.trinity.core.VectorQuantizer
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun vector_quantization_sq8_compresses_and_preserves_fidelity() {
    val rng = Random(42)
    val dim = 384
    val vector = FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    val result = VectorQuantizer.quantizeSQ8(vector)

    // Raw float32: 384 * 4 = 1536 bytes
    assertEquals(1536, result.rawBytesCount)
    // Quantized int8: 1 (magic) + 4 (scale) + 4 (dim) + 384 = 393 bytes
    assertEquals(393, result.quantizedBytesCount)
    // Direct ~74.4% network reduction
    assertTrue("Compression ratio should be around 74.4%", result.compressionRatioPercent >= 74.0f)
    // Dequantized vector should maintain > 0.99 cosine fidelity
    assertTrue("Fidelity should be > 0.99", result.fidelityCosine >= 0.99f)

    val dequantized = VectorQuantizer.dequantizeSQ8(result.quantizedBytes)
    assertEquals(dim, dequantized.size)
  }

  @Test
  fun kademlia_dht_bootstrap_and_xor_routing() {
    val dht = com.example.trinity.dht.KademliaDHT(localPort = 6881)
    assertTrue(dht.bootstrapNodes.isEmpty())

    val routingTable = dht.routingTableFlow.value
    assertTrue("No peer is discovered before an actual connection", routingTable.isEmpty())

    // Test XOR metric
    val idA = "0000000000000000000000000000000000000001"
    val idB = "0000000000000000000000000000000000000003"
    val dist = dht.xorDistance(idA, idB)
    assertEquals(java.math.BigInteger.valueOf(2), dist)

    // Test STORE and FIND_VALUE in DHT
    val testHash = "deadbeefcafebabe1234567890abcdef12345678"
    val testPeer = com.example.trinity.dht.DHTNode(
        nodeId = "1111222233334444555566667777888899990000",
        name = "Test Peer Node",
        address = "192.168.1.200",
        port = 6881
    )
    dht.store(testHash, testPeer)

    val (peers, found) = dht.findValue(testHash)
    assertTrue("Value should be found in DHT", found)
    assertEquals(1, peers.size)
    assertEquals("Test Peer Node", peers[0].name)
  }

  @Test
  fun in_memory_rag_matrix_push_allows_zero_disk_latency_search() {
    val vectorIndex = com.example.trinity.core.TrinityVectorIndex(dimension = 4)
    val testVector = floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f)

    // Direct RAM injection
    vectorIndex.add("chunk-ram-01", testVector)
    assertEquals(1, vectorIndex.totalVectors)

    val hits = vectorIndex.search(floatArrayOf(0.95f, 0.05f, 0.0f, 0.0f), k = 1)
    assertEquals(1, hits.size)
    assertEquals("chunk-ram-01", hits[0].first)
    assertTrue("Similarity should be > 0.99", hits[0].second > 0.99f)
  }

  @Test
  fun missing_credentials_must_fail_without_emulated_chat() = kotlinx.coroutines.runBlocking {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val rag = com.example.trinity.core.TrinityRAGServer(context)
    val bridge = com.example.trinity.cloud.HybridCloudBridge(rag)
    try {
      bridge.executeHybridQuery("Find my records", com.example.trinity.cloud.ChatGptModel.custom("configured-model"))
      fail("Missing credentials must not produce a model response")
    } catch (expected: IllegalStateException) {
      assertNotNull(expected.message)
    }
  }
}
