package com.example.trinity.p2p

import com.example.trinity.model.P2PMessage
import com.example.trinity.model.P2PMessageType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

object P2PCodec {

    fun encode(message: P2PMessage): ByteArray {
        val json = JSONObject().apply {
            put("type", message.msgType.code)
            put("payload", JSONObject(message.payload))
            put("timestamp", message.timestamp)
            put("peer_id", message.peerId ?: "")
        }
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        val compressed = compress(raw)

        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(compressed.size).array()
        return header + compressed
    }

    fun decode(bytes: ByteArray): P2PMessage? {
        if (bytes.size < 4) return null
        return try {
            val length = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (bytes.size < 4 + length) return null

            val compressed = bytes.copyOfRange(4, 4 + length)
            val decompressed = decompress(compressed)
            val json = JSONObject(String(decompressed, Charsets.UTF_8))

            val typeCode = json.getInt("type")
            val payloadObj = json.getJSONObject("payload")
            val payloadMap = mutableMapOf<String, Any>()
            val keys = payloadObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                payloadMap[k] = payloadObj.get(k)
            }

            P2PMessage(
                msgType = P2PMessageType.fromCode(typeCode),
                payload = payloadMap,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                peerId = json.optString("peer_id", null)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
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

    private fun decompress(data: ByteArray): ByteArray {
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
}
