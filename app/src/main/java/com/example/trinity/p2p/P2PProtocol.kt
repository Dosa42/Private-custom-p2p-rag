package com.example.trinity.p2p

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Trinity private swarm protocol, not the public BitTorrent wire protocol. */
object P2PCodec {
    const val MAGIC = 0x54525032 // TRP2
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private val random = SecureRandom()

    fun challenge(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun deriveKey(sharedSecret: String): SecretKeySpec {
        require(sharedSecret.length >= 32) { "Use a randomly generated swarm secret of at least 32 characters" }
        return SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(
                ("trinity-private-swarm-v1\u0000" + sharedSecret).toByteArray(Charsets.UTF_8)
            ), "AES"
        )
    }

    /** A fresh server challenge binds each frame to this connection and its direction. */
    fun writeFrame(output: DataOutputStream, key: SecretKeySpec, challenge: ByteArray,
                   direction: String, message: JSONObject) {
        val plain = message.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_FRAME_BYTES - 28) { "Peer message exceeds frame limit" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge + direction.toByteArray(Charsets.US_ASCII))
        val encrypted = cipher.doFinal(plain)
        output.writeInt(nonce.size + encrypted.size)
        output.write(nonce)
        output.write(encrypted)
        output.flush()
    }

    fun readFrame(input: DataInputStream, key: SecretKeySpec, challenge: ByteArray,
                  direction: String): JSONObject {
        val length = input.readInt()
        require(length in 28..MAX_FRAME_BYTES) { "Invalid peer frame length" }
        val nonce = ByteArray(12).also(input::readFully)
        val encrypted = ByteArray(length - 12).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge + direction.toByteArray(Charsets.US_ASCII))
        return JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }
}
