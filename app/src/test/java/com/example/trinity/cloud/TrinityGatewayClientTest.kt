package com.example.trinity.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TrinityGatewayClientTest {
    @Test fun publicMcpUrlMapsToTheSameGatewayDeviceChannel() {
        assertEquals("https://gateway.example/device/ws", TrinityGatewayClient.gatewayUrl("https://gateway.example/mcp"))
        assertEquals("https://gateway.example/device/ws", TrinityGatewayClient.gatewayUrl("wss://gateway.example/device/ws"))
        assertEquals("https://gateway.example/trinity/device/ws", TrinityGatewayClient.gatewayUrl("https://gateway.example/trinity/"))
    }

    @Test fun credentialAndInsecureRemoteUrlsAreRejectedBeforeConnection() {
        for (url in listOf("https://user:secret@gateway.example", "https://gateway.example?token=value", "http://gateway.example")) {
            try {
                TrinityGatewayClient.gatewayUrl(url)
                fail("Should reject unsafe gateway URL")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals("http://127.0.0.1:8123/device/ws", TrinityGatewayClient.gatewayUrl("http://127.0.0.1:8123"))
    }
}
