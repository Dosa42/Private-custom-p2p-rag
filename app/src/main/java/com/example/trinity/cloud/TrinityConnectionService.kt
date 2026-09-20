package com.example.trinity.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the explicitly enabled gateway/peer connection alive while ChatGPT is in front. */
class TrinityConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime by lazy { TrinityRuntime.get(this) }
    private val lifecycleLock = Any()
    private var destroyed = false
    private var configurationGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Trinity device connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TrinityConnectionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Trinity device connection")
            .setContentText("Gateway and peer connection enabled. Open Trinity for live status.")
            .setContentIntent(openApp).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Disconnect", stop).build()
        ServiceCompat.startForeground(this, 1456, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        scope.launch {
            runtime.gateway.principal.collect { principal ->
                if (principal != null) {
                    synchronized(lifecycleLock) {
                        if (!destroyed && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("connection_enabled", false)
                            && principal == runtime.gateway.principal.value && principal == runtime.ragServer.currentOwnerId()) {
                            try { configurePeers(principal) }
                            catch (error: Exception) { runtime.connectionStatus.value = "Peer connection: ${error.message}" }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            synchronized(lifecycleLock) {
                configurationGeneration++
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("connection_enabled", false).apply()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("connection_enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val generation = synchronized(lifecycleLock) {
            if (destroyed) return START_NOT_STICKY
            ++configurationGeneration
        }
        scope.launch {
            try {
                runtime.mcpBridge.startServer()
                synchronized(lifecycleLock) {
                    if (!destroyed && generation == configurationGeneration && prefs.getBoolean("connection_enabled", false)) {
                        configurePeers(runtime.ragServer.currentOwnerId())
                        val url = prefs.getString("public_url", "").orEmpty()
                        val token = prefs.getString("device_token", "").orEmpty()
                        if (url.isNotBlank() && token.isNotBlank()) runtime.gateway.connect(url, token)
                        runtime.connectionStatus.value = "Connection service running."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lifecycleLock) {
                    if (!destroyed && generation == configurationGeneration) {
                        prefs.edit().putString("connection_error", error.message ?: error.javaClass.simpleName).apply()
                        runtime.connectionStatus.value = "Connection failed: ${error.message}"
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun configurePeers(owner: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val secret = prefs.getString("swarm_key", "").orEmpty()
        if (secret.isBlank()) return
        runtime.ragServer.peerManager.configureNetwork(secret, owner)
        val saved = org.json.JSONArray(prefs.getString("peers", "[]"))
        for (index in 0 until saved.length()) {
            val peer = saved.getJSONObject(index)
            val address = peer.getString("address")
            val port = peer.getInt("port")
            if (runtime.ragServer.peerManager.connectedPeers.value.none { it.address == address && it.port == port }) {
                runtime.ragServer.peerManager.addManualPeer(address, port, peer.getString("name"))
            }
        }
    }

    override fun onDestroy() {
        synchronized(lifecycleLock) {
            destroyed = true
            configurationGeneration++
            scope.cancel()
            runtime.gateway.disconnect()
            runtime.ragServer.peerManager.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "trinity_connection"
        private const val CHANNEL = "trinity_connection"
        private const val STOP = "com.example.trinity.DISCONNECT"
    }
}
