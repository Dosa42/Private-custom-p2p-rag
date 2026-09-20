package com.example.trinity.cloud

import java.util.UUID

data class ChatGptModel(
    val id: String,
    val name: String,
    val description: String,
    val defaultReasoning: String? = null,
    val reasoningLevels: List<String> = emptyList(),
    val acceptsImages: Boolean = false
) {
    companion object {
        fun custom(modelId: String): ChatGptModel = ChatGptModel(
            id = modelId.trim(),
            name = modelId.trim(),
            description = "User-specified model ID; availability is checked by the provider.",
            acceptsImages = false
        )

        // Populate from the authenticated provider; no invented availability or fallback models.
        val LATEST_MODELS: List<ChatGptModel> = emptyList()
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
    val rawResultSnippet: String,
    val isError: Boolean = false
)

data class HybridChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val modelId: String = "",
    val toolCall: ToolCallEvent? = null,
    val isGrounded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
