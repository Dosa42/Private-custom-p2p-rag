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

enum class TrinityTab(val title: String, val subtitle: String) {
    PIPELINE_RAG("RAG Core", "Ingest & Vector Query"),
    HYBRID_CLOUD("Cloud AI Bridge", "Gemini / GPT-4o / Claude"),
    STORAGE_TIERS("Storage Tiers", "HOT/WARM/COLD/FROZEN"),
    TORRENT_PIECES("256KB Pieces", "BitTorrent Chunk Cache"),
    P2P_SWARM("Swarm & Tracker", "P2P Protocol Stack")
}

class TrinityViewModel(application: Application) : AndroidViewModel(application) {

    val ragServer = TrinityRAGServer(application)

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
            ragServer.storageManager.delete(objId)
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
        ragServer.peerManager.syncWithPeers()
    }

    fun simulatePieceExchange(infoHash: String) {
        ragServer.peerManager.simulatePieceExchange(infoHash) {
            refreshTorrents()
            selectTorrent(infoHash)
        }
    }

    fun addManualPeer(address: String, port: Int, name: String) {
        ragServer.peerManager.addManualPeer(address, port, name)
    }

    // --- HYBRID RAG CLOUD AI BRIDGE STATE ---
    val hybridCloudBridge = com.example.trinity.cloud.HybridCloudBridge(ragServer)
    val selectedCloudProvider = MutableStateFlow(com.example.trinity.cloud.CloudProvider.GEMINI)
    val cloudApiKey = MutableStateFlow("")
    val hybridInputPrompt = MutableStateFlow("")
    val isHybridQuerying = MutableStateFlow(false)
    val lastToolCallEvent = MutableStateFlow<com.example.trinity.cloud.ToolCallEvent?>(null)

    private val _hybridChatMessages = MutableStateFlow<List<com.example.trinity.cloud.HybridChatMessage>>(
        listOf(
            com.example.trinity.cloud.HybridChatMessage(
                role = "assistant",
                content = "Trinity Hybrid Cloud AI connected. I act as the central cognitive reasoner (Gemini / GPT-4o / Claude). I have zero local storage and will invoke your local 'trinity_query' tool on-demand to retrieve verified 256KB SHA-256 chunks from your private P2P swarm.",
                provider = com.example.trinity.cloud.CloudProvider.GEMINI,
                isGrounded = true
            )
        )
    )
    val hybridChatMessages: StateFlow<List<com.example.trinity.cloud.HybridChatMessage>> = _hybridChatMessages.asStateFlow()

    fun executeHybridQuery(prompt: String = hybridInputPrompt.value) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || isHybridQuerying.value) return

        val userMsg = com.example.trinity.cloud.HybridChatMessage(
            role = "user",
            content = trimmed,
            provider = selectedCloudProvider.value
        )
        _hybridChatMessages.value = _hybridChatMessages.value + userMsg
        hybridInputPrompt.value = ""
        isHybridQuerying.value = true

        viewModelScope.launch {
            try {
                val response = hybridCloudBridge.executeHybridQuery(
                    userPrompt = trimmed,
                    provider = selectedCloudProvider.value,
                    customApiKey = cloudApiKey.value.takeIf { it.isNotBlank() },
                    onToolCallExecuted = { event ->
                        lastToolCallEvent.value = event
                    }
                )
                _hybridChatMessages.value = _hybridChatMessages.value + response
            } catch (e: Exception) {
                _hybridChatMessages.value = _hybridChatMessages.value + com.example.trinity.cloud.HybridChatMessage(
                    role = "assistant",
                    content = "Error during Hybrid Cloud Bridge call: ${e.localizedMessage}",
                    provider = selectedCloudProvider.value
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
