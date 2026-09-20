package com.example.trinity.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.trinity.core.TrinityRAGServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real TCP/WebSocket transport test with the gateway's documented device protocol. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrinityGatewayRelayTest {
    @Test fun enrolledIdentityControlsTheRealDeviceDispatcherAndDuplicateIdsDoNotRepeatWrites() {
        val rag = TrinityRAGServer(ApplicationProvider.getApplicationContext<Context>())
        val bridge = TrinityMCPBridge(rag, 0)
        val client = TrinityGatewayClient(rag, bridge)
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val owner = "relay-test-${UUID.randomUUID()}"
        try {
            val exchange = executor.submit<List<JSONObject>> {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val requestLine = line(input)
                    assertTrue(requestLine.startsWith("GET /device/ws "))
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val value = line(input)
                        if (value.isEmpty()) break
                        val split = value.indexOf(':')
                        headers[value.substring(0, split).lowercase()] = value.substring(split + 1).trim()
                    }
                    assertEquals("Bearer test-enrolled-device-token", headers["authorization"])
                    val digest = MessageDigest.getInstance("SHA-1").digest((headers.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(digest)}\r\n\r\n").toByteArray())
                    output.flush()
                    frame(output, JSONObject().put("type", "ready").put("principal", owner).put("device_id", "test-device").toString())
                    val ingest = JSONObject().put("type", "request").put("id", "ingest-once").put("principal", owner)
                        .put("method", "tools/call").put("params", JSONObject().put("name", "trinity_ingest")
                            .put("arguments", JSONObject().put("content", "WebSocket: العربية 🦜").put("source", "relay-test")))
                    frame(output, ingest.toString())
                    val first = JSONObject(readFrame(input))
                    frame(output, ingest.toString())
                    val replay = JSONObject(readFrame(input))
                    val objectId = first.getJSONObject("result").getJSONObject("structuredContent").getString("obj_id")
                    val unauthorized = JSONObject().put("type", "request").put("id", "wrong-owner").put("principal", "other-user")
                        .put("method", "tools/call").put("params", JSONObject().put("name", "trinity_retrieve")
                            .put("arguments", JSONObject().put("obj_id", objectId)))
                    frame(output, unauthorized.toString())
                    listOf(first, replay, JSONObject(readFrame(input)))
                }
            }
            client.connect("http://127.0.0.1:${server.localPort}", "test-enrolled-device-token")
            val result = exchange.get(20, TimeUnit.SECONDS)
            assertFalse(result[0].toString(), result[0].getJSONObject("result").getBoolean("isError"))
            assertEquals(result[0].toString(), result[1].toString())
            assertEquals("principal_mismatch", result[2].getJSONObject("error").getString("code"))
            assertEquals(owner, rag.currentOwnerId())
            val id = result[0].getJSONObject("result").getJSONObject("structuredContent").getString("obj_id")
            assertEquals("WebSocket: العربية 🦜", String(rag.retrieve(id, owner)!!, Charsets.UTF_8))
            assertTrue(rag.delete(id, owner))
        } finally {
            client.close()
            bridge.stopServer()
            rag.peerManager.stop()
            server.close()
            executor.shutdownNow()
        }
    }

    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            check(b >= 0) { "Unexpected HTTP EOF" }
            if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(b)
        }
    }

    private fun frame(output: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        output.write(0x81)
        if (payload.size < 126) output.write(payload.size) else {
            output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 255)
        }
        output.write(payload)
        output.flush()
    }

    private fun readFrame(input: InputStream): String {
        val opcode = input.read()
        check(opcode and 15 == 1) { "Expected text frame, got $opcode" }
        val second = input.read()
        var length = second and 127
        if (length == 126) length = (input.read() shl 8) or input.read()
        check(length != 127) { "Oversized test frame" }
        val masked = second and 128 != 0
        val mask = if (masked) ByteArray(4) { input.read().toByte() } else ByteArray(4)
        val bytes = ByteArray(length) { i ->
            val b = input.read()
            check(b >= 0) { "Unexpected WebSocket EOF" }
            (b xor (if (masked) mask[i % 4].toInt() and 255 else 0)).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
