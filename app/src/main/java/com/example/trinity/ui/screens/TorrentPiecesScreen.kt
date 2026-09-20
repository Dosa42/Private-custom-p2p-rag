package com.example.trinity.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.model.TorrentMeta
import com.example.trinity.ui.TrinityViewModel
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentPiecesScreen(
    viewModel: TrinityViewModel,
    modifier: Modifier = Modifier
) {
    val torrents by viewModel.torrents.collectAsState()
    val selectedHash by viewModel.selectedTorrentHash.collectAsState()
    val reassembledContent by viewModel.reassembledContent.collectAsState()

    val cacheStats = viewModel.ragServer.torrentCache.getStats()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // 256KB Cache Overview Card
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
                                imageVector = Icons.Default.Widgets,
                                contentDescription = null,
                                tint = TrinityAccentGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "256 KB PIECE CACHE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "SHA-256 HASHED",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TrinityCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatTile("Torrents", "${cacheStats["total_torrents"] ?: 0}", Modifier.weight(1f))
                        StatTile("Complete", "${cacheStats["complete_torrents"] ?: 0}", Modifier.weight(1f))
                        StatTile("Pieces", "${cacheStats["available_pieces"] ?: 0}", Modifier.weight(1f))
                    }
                }
            }
        }

        // --- VECTOR QUANTIZATION & SQ8 COMPRESSION CARD ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy),
                border = androidx.compose.foundation.BorderStroke(1.dp, TrinityCyan.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TrinityCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SQ8", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TrinityCyan, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "VECTOR QUANTIZATION (SQ8)",
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
                                text = "-75% BYTES",
                                color = TrinityGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Converts 384-dimensional float32 embeddings (4 B/elem) into signed int8 (1 B/elem) before payload chunking to prevent P2P torrent swarm congestion.",
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
                            Text("Raw Float32", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("1,536 B", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Quantized Int8", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("393 B", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TrinityGreen)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TrinityCardNavy)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Cosine Fidelity", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace)
                            Text("> 99.5%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TrinityAccentGold)
                        }
                    }
                }
            }
        }

        // --- SELECTED TORRENT INSPECTOR ---
        val activeTorrent = torrents.find { it.infoHash == selectedHash } ?: torrents.firstOrNull()

        if (activeTorrent != null) {
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
                            Text(
                                text = "BITFIELD & PIECES INSPECTOR",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TrinityCyan,
                                letterSpacing = 1.sp
                            )
                            val isComplete = viewModel.ragServer.torrentCache.isComplete(activeTorrent.infoHash)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isComplete) TrinityGreen.copy(alpha = 0.2f) else TrinityAccentGold.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isComplete) "100% COMPLETE" else "DOWNLOADING",
                                    color = if (isComplete) TrinityGreen else TrinityAccentGold,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "InfoHash: ${activeTorrent.infoHash}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Chunk ID: ${activeTorrent.chunkId} • Total Size: ${activeTorrent.totalSize} B",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )

                        val savings = activeTorrent.metadata["vector_bandwidth_savings"] ?: "74.5%"
                        val fidelity = activeTorrent.metadata["vector_cosine_fidelity"] ?: "0.998"
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TrinityCyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "SQ8 INT8: 1536 B → 393 B ($savings saved • Fidelity $fidelity)",
                                    color = TrinityCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Bitfield Matrix (256 KB Slices):",
                            style = MaterialTheme.typography.labelSmall,
                            color = TrinityElectricBlue,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val bitfield = viewModel.ragServer.torrentCache.getBitfield(activeTorrent.infoHash)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            bitfield.forEachIndexed { idx, hasPiece ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (hasPiece) TrinityGreen else TrinityCardNavy)
                                        .border(
                                            0.5.dp,
                                            if (hasPiece) TrinityGreen else Color.White.copy(alpha = 0.2f),
                                            RoundedCornerShape(4.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (hasPiece) TrinityDeepNavy else Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.selectTorrent(activeTorrent.infoHash) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TrinityCyan,
                                    contentColor = TrinityDeepNavy
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("VERIFY SHA-256", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.simulatePieceExchange(activeTorrent.infoHash) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TrinityElectricBlue,
                                    contentColor = TrinityDeepNavy
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SWARM SYNC", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (reassembledContent != null && selectedHash == activeTorrent.infoHash) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TrinityDeepNavy)
                                    .border(1.dp, TrinityGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "✓ Reassembled Payload from Pieces:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TrinityGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = reassembledContent ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- ALL TORRENTS LIST ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ALL KNOWLEDGE TORRENTS (${torrents.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TrinityCyan,
                    letterSpacing = 1.sp
                )
                IconButton(onClick = { viewModel.refreshTorrents() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = TrinityCyan)
                }
            }
        }

        items(torrents) { t ->
            TorrentMetaCard(
                meta = t,
                isSelected = t.infoHash == selectedHash,
                onClick = { viewModel.selectTorrent(t.infoHash) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TrinityCardNavy)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TrinityCyan)
    }
}

@Composable
private fun TorrentMetaCard(
    meta: TorrentMeta,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) TrinityCardNavy else TrinitySurfaceNavy
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, TrinityCyan) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HASH: ${meta.infoHash.take(14)}...",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TrinityAccentGold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${meta.pieceIds.size} pieces • ${meta.totalSize} B",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Chunk ID: ${meta.chunkId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
