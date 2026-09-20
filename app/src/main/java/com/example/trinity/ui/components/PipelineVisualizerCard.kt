package com.example.trinity.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.core.IngestPipelineResult
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

@Composable
fun PipelineVisualizerCard(
    lastResult: IngestPipelineResult?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(TrinityCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TRINITY PIPELINE ARCHITECTURE",
                        style = MaterialTheme.typography.labelLarge,
                        color = TrinityCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
                Text(
                    text = "5-LAYER PIPELINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TrinityAccentGold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Step 1: User / AI Agent
            PipelineNode(
                stepNumber = "1",
                fileTag = "Bestand 6: trinity_core.py",
                title = "Kappa Score & TESSA Status",
                subtitle = "Calculates κ = 0.35g₁ + 0.25g₂ + 0.28g₃ + 0.12g₄ & maps to TESSA rules",
                icon = Icons.Default.Memory,
                accentColor = TrinityCyan,
                isActive = lastResult != null,
                activeInfo = lastResult?.let { "κ=${"%.3f".format(it.kappa)} • ${it.tessaName}" }
            )

            PipelineConnector()

            // Step 2: Physical Storage Tiers
            PipelineNode(
                stepNumber = "2",
                fileTag = "Bestand 4 & 5: trinity_storage.py",
                title = "Tiered Storage (HOT/WARM/COLD)",
                subtitle = "Stores data physically in directory tiers with ZLIB compression",
                icon = Icons.Default.Storage,
                accentColor = TrinityElectricBlue,
                isActive = lastResult != null,
                activeInfo = lastResult?.let { "Tier: ${it.tier.value.uppercase()} • Path: ${it.storagePath.takeLast(25)}" }
            )

            PipelineConnector()

            // Step 3: Torrent Piece Cache
            PipelineNode(
                stepNumber = "3",
                fileTag = "Bestand 1: torrent_cache.py",
                title = "256 KB Piece Cache & SQ8 Quantization",
                subtitle = "SQ8 converts float32 to int8 (75% bandwidth reduction: 1536 B → 393 B) before slicing",
                icon = Icons.Default.Widgets,
                accentColor = TrinityAccentGold,
                isActive = lastResult != null,
                activeInfo = lastResult?.let { "${it.pieceCount} piece(s) • SQ8 Int8: ${it.vectorSavingsPercent} network saved" }
            )

            PipelineConnector()

            // Step 4: Embedded DHT & Bootstrap Nodes
            PipelineNode(
                stepNumber = "4",
                fileTag = "Bestand 7: p2p_protocol.py & Kademlia DHT",
                title = "Embedded DHT & Bootstrap Nodes",
                subtitle = "Local XOR routing index populated by authenticated peers",
                icon = Icons.Default.SwapHoriz,
                accentColor = TrinityGreen,
                isActive = lastResult != null,
                activeInfo = lastResult?.let { "Swarm discovered via DHT bootstrap nodes • InfoHash: ${it.infoHash.take(12)}..." }
            )

            PipelineConnector()

            // Step 5: Direct In-Memory RAG Matrix
            PipelineNode(
                stepNumber = "5",
                fileTag = "Bestand 1, 4 & 6: In-Memory RAG Engine",
                title = "Local Vector Index",
                subtitle = "Verified peer knowledge is persisted and added to the local query index",
                icon = Icons.Default.Memory,
                accentColor = TrinityCyan,
                isActive = lastResult != null,
                activeInfo = lastResult?.let { "Indexed local and verified peer knowledge" }
            )
        }
    }
}

@Composable
private fun PipelineNode(
    stepNumber: String,
    fileTag: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    activeInfo: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TrinityCardNavy)
            .border(
                width = if (isActive && activeInfo != null) 1.dp else 0.5.dp,
                color = if (isActive && activeInfo != null) accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fileTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )

            if (activeInfo != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = TrinityGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = activeInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = TrinityGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineConnector() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = TrinityCyan.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp)
        )
    }
}
