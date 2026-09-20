package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trinity.ui.TrinityTab
import com.example.trinity.ui.TrinityViewModel
import com.example.trinity.ui.screens.HybridCloudScreen
import com.example.trinity.ui.screens.P2PSwarmScreen
import com.example.trinity.ui.screens.RagEngineScreen
import com.example.trinity.ui.screens.StorageTiersScreen
import com.example.trinity.ui.screens.TorrentPiecesScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TrinityAccentGold
import com.example.ui.theme.TrinityCardNavy
import com.example.ui.theme.TrinityCyan
import com.example.ui.theme.TrinityDeepNavy
import com.example.ui.theme.TrinityElectricBlue
import com.example.ui.theme.TrinityGreen
import com.example.ui.theme.TrinitySurfaceNavy

class MainActivity : ComponentActivity() {

    private val viewModel: TrinityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TrinityApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrinityApp(viewModel: TrinityViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val chunks by viewModel.knowledgeChunks.collectAsState()
    val role by viewModel.currentNodeRole.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TrinityDeepNavy,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.trinity_logo),
                                contentDescription = "Trinity Logo",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "TRINITY CORE",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(TrinityCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "v3.0",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TrinityCyan,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Text(
                                    text = "RAG • Tiers • 256KB Swarm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Swarm node status pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(TrinityCardNavy)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(TrinityGreen)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = role.value.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TrinityCyan,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TrinitySurfaceNavy
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = TrinitySurfaceNavy,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == TrinityTab.PIPELINE_RAG,
                    onClick = { viewModel.setTab(TrinityTab.PIPELINE_RAG) },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "RAG Core") },
                    label = { Text("RAG Core", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TrinityDeepNavy,
                        selectedTextColor = TrinityCyan,
                        indicatorColor = TrinityCyan,
                        unselectedIconColor = Color.White.copy(alpha = 0.5f),
                        unselectedTextColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == TrinityTab.HYBRID_CLOUD,
                    onClick = { viewModel.setTab(TrinityTab.HYBRID_CLOUD) },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud AI") },
                    label = { Text("Cloud Bridge", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TrinityDeepNavy,
                        selectedTextColor = TrinityCyan,
                        indicatorColor = TrinityCyan,
                        unselectedIconColor = Color.White.copy(alpha = 0.5f),
                        unselectedTextColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == TrinityTab.STORAGE_TIERS,
                    onClick = { viewModel.setTab(TrinityTab.STORAGE_TIERS) },
                    icon = { Icon(Icons.Default.Storage, contentDescription = "Storage") },
                    label = { Text("Tiers", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TrinityDeepNavy,
                        selectedTextColor = TrinityElectricBlue,
                        indicatorColor = TrinityElectricBlue,
                        unselectedIconColor = Color.White.copy(alpha = 0.5f),
                        unselectedTextColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == TrinityTab.TORRENT_PIECES,
                    onClick = { viewModel.setTab(TrinityTab.TORRENT_PIECES) },
                    icon = { Icon(Icons.Default.Widgets, contentDescription = "Pieces") },
                    label = { Text("256KB Pieces", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TrinityDeepNavy,
                        selectedTextColor = TrinityAccentGold,
                        indicatorColor = TrinityAccentGold,
                        unselectedIconColor = Color.White.copy(alpha = 0.5f),
                        unselectedTextColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                NavigationBarItem(
                    selected = currentTab == TrinityTab.P2P_SWARM,
                    onClick = { viewModel.setTab(TrinityTab.P2P_SWARM) },
                    icon = { Icon(Icons.Default.WifiTethering, contentDescription = "Swarm") },
                    label = { Text("Swarm & Tracker", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TrinityDeepNavy,
                        selectedTextColor = TrinityGreen,
                        indicatorColor = TrinityGreen,
                        unselectedIconColor = Color.White.copy(alpha = 0.5f),
                        unselectedTextColor = Color.White.copy(alpha = 0.5f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                TrinityTab.PIPELINE_RAG -> RagEngineScreen(viewModel = viewModel)
                TrinityTab.HYBRID_CLOUD -> HybridCloudScreen(viewModel = viewModel)
                TrinityTab.STORAGE_TIERS -> StorageTiersScreen(viewModel = viewModel)
                TrinityTab.TORRENT_PIECES -> TorrentPiecesScreen(viewModel = viewModel)
                TrinityTab.P2P_SWARM -> P2PSwarmScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

