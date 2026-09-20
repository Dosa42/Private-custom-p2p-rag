package com.example.trinity.cloud

enum class CloudProvider(val displayName: String, val modelIdentifier: String, val endpointName: String) {
    GEMINI("Google Gemini 3.5 Flash", "gemini-3.5-flash", "Google Generative Language API"),
    OPENAI_GPT4O("OpenAI GPT-4o", "gpt-4o", "OpenAI Chat Completions API"),
    CLAUDE_SONNET("Anthropic Claude 3.5", "claude-3-5-sonnet", "Anthropic Messages API")
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
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val provider: CloudProvider,
    val toolCall: ToolCallEvent? = null,
    val isGrounded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
