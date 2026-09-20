package com.example.trinity.cloud

import com.example.trinity.auth.OpenAIOAuthSession
import com.example.trinity.core.TrinityRAGServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthExpiredException(val rejectedAccessToken: String) : IOException("Authentication rejected (HTTP 401). Refresh the session or log in again.")

/** ChatGPT OAuth uses the Codex Responses backend; API keys use the public Responses API. */
class HybridCloudBridge(
    private val ragServer: TrinityRAGServer,
    private val mcpBridge: TrinityMCPBridge = TrinityMCPBridge(ragServer),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        // Protocol shape checked against openai/codex; this is wire compatibility, not app identity.
        private const val CODEX_CLIENT_VERSION = "0.155.1"
        private const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"
        private const val API_BASE = "https://api.openai.com/v1"
    }

    val systemPrompt: String = """
        You are the Central Cognitive Reasoner of the Trinity Hybrid RAG framework powered by ChatGPT.
        You run in the cloud and DO NOT have direct access to the user's private computer, local RAM, or local vector index.
        Your role is purely cognitive: reasoning, language synthesis, and code generation.
        The local standalone framework acts as your sovereign on-demand data provider.

        MANDATORY SYSTEM INSTRUCTIONS:
        1. FORCED TOOL USE: You must NEVER fabricate, guess, or hallucinate facts about the private system, projects, or sovereign knowledge base. You are MANDATED to invoke the local tool 'trinity_query' to retrieve verified local knowledge.
        2. INTERPRETATION OF KAPPA (κ) & TESSA METADATA:
           - Each chunk returned contains a stability Kappa score (κ from 0.0 to 1.0) and a TESSA governance tier.
           - Sovereign Truth (κ >= 0.85, TESSA: BENEFIT_ACCEL_EFF): Primary weight. Base your deductions on this.
           - Verified Consensus (0.70 <= κ < 0.85, TESSA: HARMONIC_BALANCE / NEUTRAL): Standard weight.
           - Transitory / Quarantined (κ < 0.70, TESSA: THREAT_DECEL / QUARANTINE_ISOLATE): Low weight. You must state warnings or caveats.
        3. DATA INTEGRITY: Report integrity verification only when the tool result explicitly confirms it. Retrieved content sent to this cloud model has left the device.
    """.trimIndent()

    suspend fun fetchAvailableModels(token: String, accountId: String? = null): List<ChatGptModel> =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank()) { "Log in or provide an API key before loading models." }
            val oauth = !accountId.isNullOrBlank()
            val url = if (oauth) "$CODEX_BASE/models?client_version=$CODEX_CLIENT_VERSION" else "$API_BASE/models"
            httpClient.newCall(authenticatedRequest(url, token, accountId).get().build()).execute().use { response ->
                if (response.code == 401) throw AuthExpiredException(token)
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(providerError(response.code, raw))
                parseModels(JSONObject(raw), oauth)
            }
        }

    suspend fun executeHybridQuery(
        userPrompt: String,
        selectedModel: ChatGptModel,
        reasoningEffort: String? = null,
        oauthSession: OpenAIOAuthSession? = null,
        customApiKey: String? = null,
        onToolCallExecuted: ((ToolCallEvent) -> Unit)? = null,
        refreshSession: (suspend (String) -> OpenAIOAuthSession)? = null
    ): HybridChatMessage = withContext(Dispatchers.IO) {
        require(userPrompt.isNotBlank()) { "Enter a prompt." }
        require(selectedModel.id.isNotBlank()) { "Load and select a model, or enter a model ID." }
        val apiKey = customApiKey?.takeIf { it.isNotBlank() }
        var session = if (apiKey == null) oauthSession else null
        var token = apiKey ?: session?.accessToken
            ?: throw IllegalStateException("No ChatGPT session or API key. Log in before sending a message.")
        val endpoint = if (session != null) "$CODEX_BASE/responses" else "$API_BASE/responses"
        val tools = responseTools(mcpBridge.listTools())
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt))
        var lastToolEvent: ToolCallEvent? = null
        var grounded = false
        val ownerId = ragServer.currentOwnerId()

        while (true) {
            currentCoroutineContext().ensureActive()
            val payload = JSONObject().apply {
                put("model", selectedModel.id)
                put("instructions", systemPrompt)
                put("input", input)
                put("tools", tools)
                put("tool_choice", "auto")
                put("parallel_tool_calls", true)
                put("store", false)
                put("stream", true)
                put("include", JSONArray().put("reasoning.encrypted_content"))
                if (!reasoningEffort.isNullOrBlank()) put("reasoning", JSONObject().put("effort", reasoningEffort))
            }
            // Retry just this network request. Previously executed MCP writes must never be replayed.
            val output = try {
                requestResponse(endpoint, payload, token, session?.accountId)
            } catch (e: AuthExpiredException) {
                if (session == null || refreshSession == null) throw e
                session = refreshSession(e.rejectedAccessToken)
                token = session.accessToken
                requestResponse(endpoint, payload, token, session.accountId)
            }
            var calls = 0
            val answer = StringBuilder()
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                input.put(item) // Preserve reasoning and function-call items for stateless continuation.
                if (item.optString("type") == "message") {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        val text = when (part.optString("type")) {
                            "output_text" -> part.optString("text")
                            "refusal" -> part.optString("refusal")
                            else -> ""
                        }
                        if (text.isNotBlank()) {
                            if (answer.isNotEmpty()) answer.append('\n')
                            answer.append(text)
                        }
                    }
                }
            }
            // All calls from a response are answered before asking the model to continue.
            for (i in 0 until output.length()) {
                val call = output.getJSONObject(i)
                if (call.optString("type") != "function_call") continue
                currentCoroutineContext().ensureActive()
                calls++
                val name = call.getString("name")
                val args = JSONObject(call.getString("arguments"))
                val start = System.nanoTime()
                val result = mcpBridge.callTool(name, args, ownerId)
                val event = toolEvent(name, args, result, (System.nanoTime() - start) / 1_000_000)
                lastToolEvent = event
                grounded = grounded || (name == "trinity_query" && !result.optBoolean("isError") && event.chunksReturnedCount > 0)
                onToolCallExecuted?.invoke(event)
                input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", call.getString("call_id"))
                    put("output", result.toString())
                })
            }
            if (calls == 0) {
                if (answer.isBlank()) throw IOException("Responses completed without an answer or a tool call.")
                return@withContext HybridChatMessage(
                    role = "assistant", content = answer.toString(), modelId = selectedModel.id,
                    toolCall = lastToolEvent, isGrounded = grounded
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable")
    }

    private suspend fun requestResponse(endpoint: String, payload: JSONObject, token: String, accountId: String?): JSONArray = coroutineScope {
        val request = authenticatedRequest(endpoint, token, accountId)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = httpClient.newCall(request)
        // Close a blocked SSE socket immediately when the caller cancels its coroutine.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code == 401) throw AuthExpiredException(token)
                if (!response.isSuccessful) throw IOException(providerError(response.code, response.body?.string().orEmpty()))
                val body = response.body ?: throw IOException("Responses returned an empty body.")
                if (body.contentType()?.subtype != "event-stream") {
                    throw IOException("Responses did not return the requested text/event-stream transport.")
                }
                body.charStream().buffered().use { reader ->
                    readResponseEvents(reader) { currentCoroutineContext().ensureActive() }
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun authenticatedRequest(url: String, token: String, accountId: String?): Request.Builder = Request.Builder()
        .url(url).header("Authorization", "Bearer $token").header("User-Agent", "Trinity-Android/1.0")
        .apply { if (!accountId.isNullOrBlank()) header("ChatGPT-Account-Id", accountId) }

    internal fun parseModels(json: JSONObject, oauth: Boolean): List<ChatGptModel> {
        val entries = json.optJSONArray(if (oauth) "models" else "data")
            ?: throw IOException("Model catalog has no ${if (oauth) "models" else "data"} array.")
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.getJSONObject(index)
            val id = item.optString(if (oauth) "slug" else "id")
            if (id.isBlank()) return@mapNotNull null
            val levels = item.optJSONArray("supported_reasoning_levels")
            val modalities = item.optJSONArray("input_modalities")
            ChatGptModel(
                id = id,
                name = item.optString("display_name").ifBlank { id },
                description = if (item.isNull("description")) "Returned by the authenticated model catalog." else item.optString("description"),
                defaultReasoning = item.optString("default_reasoning_level").takeIf { it.isNotBlank() && it != "null" },
                reasoningLevels = if (levels == null) emptyList() else (0 until levels.length())
                    .mapNotNull { levels.optJSONObject(it)?.optString("effort")?.takeIf(String::isNotBlank) },
                acceptsImages = modalities != null && (0 until modalities.length()).any { modalities.optString(it) == "image" }
            )
        }.distinctBy { it.id }
    }

    private fun responseTools(mcpTools: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until mcpTools.length()) {
            val tool = mcpTools.getJSONObject(i)
            put(JSONObject().apply {
                put("type", "function")
                put("name", tool.getString("name"))
                put("description", tool.optString("description"))
                put("parameters", tool.getJSONObject("inputSchema"))
                put("strict", false)
            })
        }
    }

    private fun toolEvent(name: String, args: JSONObject, result: JSONObject, elapsed: Long): ToolCallEvent {
        val textParts = result.optJSONArray("content") ?: JSONArray()
        val text = (0 until textParts.length()).mapNotNull { textParts.optJSONObject(it)?.optString("text") }.joinToString("\n")
        val structured = result.optJSONObject("structuredContent") ?: try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        val chunks = structured.optJSONArray("chunks") ?: JSONArray()
        val scores = (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.optDouble("kappa")?.takeIf(Double::isFinite) }
        return ToolCallEvent(
            toolName = name,
            arguments = args.keys().asSequence().associateWith { args.get(it) },
            executionLatencyMs = elapsed,
            chunksReturnedCount = chunks.length(),
            averageKappa = if (scores.isEmpty()) 0f else scores.average().toFloat(),
            tessaStatuses = (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.optString("tessa")?.takeIf(String::isNotBlank) }.distinct(),
            integrityVerified = structured.optBoolean("integrity_verified", false),
            rawResultSnippet = text.take(240),
            isError = result.optBoolean("isError", false)
        )
    }
}

