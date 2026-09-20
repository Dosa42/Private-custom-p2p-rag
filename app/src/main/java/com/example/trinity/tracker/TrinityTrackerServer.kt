package com.example.trinity.tracker

import com.example.trinity.model.AnnounceResponse
import com.example.trinity.model.ScrapeStats
import com.example.trinity.model.TrackedPeer
import java.util.concurrent.ConcurrentHashMap

class TrinityTrackerServer(
    val host: String = "127.0.0.1",
    val port: Int = 6969
) {

    // info_hash -> (peer_id -> TrackedPeer)
    private val swarms = ConcurrentHashMap<String, ConcurrentHashMap<String, TrackedPeer>>()
    private val downloadCounts = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun announce(
        infoHash: String,
        peerId: String,
        ip: String,
        port: Int,
        event: String = "started",
        uploaded: Long = 0L,
        downloaded: Long = 0L
    ): AnnounceResponse {
        val swarm = swarms.getOrPut(infoHash) { ConcurrentHashMap() }

        if (event == "stopped") {
            swarm.remove(peerId)
        } else {
            val peer = swarm.getOrPut(peerId) {
                TrackedPeer(
                    peerId = peerId,
                    ip = ip,
                    port = port,
                    event = event
                )
            }
            peer.lastAnnounce = System.currentTimeMillis()
            peer.uploaded = uploaded
            peer.downloaded = downloaded
            peer.event = event

            if (event == "completed") {
                downloadCounts[infoHash] = (downloadCounts[infoHash] ?: 0L) + 1L
            }
        }

        // Clean expired
        cleanupExpired()

        val activePeers = swarm.values.filter { !it.isExpired() }
        val otherPeers = activePeers.filter { it.peerId != peerId }.map { p ->
            mapOf<String, Any>(
                "peer_id" to p.peerId,
                "ip" to p.ip,
                "port" to p.port
            )
        }

        val complete = activePeers.count { it.event == "completed" }
        val incomplete = activePeers.size - complete

        return AnnounceResponse(
            interval = 30,
            peers = otherPeers,
            complete = complete,
            incomplete = incomplete
        )
    }

    @Synchronized
    fun scrape(infoHash: String): ScrapeStats {
        val swarm = swarms[infoHash] ?: return ScrapeStats(infoHash, 0, 0, 0L)
        val active = swarm.values.filter { !it.isExpired() }
        val complete = active.count { it.event == "completed" }
        val incomplete = active.size - complete
        val downloaded = downloadCounts[infoHash] ?: 0L
        return ScrapeStats(infoHash, complete, incomplete, downloaded)
    }

    @Synchronized
    fun getAllSwarms(): Map<String, List<TrackedPeer>> {
        cleanupExpired()
        return swarms.mapValues { it.value.values.toList() }
    }

    @Synchronized
    fun cleanupExpired() {
        for ((_, swarm) in swarms) {
            val toRemove = swarm.filter { it.value.isExpired() }.keys
            for (k in toRemove) {
                swarm.remove(k)
            }
        }
    }

    fun getStats(): Map<String, Any> {
        val totalPeers = swarms.values.sumOf { it.size }
        return mapOf(
            "total_swarms" to swarms.size,
            "total_peers" to totalPeers,
            "download_counts" to downloadCounts
        )
    }
}
