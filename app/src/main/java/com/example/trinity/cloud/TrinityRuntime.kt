package com.example.trinity.cloud

import android.content.Context
import com.example.trinity.core.TrinityRAGServer

/** One engine shared by the activity and the foreground device connection. */
class TrinityRuntime private constructor(context: Context) {
    val connectionStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val ragServer = TrinityRAGServer(context.applicationContext)
    val mcpBridge = TrinityMCPBridge(ragServer)
    val gateway = TrinityGatewayClient(ragServer, mcpBridge)

    companion object {
        @Volatile private var instance: TrinityRuntime? = null
        fun get(context: Context): TrinityRuntime = instance ?: synchronized(this) {
            instance ?: TrinityRuntime(context).also { instance = it }
        }
    }
}