/** Consume SSE frames until the authoritative terminal event; truncated streams are errors. */
internal suspend fun readResponseEvents(reader: BufferedReader, checkCancellation: suspend () -> Unit = {}): JSONArray {
    val completedItems = sortedMapOf<Int, JSONObject>()
    val data = StringBuilder()
    while (true) {
        checkCancellation()
        val line = reader.readLine()
        if (line != null && line.isNotEmpty()) {
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            continue
        }
        if (data.isNotEmpty()) {
            val frame = data.toString()
            data.setLength(0)
            if (frame == "[DONE]") throw IOException("Responses stream ended without response.completed.")
            val event = JSONObject(frame)
            when (event.optString("type")) {
                "response.output_item.done" -> completedItems[event.optInt("output_index", completedItems.size)] = event.getJSONObject("item")
                "response.failed", "response.incomplete", "error" -> {
                    val response = event.optJSONObject("response") ?: event
                    val detail = response.optJSONObject("error")?.optString("message")
                        ?: response.optJSONObject("incomplete_details")?.optString("reason")
                        ?: event.optString("message")
                    throw IOException("${event.optString("type")}: ${detail.orEmpty().ifBlank { "Generation did not complete." }}")
                }
                "response.completed" -> {
                    val response = event.getJSONObject("response")
                    val status = response.optString("status")
                    if (status.isNotBlank() && status != "completed") throw IOException("Responses terminal status: $status")
                    val output = response.optJSONArray("output")
                    return if (output != null && output.length() > 0) output else JSONArray(completedItems.values.toList())
                }
            }
        }
        if (line == null) throw IOException("Responses stream closed before response.completed.")
    }
}

private fun providerError(code: Int, raw: String): String {
    val detail = try {
        val error = JSONObject(raw).optJSONObject("error")
        error?.optString("message").orEmpty().take(600)
    } catch (_: Exception) { "" }
    return "Provider request failed (HTTP $code)${if (detail.isNotBlank()) ": $detail" else ""}"
}
