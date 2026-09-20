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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.model.StorageTier
import com.example.trinity.model.StoredObject
import com.example.trinity.ui.TrinityViewModel
import com.example.ui.theme.TierColdColor
import com.example.ui.theme.TierFrozenColor
import com.example.ui.theme.TierHotColor
import com.example.ui.theme.TierWarmColor
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinitySurfaceNavy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StorageTiersScreen(
    viewModel: TrinityViewModel,
    modifier: Modifier = Modifier
) {
    val storedObjects by viewModel.storedObjects.collectAsState()
    val quota = viewModel.ragServer.storageManager.getQuota()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // Quota Card
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
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = TrinityCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "STORAGE QUOTA & HEALTH",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "QUOTA 10 GB",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TrinityAccentGold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val usedPercent = (quota.usedBytes.toFloat() / quota.maxBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { usedPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = TrinityCyan,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Used: ${quota.usedBytes} Bytes (${"%.2f".format(usedPercent * 100)}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Files: ${quota.usedFiles} / ${quota.maxFiles}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // --- 4 TIERS BREAKDOWN GRID ---
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierInfoCard(
                        tierName = "HOT TIER",
                        policy = "< 15 min • Uncompressed SSD",
                        count = storedObjects.count { it.tier == StorageTier.HOT },
                        accentColor = TierHotColor,
                        icon = Icons.Default.Memory,
                        modifier = Modifier.weight(1f)
                    )
                    TierInfoCard(
                        tierName = "WARM TIER",
                        policy = "< 2 hours • Fast Cache",
                        count = storedObjects.count { it.tier == StorageTier.WARM },
                        accentColor = TierWarmColor,
                        icon = Icons.Default.Storage,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierInfoCard(
                        tierName = "COLD TIER",
                        policy = "< 7 days • ZLIB Compressed",
                        count = storedObjects.count { it.tier == StorageTier.COLD },
                        accentColor = TierColdColor,
                        icon = Icons.Default.FolderZip,
                        modifier = Modifier.weight(1f)
                    )
                    TierInfoCard(
                        tierName = "FROZEN TIER",
                        policy = "> 7 days • Encrypted Archive",
                        count = storedObjects.count { it.tier == StorageTier.FROZEN },
                        accentColor = TierFrozenColor,
                        icon = Icons.Default.Lock,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STORED OBJECTS ON DISK (${storedObjects.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TrinityCyan,
                    letterSpacing = 1.sp
                )
                IconButton(onClick = { viewModel.refreshStorageData() }) {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = "Refresh", tint = TrinityCyan)
                }
            }
        }

        if (storedObjects.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TrinitySurfaceNavy)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No stored physical files. Ingest a chunk in RAG Core to trigger physical storage!",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        items(storedObjects) { obj ->
            StoredObjectRow(
                obj = obj,
                onMoveTier = { newTier -> viewModel.moveStorageTier(obj.id, newTier) },
                onDelete = { viewModel.deleteStorageObject(obj.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TierInfoCard(
    tierName: String,
    policy: String,
    count: Int,
    accentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tierName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$count objects",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = policy,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun StoredObjectRow(
    obj: StoredObject,
    onMoveTier: (StorageTier) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

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
                    TierBadge(obj.tier)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (obj.compressed) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TrinityElectricBlue.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ZLIB",
                                color = TrinityElectricBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "${obj.sizeBytes} B",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box {
                    Button(
                        onClick = { showMenu = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrinityCardNavy,
                            contentColor = TrinityCyan
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("MOVE TIER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(TrinityDeepNavy)
                    ) {
                        StorageTier.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("Move to ${t.value.uppercase()}", color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    showMenu = false
                                    onMoveTier(t)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "File: ${obj.path.takeLast(40)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Hash: ${obj.contentHash.take(24)}...",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(obj.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
