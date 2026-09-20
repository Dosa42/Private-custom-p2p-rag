package com.example.trinity.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class QuantizationResult(
    val quantizedBytes: ByteArray,
    val rawBytesCount: Int,
    val quantizedBytesCount: Int,
    val compressionRatioPercent: Float, // e.g. 74.5% - 75.0%
    val fidelityCosine: Float // Cosine similarity between original float32 and dequantized int8
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantizationResult) return false
        return quantizedBytes.contentEquals(other.quantizedBytes)
    }

    override fun hashCode(): Int = quantizedBytes.contentHashCode()
}

object VectorQuantizer {

    private const val MAGIC_SQ8: Byte = 0x51 // 'Q'

    /**
     * Scalar Quantization (SQ8):
     * Converts float32 values (4 bytes) to signed int8 values (1 byte).
     * Symmetrical scaling preserves exact zero and high dynamic range.
     * Raw: 384 * 4 = 1536 bytes
     * Quantized: 1 (magic) + 4 (scale) + 4 (dimension) + 384 = 393 bytes (~74.4% network reduction)
     */
    fun quantizeSQ8(vector: FloatArray): QuantizationResult {
        val dim = vector.size
        val rawBytesCount = dim * 4

        var maxAbs = 0.0f
        for (f in vector) {
            val a = abs(f)
            if (a > maxAbs) maxAbs = a
        }

        val scale = if (maxAbs > 1e-7f) maxAbs / 127.0f else 1.0f

        // Buffer: 1 byte magic + 4 byte scale + 4 byte dim + dim bytes int8
        val buffer = ByteBuffer.allocate(1 + 4 + 4 + dim).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC_SQ8)
        buffer.putFloat(scale)
        buffer.putInt(dim)

        val int8Array = ByteArray(dim)
        for (i in 0 until dim) {
            val q = (vector[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
            int8Array[i] = q
            buffer.put(q)
        }

        val bytes = buffer.array()
        val quantizedBytesCount = bytes.size
        val savings = ((rawBytesCount - quantizedBytesCount).toFloat() / rawBytesCount.toFloat()) * 100.0f

        // Calculate reconstruction fidelity
        val dequantized = dequantizeSQ8(bytes)
        val fidelity = computeCosine(vector, dequantized)

        return QuantizationResult(
            quantizedBytes = bytes,
            rawBytesCount = rawBytesCount,
            quantizedBytesCount = quantizedBytesCount,
            compressionRatioPercent = savings,
            fidelityCosine = fidelity
        )
    }

    /**
     * Dequantizes SQ8 int8 bytes back to float32 values.
     */
    fun dequantizeSQ8(bytes: ByteArray): FloatArray {
        if (bytes.size < 9) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.get()
        if (magic != MAGIC_SQ8) {
            // Fallback for legacy raw floats
            return FloatArray(0)
        }

        val scale = buffer.float
        val dim = buffer.int
        val result = FloatArray(dim)

        for (i in 0 until dim) {
            if (buffer.hasRemaining()) {
                val q = buffer.get()
                result[i] = q.toFloat() * scale
            }
        }

        // Re-normalize vector
        var sumSquares = 0.0
        for (v in result) sumSquares += (v * v)
        val norm = sqrt(sumSquares).toFloat()
        if (norm > 1e-6f) {
            for (i in 0 until dim) result[i] /= norm
        }

        return result
    }

    private fun computeCosine(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 1.0f
        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for (i in v1.indices) {
            dot += (v1[i] * v2[i])
            norm1 += (v1[i] * v1[i])
            norm2 += (v2[i] * v2[i])
        }
        val denom = (sqrt(norm1) * sqrt(norm2)) + 1e-8
        return (dot / denom).toFloat().coerceIn(0.0f, 1.0f)
    }
}
