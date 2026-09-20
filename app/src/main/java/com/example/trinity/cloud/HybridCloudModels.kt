package com.example.trinity.cloud

import java.util.UUID

data class ChatGptModel(
    val id: String,
    val name: String,
    val description: String,
    val defaultReasoning: String? = null,
    val reasoningLevels: List<String> = emptyList(),
    val acceptsImages: Boolean = true
) {
    companion object {
        fun custom(modelId: String): ChatGptModel {
            val trimmed = modelId.trim()
            val isReasoning = trimmed.startsWith("o1") || trimmed.startsWith("o3") || trimmed.startsWith("o4")
            return ChatGptModel(
                id = trimmed,
                name = trimmed,
                description = "User-specified custom model ID.",
                defaultReasoning = if (isReasoning) "medium" else null,
                reasoningLevels = if (isReasoning) listOf("low", "medium", "high") else emptyList(),
                acceptsImages = true
            )
        }

        val LATEST_MODELS = listOf(
            ChatGptModel(
                id = "gpt-4o",
                name = "ChatGPT-4o",
                description = "Flagship omnimodal model for complex reasoning and general tasks.",
                defaultReasoning = null,
                reasoningLevels = emptyList(),
                acceptsImages = true
            ),
            ChatGptModel(
                id = "gpt-4o-mini",
                name = "ChatGPT-4o Mini",
                description = "Lightweight, high-speed model for lightweight tasks.",
                defaultReasoning = null,
                reasoningLevels = emptyList(),
                acceptsImages = true
            ),
            ChatGptModel(
                id = "gpt-4.5-preview",
                name = "ChatGPT-4.5 Preview",
                description = "Frontier intelligence model with broad knowledge and deep intuition.",
                defaultReasoning = null,
                reasoningLevels = emptyList(),
                acceptsImages = true
            ),
            ChatGptModel(
                id = "o1",
                name = "OpenAI o1",
                description = "Deep reasoning model designed to solve hard problems in science & coding.",
                defaultReasoning = "medium",
                reasoningLevels = listOf("low", "medium", "high"),
                acceptsImages = true
            ),
            ChatGptModel(
                id = "o1-mini",
                name = "OpenAI o1-mini",
                description = "Fast reasoning model optimized for math, code, and STEM.",
                defaultReasoning = "medium",
                reasoningLevels = listOf("low", "medium", "high"),
                acceptsImages = false
            ),
            ChatGptModel(
                id = "o3-mini",
                name = "OpenAI o3-mini",
                description = "High-efficiency STEM reasoning engine with configurable effort.",
                defaultReasoning = "medium",
                reasoningLevels = listOf("low", "medium", "high"),
                acceptsImages = false
            ),
            ChatGptModel(
                id = "chatgpt-4o-latest",
                name = "ChatGPT-4o Latest",
                description = "Continuously updated dynamic ChatGPT-4o model.",
                defaultReasoning = null,
                reasoningLevels = emptyList(),
                acceptsImages = true
            )
        )
    }
}

data class ToolCallEvent(
    val toolName: String,
    val arguments: Map<String, Any>,
    val executionLatencyMs: Long,
    val chunksReturnedCount: Int,
    val averageKappa: Float,
    val tessaStatuses: List<String>,
    val integrityVerified: Boolean,
    val rawResultSnippet: String
)

data class HybridChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val modelId: String = "gpt-4o",
    val toolCall: ToolCallEvent? = null,
    val isGrounded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
