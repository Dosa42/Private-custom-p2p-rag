package com.example.trinity.core

import com.example.trinity.model.SemanticVector
import java.security.MessageDigest
import kotlin.math.sqrt

class SemanticForceEngine(val dimension: Int = 384) {

    fun embed(text: String): SemanticVector {
        val vector = FloatArray(dimension)
        val normalizedText = text.lowercase().trim()
        if (normalizedText.isEmpty()) {
            return SemanticVector(vector, dimension)
        }

        // Tokenization & character n-gram hashing projection for semantic force representation
        val tokens = normalizedText.split(Regex("[\\s,;:.!?\"'()\\[\\]{}]+")).filter { it.isNotBlank() }

        for ((idx, token) in tokens.withIndex()) {
            val weight = 1.0f / (1.0f + 0.05f * idx) // Position decay
            applyTokenHash(token, weight, vector)

            // 3-gram char slices for subword awareness
            if (token.length >= 3) {
                for (i in 0..token.length - 3) {
                    val tri = token.substring(i, i + 3)
                    applyTokenHash(tri, weight * 0.5f, vector)
                }
            }
        }

        // Add overall document digest dispersion
        val md = MessageDigest.getInstance("SHA-512")
        val digest = md.digest(normalizedText.toByteArray(Charsets.UTF_8))
        for (i in 0 until dimension) {
            val byteVal = digest[i % digest.size].toInt() and 0xFF
            val sign = if ((digest[(i * 3 + 7) % digest.size].toInt() and 1) == 0) 1.0f else -1.0f
            vector[i] += sign * (byteVal / 255.0f) * 0.2f
        }

        // Normalize vector to unit length
        var sumSquares = 0.0
        for (v in vector) {
            sumSquares += (v * v)
        }
        val norm = sqrt(sumSquares).toFloat()
        if (norm > 0f) {
            for (i in 0 until dimension) {
                vector[i] /= norm
            }
        }

        return SemanticVector(data = vector, dimension = dimension, norm = 1.0f)
    }

    private fun applyTokenHash(token: String, weight: Float, target: FloatArray) {
        val hash = token.hashCode()
        val index1 = (Math.abs(hash) % dimension)
        val index2 = (Math.abs(hash * 31 + 17) % dimension)
        val index3 = (Math.abs(hash * 97 + 53) % dimension)

        val sign1 = if ((hash and 1) == 0) 1.0f else -1.0f
        val sign2 = if ((hash and 2) == 0) 1.0f else -1.0f
        val sign3 = if ((hash and 4) == 0) 1.0f else -1.0f

        target[index1] += sign1 * weight
        target[index2] += sign2 * weight * 0.7f
        target[index3] += sign3 * weight * 0.4f
    }

    fun computeSimilarity(v1: SemanticVector, v2: SemanticVector): Float {
        val a = v1.data
        val b = v2.data
        if (a.size != b.size || a.isEmpty()) return 0.0f

        var dot = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i])
        }
        val denom = (v1.norm * v2.norm).toDouble() + 1e-8
        return (dot / denom).toFloat().coerceIn(-1.0f, 1.0f)
    }
}
