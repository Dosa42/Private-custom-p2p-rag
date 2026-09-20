package com.example.trinity.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.trinity.core.TrinityRAGServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrinityMCPBridgeTest {
    private lateinit var rag: TrinityRAGServer
    private lateinit var bridge: TrinityMCPBridge

    @Before fun start() = runBlocking {
        rag = TrinityRAGServer(ApplicationProvider.getApplicationContext<Context>())
        rag.bindAuthenticatedOwner("mcp-test-${UUID.randomUUID()}")
        bridge = TrinityMCPBridge(rag, 0)
        bridge.startServer()
        Unit
    }

    @After fun stop() {
        bridge.stopServer()
        rag.peerManager.stop()
    }

    @Test fun unauthorizedRequestsNeverReachTheToolDispatcher() {
        val response = post(JSONObject().put("jsonrpc", "2.0").put("id", "1").put("method", "tools/list"), "invalid")
        assertTrue(response.startsWith("HTTP/1.1 401"))
        assertTrue(response.contains("WWW-Authenticate: Bearer"))
        assertFalse(response.contains("trinity_ingest"))
    }

    @Test fun notificationsHaveNoJsonRpcResponse() {
        val response = post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        assertTrue(response.startsWith("HTTP/1.1 202"))
        assertEquals("", response.substringAfter("\r\n\r\n"))
    }

    @Test fun unknownResourcesReportAnErrorRatherThanActive() {
        val response = post(JSONObject().put("jsonrpc", "2.0").put("id", "missing")
            .put("method", "resources/read").put("params", JSONObject().put("uri", "trinity://does-not-exist")))
        val body = JSONObject(response.substringAfter("\r\n\r\n"))
        assertEquals(-32002, body.getJSONObject("error").getInt("code"))
    }

    @Test fun realHttpRoundTripPreservesUnicodeAndEnforcesOwnership() = runBlocking {
        val definitions = bridge.listTools()
        assertEquals(6, definitions.length())
        val original = "Türkçe — Nederlandse kennis — العربية — 🦜"
        val ingested = bridge.callTool("trinity_ingest", JSONObject().put("content", original).put("source", "integration-test"))
        assertFalse(ingested.toString(), ingested.getBoolean("isError"))
        val objectId = ingested.getJSONObject("structuredContent").getString("obj_id")
        assertTrue(objectId.isNotBlank())
        val retrieved = bridge.callTool("trinity_retrieve", JSONObject().put("obj_id", objectId))
        assertFalse(retrieved.toString(), retrieved.getBoolean("isError"))
        val encoded = retrieved.getJSONObject("structuredContent").getString("content_base64")
        assertEquals(original, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        val denied = bridge.handleTool("trinity_retrieve", JSONObject().put("obj_id", objectId), "different-user")
        assertTrue(denied.getBoolean("isError"))
        val identityInArguments = bridge.callTool("trinity_retrieve", JSONObject().put("obj_id", objectId).put("principal", "different-user"))
        assertTrue(identityInArguments.getBoolean("isError"))
        val deleted = bridge.callTool("trinity_delete", JSONObject().put("obj_id", objectId))
        assertFalse(deleted.toString(), deleted.getBoolean("isError"))
        val missing = bridge.callTool("trinity_retrieve", JSONObject().put("obj_id", objectId))
        assertTrue(missing.getBoolean("isError"))
    }

    @Test fun shutdownReallyClosesTheListener() = runBlocking {
        bridge.stopServer()
        try {
            bridge.listTools()
            fail("Stopped MCP server should not produce a tool catalog")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("stopped"))
        }
    }

    private fun post(message: JSONObject, token: String = bridge.localAccessToken): String {
        val url = URI(bridge.endpoint)
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        return Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 5_000
            val header = "POST /mcp HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
