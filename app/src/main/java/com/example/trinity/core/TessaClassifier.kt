package com.example.trinity.core

import com.example.trinity.model.ConfidenceLevel
import com.example.trinity.model.KnowledgeChunk
import com.example.trinity.model.SDCKClassification
import com.example.trinity.model.StorageTier
import com.example.trinity.model.TessaResult

object TessaClassifier {

    private data class TessaRule(
        val threshold: Float,
        val name: String,
        val permission: String,
        val tier: StorageTier
    )

    private val TESSA_TABLE = listOf(
        TessaRule(0.85f, "BENEFIT_ACCEL_EFF", "ALLOW", StorageTier.HOT),
        TessaRule(0.75f, "BENEFIT_ACCEL", "ALLOW", StorageTier.HOT),
        TessaRule(0.65f, "BENEFIT_STABLE_EFF", "ALLOW", StorageTier.HOT),
        TessaRule(0.55f, "BENEFIT_STABLE", "ALLOW", StorageTier.WARM),
        TessaRule(0.45f, "BENEFIT_DECAY", "MONITOR", StorageTier.WARM),
        TessaRule(0.35f, "PROMOTE", "MONITOR", StorageTier.WARM),
        TessaRule(0.25f, "NEUTRAL_STABLE", "MONITOR", StorageTier.COLD),
        TessaRule(0.15f, "NEUTRAL_COLLAPSE", "MONITOR", StorageTier.COLD),
        TessaRule(0.10f, "HOSTILE_STABLE", "MONITOR", StorageTier.COLD),
        TessaRule(0.05f, "HOSTILE_UNSTABLE", "ISOLATE", StorageTier.FROZEN),
        TessaRule(0.00f, "HOSTILE_ACCEL", "ISOLATE", StorageTier.FROZEN)
    )

    fun kappaFromGVec(g1: Float, g2: Float, g3: Float, g4: Float = 0.0f): Float {
        val weighted = 0.35f * g1 + 0.25f * g2 + 0.28f * g3 + 0.12f * g4
        return weighted.coerceIn(0.0f, 1.0f)
    }

    fun classify(kappa: Float): TessaResult {
        for (rule in TESSA_TABLE) {
            if (kappa > rule.threshold) {
                return TessaResult(
                    kappa = kappa,
                    name = rule.name,
                    permission = rule.permission,
                    tier = rule.tier
                )
            }
        }
        return TessaResult(
            kappa = kappa,
            name = "HOSTILE_ACCEL",
            permission = "ISOLATE",
            tier = StorageTier.FROZEN
        )
    }
}

class SDCKCanon(val epsilon: Float = 0.1f) {

    private val history = mutableListOf<SDCKClassification>()

    fun classify(chunk: KnowledgeChunk, context: Map<String, Any>? = null): SDCKClassification {
        val score = calculateScore(chunk)
        val (tier, confidence) = mapScore(score)
        val classification = SDCKClassification(
            tier = tier,
            confidence = confidence,
            score = score,
            reasoning = "score=${"%.3f".format(score)} | kappa=${"%.3f".format(chunk.kappa)} | src=${chunk.source} | len=${chunk.content.length} | access=${chunk.accessCount}"
        )
        history.add(classification)
        return classification
    }

    private fun calculateScore(chunk: KnowledgeChunk): Float {
        var s = 0.0f
        val len = chunk.content.length
        s += when {
            len > 500 -> 0.25f
            len > 100 -> 0.15f
            else -> 0.05f
        }

        s += (chunk.accessCount * 0.05f).coerceAtMost(0.3f)

        val srcWeights = mapOf(
            "guardian" to 0.25f,
            "kral" to 0.20f,
            "hub" to 0.15f,
            "imperial" to 0.15f
        )
        s += srcWeights[chunk.source.lowercase()] ?: 0.10f

        s += chunk.kappa * 0.30f
        return s.coerceAtMost(1.0f)
    }

    private fun mapScore(score: Float): Pair<StorageTier, ConfidenceLevel> {
        return when {
            score >= 0.75f -> Pair(StorageTier.HOT, ConfidenceLevel.CANON)
            score >= 0.55f -> Pair(StorageTier.HOT, ConfidenceLevel.HIGH)
            score >= 0.40f -> Pair(StorageTier.WARM, ConfidenceLevel.MEDIUM)
            score >= 0.25f -> Pair(StorageTier.COLD, ConfidenceLevel.LOW)
            else -> Pair(StorageTier.FROZEN, ConfidenceLevel.UNCERTAIN)
        }
    }

    fun shouldConsolidate(chunk: KnowledgeChunk): Boolean {
        val ageMs = System.currentTimeMillis() - chunk.lastAccessed
        return ageMs > 86_400_000L && chunk.accessCount < 3
    }
}
