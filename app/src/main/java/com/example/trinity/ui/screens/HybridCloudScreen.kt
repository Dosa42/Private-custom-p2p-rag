package com.example.trinity.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.cloud.CloudProvider
import com.example.trinity.cloud.HybridChatMessage
import com.example.trinity.cloud.ToolCallEvent
import com.example.trinity.ui.TrinityViewModel
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

@Composable
fun HybridCloudScreen(
    viewModel: TrinityViewModel,
    modifier: Modifier = Modifier
) {
    val selectedProvider by viewModel.selectedCloudProvider.collectAsState()
    val apiKey by viewModel.cloudApiKey.collectAsState()
    val chatMessages by viewModel.hybridChatMessages.collectAsState()
    val isQuerying by viewModel.isHybridQuerying.collectAsState()
    val lastToolEvent by viewModel.lastToolCallEvent.collectAsState()
    val promptText by viewModel.hybridInputPrompt.collectAsState()

    var showSystemPrompt by remember { mutableStateOf(false) }
    var showApiKeyField by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // --- ARCHITECTURE BANNER ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TrinitySurfaceNavy),
                    border = BorderStroke(1.dp, TrinityCyan.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = TrinityCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "HYBRID CLOUD AI RAG",
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
                                    text = "FUNCTION CALLING",
                                    color = TrinityCyan,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Cloud AI acts as Central Cognitive Thinker. Local framework acts as sovereign on-demand data provider via P2P torrent chunks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Active Cloud Reasoner:",
                            fontSize = 10.sp,
                            color = TrinityAccentGold,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CloudProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = selectedProvider == provider,
                                    onClick = { viewModel.selectedCloudProvider.value = provider },
                                    label = { Text(provider.displayName, fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = TrinityCyan,
                                        selectedLabelColor = TrinityDeepNavy,
                                        containerColor = TrinityCardNavy,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { showApiKeyField = !showApiKeyField }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = "API Key",
                                        tint = if (apiKey.isNotBlank()) TrinityGreen else Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = if (apiKey.isNotBlank()) "Custom API Key Active" else "Default AI Studio Key / Emulated Bridge",
                                    fontSize = 10.sp,
                                    color = if (apiKey.isNotBlank()) TrinityGreen else Color.White.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            IconButton(onClick = { showSystemPrompt = !showSystemPrompt }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = if (showSystemPrompt) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle System Prompt",
                                    tint = TrinityCyan
                                )
                            }
                        }

                        AnimatedVisibility(visible = showApiKeyField) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { viewModel.cloudApiKey.value = it },
                                    label = { Text("Cloud API Key (Optional)", fontSize = 11.sp) },
                                    placeholder = { Text("AIzaSy... or sk-...", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TrinityCyan,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    singleLine = true
                                )
                            }
                        }

                        AnimatedVisibility(visible = showSystemPrompt) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TrinityDeepNavy)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "MANDATORY CLOUD SYSTEM PROMPT:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TrinityAccentGold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = viewModel.hybridCloudBridge.systemPrompt,
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            // --- LAST TOOL CALL TELEMETRY ---
            if (lastToolEvent != null) {
                item {
                    ToolCallCard(lastToolEvent!!)
                }
            }

            // --- CHAT MESSAGES STREAM ---
            items(chatMessages) { message ->
                HybridMessageCard(message)
            }

            if (isQuerying) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = TrinityCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cloud AI querying local RAM & P2P torrent cache...",
                            fontSize = 11.sp,
                            color = TrinityCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Quick question chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { viewModel.executeHybridQuery("What is SDCK and how are chunks classified?") },
                colors = ButtonDefaults.buttonColors(containerColor = TrinityCardNavy, contentColor = TrinityCyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("SDCK Status", fontSize = 10.sp)
            }
            Button(
                onClick = { viewModel.executeHybridQuery("Explain how 256KB torrent pieces are cached in RAM") },
                colors = ButtonDefaults.buttonColors(containerColor = TrinityCardNavy, contentColor = TrinityElectricBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Torrent Cache", fontSize = 10.sp)
            }
            Button(
                onClick = { viewModel.clearHybridChat() },
                colors = ButtonDefaults.buttonColors(containerColor = TrinityCardNavy, contentColor = Color.White.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Clear", fontSize = 10.sp)
            }
        }

        // Bottom Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = promptText,
                onValueChange = { viewModel.hybridInputPrompt.value = it },
                placeholder = { Text("Ask Cloud AI via local P2P RAG bridge...", fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("hybrid_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TrinityCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { viewModel.executeHybridQuery() },
                enabled = promptText.isNotBlank() && !isQuerying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TrinityCyan,
                    contentColor = TrinityDeepNavy,
                    disabledContainerColor = TrinityCardNavy
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .size(52.dp)
                    .testTag("hybrid_send_button")
            ) {
                Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ToolCallCard(event: ToolCallEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TrinityDeepNavy),
        border = BorderStroke(1.dp, TrinityGreen.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        tint = TrinityGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FUNCTION CALL: ${event.toolName}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TrinityGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TrinityGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${event.executionLatencyMs}ms (RAM)",
                        fontSize = 9.sp,
                        color = TrinityGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Args: ${event.arguments}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Retrieved: ${event.chunksReturnedCount} chunks",
                    fontSize = 10.sp,
                    color = TrinityCyan,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Avg κ: ${"%.3f".format(event.averageKappa)}",
                    fontSize = 10.sp,
                    color = TrinityAccentGold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "SHA-256 Verified ✓",
                    fontSize = 10.sp,
                    color = TrinityGreen,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun HybridMessageCard(message: HybridChatMessage) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.98f),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 14.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) TrinityCardNavy else TrinitySurfaceNavy
            ),
            border = if (!isUser) BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)) else null
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.provider.displayName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TrinityCyan,
                            fontFamily = FontFamily.Monospace
                        )
                        if (message.isGrounded) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TrinityGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "GROUNDED IN SWARM",
                                    fontSize = 8.sp,
                                    color = TrinityGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = message.content,
                    fontSize = 12.sp,
                    color = Color.White,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
