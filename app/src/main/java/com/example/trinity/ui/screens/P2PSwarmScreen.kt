package com.example.trinity.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.model.NodeRole
import com.example.trinity.p2p.PeerNode
import com.example.trinity.ui.TrinityViewModel
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

@Composable
fun P2PSwarmScreen(
    viewModel: TrinityViewModel,
    modifier: Modifier = Modifier
) {
    val peers by viewModel.connectedPeers.collectAsState()
    val logs by viewModel.swarmLogs.collectAsState()
    val currentRole by viewModel.currentNodeRole.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var peerName by remember { mutableStateOf("") }
    var peerAddress by remember { mutableStateOf("192.168.1.150") }
    var peerPort by remember { mutableStateOf("6881") }

    val trackerStats = viewModel.ragServer.trackerServer.getStats()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // Node Identity & Tracker Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = null,
                                tint = TrinityGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "P2P WIRE PROTOCOL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TrinityGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PORT :6881",
                                color = TrinityGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Local Node ID: ${viewModel.ragServer.peerManager.localPeerId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Node Swarm Role:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TrinityCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NodeRole.entries.forEach { role ->
                            FilterChip(
                                selected = currentRole == role,
                                onClick = { viewModel.setNodeRole(role) },
                                label = { Text(role.value.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TrinityCyan,
                                    selectedLabelColor = TrinityDeepNavy,
                                    containerColor = TrinityCardNavy,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.syncSwarm() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TrinityCyan,
                                contentColor = TrinityDeepNavy
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BROADCAST SYNC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TrinityCardNavy,
                                contentColor = TrinityCyan
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ADD PEER", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // --- TRACKER SERVER STATUS ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = null,
                                tint = TrinityElectricBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "TRACKER SERVER (:6969)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "STATUS: ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = TrinityGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Active Swarms", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                            Text("${trackerStats["total_swarms"] ?: 0}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TrinityCyan)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Registered Peers", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                            Text("${peers.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TrinityElectricBlue)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Announce Loop", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                            Text("30s", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TrinityAccentGold)
                        }
                    }
                }
            }
        }

        // --- EMBEDDED KADEMLIA DHT & BOOTSTRAP NODES (NO EXTERNAL SERVERS) ---
        item {
            val dht = viewModel.ragServer.dht
            val routingTable by dht.routingTableFlow.collectAsState()

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy),
                border = androidx.compose.foundation.BorderStroke(1.dp, TrinityGreen.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = null,
                                tint = TrinityGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EMBEDDED KADEMLIA DHT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TrinityGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ZERO EXTERNAL SERVER",
                                color = TrinityGreen,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "160-bit XOR Metric • Auto-discovers private mesh network without central HTTP website.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Local Node ID: ${dht.localNodeId.take(16)}... (160-bit SHA-1)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TrinityCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Hardcoded Bootstrap Nodes (${dht.bootstrapNodes.size}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = TrinityAccentGold,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dht.bootstrapNodes.forEach { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TrinityCardNavy)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(b.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("${b.address}:${b.port} • ${b.role.value.uppercase()}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(TrinityGreen.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("BOOTSTRAPPED", fontSize = 9.sp, color = TrinityGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { dht.bootstrap() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrinityGreen.copy(alpha = 0.2f),
                            contentColor = TrinityGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("REFRESH DHT ROUTING TABLE (${routingTable.size} NODES)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- DIRECT IN-MEMORY RAG MATRIX CARD ---
        item {
            val inMemoryCount = viewModel.ragServer.inMemoryRAGCache.size
            val totalVectors = viewModel.ragServer.vectorIndex.totalVectors

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy),
                border = androidx.compose.foundation.BorderStroke(1.dp, TrinityCyan.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = TrinityCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DIRECT IN-MEMORY RAG MATRIX",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TrinityCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "0ms DISK WAIT",
                                color = TrinityCyan,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Incoming torrent blocks push vector floats directly into the active FAISS search matrix in RAM. The AI searches newly arrived knowledge before disk write-behind commits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("RAM Live Chunks", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("$inMemoryCount", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TrinityCyan)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Active FAISS Vectors", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("$totalVectors", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TrinityElectricBlue)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Vector Dim", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("384-d", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TrinityGreen)
                        }
                    }
                }
            }
        }

        // --- CONNECTED PEERS ---
        item {
            Text(
                text = "CONNECTED SWARM PEERS (${peers.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TrinityCyan,
                letterSpacing = 1.sp
            )
        }

        items(peers) { peer ->
            PeerCard(peer)
        }

        // --- LIVE PROTOCOL LOGS TERMINAL ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinityDeepNavy),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SWARM WIRE TERMINAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = TrinityCyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(TrinityGreen)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (logs.isEmpty()) {
                        Text(
                            text = "Awaiting P2P socket events...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    } else {
                        logs.takeLast(10).forEach { logLine ->
                            Text(
                                text = logLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (logLine.contains("SYNC")) TrinityAccentGold else Color.White.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Swarm Peer", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = peerName,
                        onValueChange = { peerName = it },
                        label = { Text("Peer Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = peerAddress,
                        onValueChange = { peerAddress = it },
                        label = { Text("IP Address") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = peerPort,
                        onValueChange = { peerPort = it },
                        label = { Text("Port") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = peerPort.toIntOrNull() ?: 6881
                        val n = peerName.ifBlank { "Custom Peer" }
                        viewModel.addManualPeer(peerAddress, p, n)
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TrinityCyan, contentColor = TrinityDeepNavy)
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = TrinitySurfaceNavy
        )
    }
}

@Composable
private fun PeerCard(peer: PeerNode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (peer.isConnected) TrinityGreen else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TrinityElectricBlue.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = peer.role.value.uppercase(),
                        color = TrinityElectricBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Address: ${peer.address}:${peer.port}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "Peer ID: ${peer.peerId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}
