package com.example.trinity.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trinity.core.IngestPipelineResult
import com.example.trinity.core.QueryResultItem
import com.example.trinity.core.TessaClassifier
import com.example.trinity.core.TrinityRAGServer
import com.example.trinity.model.KnowledgeChunk
import com.example.trinity.model.NodeRole
import com.example.trinity.model.StorageTier
import com.example.trinity.model.StoredObject
import com.example.trinity.model.TessaResult
import com.example.trinity.model.TorrentMeta
import com.example.trinity.p2p.PeerNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.example.trinity.cloud.TrinityRuntime
import com.example.trinity.cloud.TrinityConnectionService
import com.example.trinity.cloud.AuthExpiredException
import androidx.core.content.ContextCompat
import android.content.Intent

enum class TrinityTab(val title: String, val subtitle: String) {
    PIPELINE_RAG("RAG Core", "Ingest & Vector Query"),
    HYBRID_CLOUD("Cloud AI Bridge", "ChatGPT OAuth PKCE"),
    STORAGE_TIERS("Storage Tiers", "HOT/WARM/COLD/FROZEN"),
    TORRENT_PIECES("256KB Pieces", "BitTorrent Chunk Cache"),
    P2P_SWARM("Swarm & Tracker", "P2P Protocol Stack")
}

class TrinityViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = TrinityRuntime.get(application)
    val ragServer = runtime.ragServer

    // Current navigation tab
    private val _currentTab = MutableStateFlow(TrinityTab.PIPELINE_RAG)
    val currentTab: StateFlow<TrinityTab> = _currentTab.asStateFlow()

    // Ingest Form inputs
    val inputContent = MutableStateFlow("")
    val inputSource = MutableStateFlow("guardian")
    val g1 = MutableStateFlow(0.85f)
    val g2 = MutableStateFlow(0.70f)
    val g3 = MutableStateFlow(0.80f)
    val g4 = MutableStateFlow(0.40f)

    // Live Kappa & Tessa preview
    val liveKappa = MutableStateFlow(0.75f)
    val liveTessa = MutableStateFlow(TessaClassifier.classify(0.75f))

    // Query & Search State
    val searchQuery = MutableStateFlow("")
    val queryMinKappa = MutableStateFlow(0.0f)
    val queryTierFilter = MutableStateFlow<StorageTier?>(null)
    private val _queryResults = MutableStateFlow<List<QueryResultItem>>(emptyList())
    val queryResults: StateFlow<List<QueryResultItem>> = _queryResults.asStateFlow()
    val generatedLlmContext = MutableStateFlow<String?>(null)

    // Storage & Torrent State
    private val _storedObjects = MutableStateFlow<List<StoredObject>>(emptyList())
    val storedObjects: StateFlow<List<StoredObject>> = _storedObjects.asStateFlow()

    private val _torrents = MutableStateFlow<List<TorrentMeta>>(emptyList())
    val torrents: StateFlow<List<TorrentMeta>> = _torrents.asStateFlow()

    val selectedTorrentHash = MutableStateFlow<String?>(null)
    val reassembledContent = MutableStateFlow<String?>(null)

    // Swarm State
    val connectedPeers: StateFlow<List<PeerNode>> = ragServer.peerManager.connectedPeers
    val swarmLogs: StateFlow<List<String>> = ragServer.peerManager.eventLogs
    val currentNodeRole = MutableStateFlow(NodeRole.MASTER)

    val lastPipelineResult: StateFlow<IngestPipelineResult?> = ragServer.lastPipelineResult
    val knowledgeChunks: StateFlow<List<KnowledgeChunk>> = ragServer.chunksFlow

    init {
        updateLiveTessa()
        refreshStorageData()
        refreshTorrents()
    }

    fun setTab(tab: TrinityTab) {
        _currentTab.value = tab
    }

    fun updateGVector(index: Int, value: Float) {
        when (index) {
            1 -> g1.value = value
            2 -> g2.value = value
            3 -> g3.value = value
            4 -> g4.value = value
        }
        updateLiveTessa()
    }

    private fun updateLiveTessa() {
        val k = TessaClassifier.kappaFromGVec(g1.value, g2.value, g3.value, g4.value)
        liveKappa.value = k
        liveTessa.value = TessaClassifier.classify(k)
    }

    fun executeIngest() {
        val text = inputContent.value.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            ragServer.ingest(
                content = text,
                source = inputSource.value,
                g1 = g1.value,
                g2 = g2.value,
                g3 = g3.value,
                g4 = g4.value
            )
            inputContent.value = ""
            refreshStorageData()
            refreshTorrents()
        }
    }

    fun executeSearch() {
        val q = searchQuery.value.trim()
        if (q.isEmpty()) return

        viewModelScope.launch {
            val hits = ragServer.query(
                queryText = q,
                k = 6,
                minKappa = queryMinKappa.value,
                tierFilter = queryTierFilter.value
            )
            _queryResults.value = hits
            generatedLlmContext.value = null
        }
    }

    fun generateLlmContext() {
        val q = searchQuery.value.trim()
        if (q.isEmpty()) return
        generatedLlmContext.value = ragServer.buildLlmContext(q)
    }

    fun refreshStorageData() {
        _storedObjects.value = ragServer.storageManager.getAllObjects()
    }

    fun refreshTorrents() {
        _torrents.value = ragServer.torrentCache.getAllTorrents()
    }

    fun moveStorageTier(objId: String, targetTier: StorageTier) {
        viewModelScope.launch {
            ragServer.storageManager.moveTier(objId, targetTier)
            refreshStorageData()
            ragServer.refreshChunks()
        }
    }

    fun deleteStorageObject(objId: String) {
        viewModelScope.launch {
            ragServer.delete(objId)
            refreshStorageData()
        }
    }

    fun selectTorrent(infoHash: String) {
        selectedTorrentHash.value = infoHash
        val reassembled = ragServer.torrentCache.reassembleChunk(infoHash)
        reassembledContent.value = reassembled?.get("content") as? String
    }

    fun setNodeRole(role: NodeRole) {
        currentNodeRole.value = role
        ragServer.peerManager.nodeRole = role
    }

    fun syncSwarm() {
        viewModelScope.launch {
            try {
                ragServer.peerManager.syncNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionStatus.value = "Peer sync failed: ${error.message}"
            }
        }
    }

    fun downloadPieces(infoHash: String) {
        ragServer.peerManager.downloadTorrent(infoHash) {
            refreshTorrents()
            selectTorrent(infoHash)
        }
    }

    fun addManualPeer(address: String, port: Int, name: String) {
        try {
            ragServer.peerManager.addManualPeer(address, port, name)
            val saved = org.json.JSONArray(connectionPrefs.getString("peers", "[]"))
            val updated = org.json.JSONArray()
            for (index in 0 until saved.length()) {
                val peer = saved.getJSONObject(index)
                if (peer.optString("address") != address.trim() || peer.optInt("port") != port) updated.put(peer)
            }
            updated.put(org.json.JSONObject().put("address", address.trim()).put("port", port).put("name", name))
            connectionPrefs.edit().putString("peers", updated.toString()).apply()
        } catch (error: Exception) {
            connectionStatus.value = "Peer connection: ${error.message}"
        }
    }

    // --- CHATGPT OAUTH PKCE & HYBRID RAG CLOUD BRIDGE STATE ---
    val mcpBridge = runtime.mcpBridge
    val hybridCloudBridge = com.example.trinity.cloud.HybridCloudBridge(ragServer, mcpBridge)
    val gatewayStatus = runtime.gateway.status
    val gatewayPrincipal = runtime.gateway.principal
    private val connectionPrefs = application.getSharedPreferences(TrinityConnectionService.PREFS, android.content.Context.MODE_PRIVATE)
    val gatewayUrl = MutableStateFlow(connectionPrefs.getString("public_url", "").orEmpty())
    val gatewayDeviceToken = MutableStateFlow(connectionPrefs.getString("device_token", "").orEmpty())
    val swarmKey = MutableStateFlow(connectionPrefs.getString("swarm_key", "").orEmpty())
    val connectionStatus = runtime.connectionStatus
    val isMcpServerRunning = MutableStateFlow(false)

    val openAiSession = MutableStateFlow<com.example.trinity.auth.OpenAIOAuthSession?>(null)
    val isOpenAiAuthenticating = MutableStateFlow(false)
    val openAiAuthStatus = MutableStateFlow<String?>(null)

    val availableChatGptModels = MutableStateFlow(com.example.trinity.cloud.ChatGptModel.LATEST_MODELS)
    val selectedChatGptModel = MutableStateFlow(com.example.trinity.cloud.ChatGptModel.custom(""))
    val selectedReasoningEffort = MutableStateFlow<String?>(null)

    val customApiKey = MutableStateFlow("")
    val customModelInput = MutableStateFlow("")
    val isFetchingModels = MutableStateFlow(false)
    val hybridInputPrompt = MutableStateFlow("")
    val isHybridQuerying = MutableStateFlow(false)
    val lastToolCallEvent = MutableStateFlow<com.example.trinity.cloud.ToolCallEvent?>(null)

    private val _hybridChatMessages = MutableStateFlow<List<com.example.trinity.cloud.HybridChatMessage>>(emptyList())
    val hybridChatMessages: StateFlow<List<com.example.trinity.cloud.HybridChatMessage>> = _hybridChatMessages.asStateFlow()

    fun loadOpenAiSession(context: android.content.Context) {
        val session = com.example.trinity.auth.OpenAIOAuthManager.loadSession(context)
        openAiSession.value = session
        fetchDynamicModels()
        startMcpServer()
    }

    fun startMcpServer() {
        viewModelScope.launch {
            try {
                isMcpServerRunning.value = mcpBridge.startServer()
            } catch (error: Exception) {
                isMcpServerRunning.value = false
                connectionStatus.value = "Local MCP: ${error.message}"
            }
        }
    }

    fun stopMcpServer() {
        mcpBridge.stopServer()
        isMcpServerRunning.value = false
    }

    fun connectGateway() {
        val url = gatewayUrl.value.trim().trimEnd('/')
        if (!url.startsWith("https://") || gatewayDeviceToken.value.isBlank()) {
            connectionStatus.value = "Enter the HTTPS gateway URL and the device token created on that server."
            return
        }
        connectionPrefs.edit().putString("public_url", url)
            .putString("device_token", gatewayDeviceToken.value.trim())
            .putString("swarm_key", swarmKey.value)
            .putBoolean("connection_enabled", true).remove("connection_error").apply()
        startConnectionService()
    }

    fun disconnectGateway() {
        connectionPrefs.edit().putBoolean("connection_enabled", false).apply()
        runtime.gateway.disconnect()
        getApplication<Application>().stopService(Intent(getApplication(), TrinityConnectionService::class.java))
        connectionStatus.value = "Device connection stopped."
    }

    fun configurePeerNetwork() {
        if (swarmKey.value.isBlank()) {
            connectionStatus.value = "Enter the shared key used by your peer devices."
            return
        }
        connectionPrefs.edit().putString("swarm_key", swarmKey.value)
            .putBoolean("connection_enabled", true).apply()
        startConnectionService()
    }

    private fun startConnectionService() {
        try {
            ContextCompat.startForegroundService(getApplication(),
                Intent(getApplication(), TrinityConnectionService::class.java))
            connectionStatus.value = "Device connection requested; live status is shown below."
        } catch (error: Exception) {
            connectionStatus.value = "Connection could not start: ${error.message}"
        }
    }

    fun fetchDynamicModels() {
        viewModelScope.launch {
            isFetchingModels.value = true
            try {
                val apiKey = customApiKey.value.takeIf { it.isNotBlank() }
                val context = getApplication<Application>()
                val session = if (apiKey == null) {
                    com.example.trinity.auth.OpenAIOAuthManager.refreshIfNeeded(context).getOrThrow()
                        .also { openAiSession.value = it }
                } else null
                val fetched = try {
                    hybridCloudBridge.fetchAvailableModels(apiKey ?: session!!.accessToken, session?.accountId)
                } catch (expired: AuthExpiredException) {
                    if (apiKey != null) throw expired
                    val renewed = com.example.trinity.auth.OpenAIOAuthManager.refreshIfNeeded(
                        context, rejectedAccessToken = session!!.accessToken).getOrThrow()
                    openAiSession.value = renewed
                    hybridCloudBridge.fetchAvailableModels(renewed.accessToken, renewed.accountId)
                }
                availableChatGptModels.value = fetched
                if (selectedChatGptModel.value.id.isBlank() && fetched.isNotEmpty()) selectChatGptModel(fetched.first())
                openAiAuthStatus.value = "Loaded ${fetched.size} models from the authenticated provider."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                availableChatGptModels.value = emptyList()
                openAiAuthStatus.value = "Model request failed: ${error.message}"
            } finally {
                isFetchingModels.value = false
            }
        }
    }

    fun applyCustomModelInput(modelId: String = customModelInput.value) {
        val trimmed = modelId.trim()
        if (trimmed.isNotBlank()) {
            val customModel = com.example.trinity.cloud.ChatGptModel.custom(trimmed)
            val currentList = availableChatGptModels.value.toMutableList()
            if (currentList.none { it.id == customModel.id }) {
                currentList.add(0, customModel)
                availableChatGptModels.value = currentList
            }
            selectChatGptModel(customModel)
        }
    }

    fun startOpenAiPkceLogin(context: android.content.Context) {
        isOpenAiAuthenticating.value = true
        openAiAuthStatus.value = "Starting ChatGPT OAuth PKCE flow..."

        viewModelScope.launch {
            val result = com.example.trinity.auth.OpenAIOAuthManager.authenticate(
                context = context,
                onStatusUpdate = { status ->
                    openAiAuthStatus.value = status
                }
            )

            result.onSuccess { session ->
                openAiSession.value = session
                isOpenAiAuthenticating.value = false
                openAiAuthStatus.value = "Connected to ChatGPT (Account: ${session.accountId.take(12)}...)"
                fetchDynamicModels()
            }.onFailure { error ->
                isOpenAiAuthenticating.value = false
                openAiAuthStatus.value = "Authentication failed: ${error.message ?: "Unknown error"}"
            }
        }
    }

    fun refreshOpenAiSession(context: android.content.Context) {
        viewModelScope.launch {
            openAiAuthStatus.value = "Refreshing OpenAI session token..."
            val result = com.example.trinity.auth.OpenAIOAuthManager.refreshIfNeeded(context)
            result.onSuccess { refreshed ->
                openAiSession.value = refreshed
                openAiAuthStatus.value = "ChatGPT session ready."
            }.onFailure { err ->
                openAiAuthStatus.value = "Refresh failed: ${err.message}"
            }
        }
    }

    fun disconnectOpenAi(context: android.content.Context) {
        com.example.trinity.auth.OpenAIOAuthManager.clearSession(context)
        openAiSession.value = null
        openAiAuthStatus.value = "ChatGPT OAuth session disconnected."
    }

    fun selectChatGptModel(model: com.example.trinity.cloud.ChatGptModel) {
        selectedChatGptModel.value = model
        selectedReasoningEffort.value = model.defaultReasoning
    }

    fun executeHybridQuery(prompt: String = hybridInputPrompt.value) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || isHybridQuerying.value) return
        if (selectedChatGptModel.value.id.isBlank()) {
            openAiAuthStatus.value = "Select a model from the provider or enter a model ID first."
            return
        }

        val userMsg = com.example.trinity.cloud.HybridChatMessage(
            role = "user",
            content = trimmed,
            modelId = selectedChatGptModel.value.id
        )
        _hybridChatMessages.value = _hybridChatMessages.value + userMsg
        hybridInputPrompt.value = ""
        isHybridQuerying.value = true

        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                if (customApiKey.value.isBlank()) {
                    openAiSession.value = com.example.trinity.auth.OpenAIOAuthManager
                        .refreshIfNeeded(context).getOrThrow()
                }
                mcpBridge.startServer()
                isMcpServerRunning.value = true
                val response = hybridCloudBridge.executeHybridQuery(
                    userPrompt = trimmed,
                    selectedModel = selectedChatGptModel.value,
                    reasoningEffort = selectedReasoningEffort.value,
                    oauthSession = if (customApiKey.value.isBlank()) openAiSession.value else null,
                    refreshSession = { rejected ->
                        com.example.trinity.auth.OpenAIOAuthManager.refreshIfNeeded(
                            context, rejectedAccessToken = rejected).getOrThrow().also { openAiSession.value = it }
                    },
                    customApiKey = customApiKey.value.takeIf { it.isNotBlank() },
                    onToolCallExecuted = { event ->
                        lastToolCallEvent.value = event
                    }
                )
                _hybridChatMessages.value = _hybridChatMessages.value + response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _hybridChatMessages.value = _hybridChatMessages.value + com.example.trinity.cloud.HybridChatMessage(
                    role = "assistant",
                    content = "Error during ChatGPT Hybrid Bridge call: ${e.localizedMessage}",
                    modelId = selectedChatGptModel.value.id
                )
            } finally {
                isHybridQuerying.value = false
            }
        }
    }

    fun clearHybridChat() {
        _hybridChatMessages.value = emptyList()
        lastToolCallEvent.value = null
    }
}
