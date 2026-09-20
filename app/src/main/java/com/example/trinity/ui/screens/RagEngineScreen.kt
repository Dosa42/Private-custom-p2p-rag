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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.core.QueryResultItem
import com.example.trinity.model.KnowledgeChunk
import com.example.trinity.model.StorageTier
import com.example.trinity.ui.TrinityViewModel
import com.example.trinity.ui.components.PipelineVisualizerCard
import com.example.ui.theme.TierColdColor
import com.example.ui.theme.TierFrozenColor
import com.example.ui.theme.TierHotColor
import com.example.ui.theme.TierWarmColor
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RagEngineScreen(
    viewModel: TrinityViewModel,
    modifier: Modifier = Modifier
) {
    val inputContent by viewModel.inputContent.collectAsState()
    val inputSource by viewModel.inputSource.collectAsState()
    val g1 by viewModel.g1.collectAsState()
    val g2 by viewModel.g2.collectAsState()
    val g3 by viewModel.g3.collectAsState()
    val g4 by viewModel.g4.collectAsState()
    val liveKappa by viewModel.liveKappa.collectAsState()
    val liveTessa by viewModel.liveTessa.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val queryMinKappa by viewModel.queryMinKappa.collectAsState()
    val queryTierFilter by viewModel.queryTierFilter.collectAsState()
    val queryResults by viewModel.queryResults.collectAsState()
    val generatedLlmContext by viewModel.generatedLlmContext.collectAsState()

    val lastPipelineResult by viewModel.lastPipelineResult.collectAsState()
    val chunks by viewModel.knowledgeChunks.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // Interactive 5-Layer Pipeline Architecture visualizer
            PipelineVisualizerCard(lastResult = lastPipelineResult)
        }

        // --- INGESTION CARD ---
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
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = TrinityCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "KNOWLEDGE INGESTION",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "HTTP POST /ingest",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TrinityAccentGold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inputContent,
                        onValueChange = { viewModel.inputContent.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ingest_input_field"),
                        placeholder = { Text("Enter knowledge chunk to embed, index, chunk and distribute...") },
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TrinityCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedContainerColor = TrinityCardNavy,
                            unfocusedContainerColor = TrinityCardNavy,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Source Origin:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("guardian", "kral", "hub", "imperial").forEach { src ->
                            FilterChip(
                                selected = inputSource == src,
                                onClick = { viewModel.inputSource.value = src },
                                label = { Text(src.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TrinityCyan,
                                    selectedLabelColor = TrinityDeepNavy,
                                    containerColor = TrinityCardNavy,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // G-Vector Sliders for Kappa calculation
                    Text(
                        text = "Guardian g-vectors (κ = 0.35g₁ + 0.25g₂ + 0.28g₃ + 0.12g₄):",
                        style = MaterialTheme.typography.labelSmall,
                        color = TrinityCyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GVectorColumn("g₁ (0.35)", g1, Modifier.weight(1f)) { viewModel.updateGVector(1, it) }
                        GVectorColumn("g₂ (0.25)", g2, Modifier.weight(1f)) { viewModel.updateGVector(2, it) }
                        GVectorColumn("g₃ (0.28)", g3, Modifier.weight(1f)) { viewModel.updateGVector(3, it) }
                        GVectorColumn("g₄ (0.12)", g4, Modifier.weight(1f)) { viewModel.updateGVector(4, it) }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live TESSA status preview badge
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TrinityCardNavy)
                            .border(1.dp, TrinityCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "LIVE KAPPA: ${"%.3f".format(liveKappa)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TrinityAccentGold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "TESSA: ${liveTessa.name} (${liveTessa.permission})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                            TierBadge(tier = liveTessa.tier)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { viewModel.executeIngest() },
                        enabled = inputContent.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("ingest_execute_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrinityCyan,
                            contentColor = TrinityDeepNavy
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Bolt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("EXECUTE FULL INGESTION PIPELINE", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- DIRECT QUERY & SEMANTIC SEARCH ---
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
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = TrinityElectricBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SEMANTIC SEARCH & MVS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "POST /query",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TrinityElectricBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("query_input_field"),
                            placeholder = { Text("Query swarm memory...") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TrinityElectricBlue,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedContainerColor = TrinityCardNavy,
                                unfocusedContainerColor = TrinityCardNavy,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = { viewModel.executeSearch() },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("search_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TrinityElectricBlue,
                                contentColor = TrinityDeepNavy
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Tier filter chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = queryTierFilter == null,
                            onClick = { viewModel.queryTierFilter.value = null },
                            label = { Text("ALL TIERS", fontSize = 10.sp) }
                        )
                        StorageTier.entries.forEach { tier ->
                            FilterChip(
                                selected = queryTierFilter == tier,
                                onClick = { viewModel.queryTierFilter.value = if (queryTierFilter == tier) null else tier },
                                label = { Text(tier.value.uppercase(), fontSize = 10.sp) }
                            )
                        }
                    }

                    if (queryResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Found ${queryResults.size} matches",
                                style = MaterialTheme.typography.labelSmall,
                                color = TrinityCyan
                            )
                            Button(
                                onClick = { viewModel.generateLlmContext() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TrinityAccentGold,
                                    contentColor = TrinityDeepNavy
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("BUILD LLM CONTEXT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Generated LLM Context Box
                    if (generatedLlmContext != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TrinityDeepNavy)
                                .border(1.dp, TrinityAccentGold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "POST /context — Formatted for LLM Prompt:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TrinityAccentGold,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = generatedLlmContext ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Query Results items
                    queryResults.forEach { item ->
                        Spacer(modifier = Modifier.height(8.dp))
                        QueryResultRow(item)
                    }
                }
            }
        }

        // --- RECENT KNOWLEDGE CHUNKS ---
        item {
            Text(
                text = "SWARM KNOWLEDGE CHUNKS (${chunks.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TrinityCyan,
                letterSpacing = 1.sp
            )
        }

        items(chunks) { chunk ->
            ChunkItemCard(chunk)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GVectorColumn(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TrinityCardNavy)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "%.2f".format(value),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TrinityCyan
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.0f..1.0f,
            colors = SliderDefaults.colors(
                thumbColor = TrinityCyan,
                activeTrackColor = TrinityCyan,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
fun TierBadge(tier: StorageTier, modifier: Modifier = Modifier) {
    val (bg, label) = when (tier) {
        StorageTier.HOT -> TierHotColor to "HOT"
        StorageTier.WARM -> TierWarmColor to "WARM"
        StorageTier.COLD -> TierColdColor to "COLD"
        StorageTier.FROZEN -> TierFrozenColor to "FROZEN"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg.copy(alpha = 0.2f))
            .border(0.5.dp, bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = bg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun QueryResultRow(item: QueryResultItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TrinityCardNavy)
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierBadge(item.chunk.tier)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sim: ${"%.1f".format(item.similarityScore * 100)}%",
                        color = TrinityCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MVS: ${"%.2f".format(item.mvsScore)}",
                        color = TrinityAccentGold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "κ: ${"%.2f".format(item.chunk.kappa)}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.chunk.content,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ChunkItemCard(chunk: KnowledgeChunk) {
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
                    TierBadge(chunk.tier)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = chunk.source.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TrinityCyan,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "ID: ${chunk.chunkId.take(10)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = chunk.content,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TESSA: ${chunk.tessaName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TrinityAccentGold,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Access: ${chunk.accessCount} times",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
    }
}
