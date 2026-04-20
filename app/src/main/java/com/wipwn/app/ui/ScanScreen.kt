package com.wipwn.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wipwn.app.data.ChannelInterferenceMap
import com.wipwn.app.data.NetworkInsight
import com.wipwn.app.data.RogueSeverity
import com.wipwn.app.data.ScanSecurityAnalysis
import com.wipwn.app.data.SignalLevel
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanContent(
    networks: List<WifiNetwork>,
    securityAnalysis: ScanSecurityAnalysis,
    isScanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
    onSelectNetwork: (WifiNetwork) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showWpsOnly by remember { mutableStateOf(false) }

    val filteredNetworks = remember(networks, searchQuery, showWpsOnly) {
        val query = searchQuery.trim()
        networks.filter { network ->
            val matchesWps = !showWpsOnly || network.wpsEnabled
            val matchesQuery = query.isBlank() ||
                network.displayName.contains(query, ignoreCase = true) ||
                network.bssid.contains(query, ignoreCase = true)
            matchesWps && matchesQuery
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Scan Jaringan", fontWeight = FontWeight.Bold)
                        if (networks.isNotEmpty() || searchQuery.isNotBlank() || showWpsOnly) {
                            Text(
                                "${filteredNetworks.size} dari ${networks.size} jaringan",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariantDark
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = OnSurfaceDark,
                    navigationIconContentColor = OnSurfaceDark
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!isScanning) onScan() },
                containerColor = GreenAccent,
                contentColor = Color.Black,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Sensors, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isScanning) "Scanning..." else "Scan",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { padding ->
        if (networks.isEmpty() && !isScanning) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )

                    Icon(
                        Icons.Filled.WifiFind,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = OnSurfaceVariantDark.copy(alpha = pulseAlpha)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Belum ada jaringan",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurfaceVariantDark
                    )
                    Text(
                        "Tekan tombol Scan untuk memulai",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariantDark.copy(alpha = 0.6f)
                    )

                    if (scanError != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            scanError,
                            style = MaterialTheme.typography.bodySmall,
                            color = Red400
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isScanning) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = GreenAccent,
                            trackColor = GreenAccent.copy(alpha = 0.15f)
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("Cari SSID / BSSID") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Hapus")
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GreenAccent,
                            focusedLabelColor = GreenAccent,
                            cursorColor = GreenAccent
                        )
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = showWpsOnly,
                            onClick = { showWpsOnly = !showWpsOnly },
                            label = { Text("WPS only") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        AssistChip(
                            onClick = {
                                searchQuery = ""
                                showWpsOnly = false
                            },
                            label = { Text("Reset filter") },
                            enabled = searchQuery.isNotBlank() || showWpsOnly
                        )
                    }
                }

                if (securityAnalysis.rogueAlerts.isNotEmpty()) {
                    item {
                        RogueAlertCard(
                            alerts = securityAnalysis.rogueAlerts
                        )
                    }
                }

                if (securityAnalysis.map2Ghz.usage.isNotEmpty() || securityAnalysis.map5Ghz.usage.isNotEmpty()) {
                    item {
                        ChannelMapCard(
                            map2Ghz = securityAnalysis.map2Ghz,
                            map5Ghz = securityAnalysis.map5Ghz
                        )
                    }
                }

                // WPS-enabled networks first
                val wpsNetworks = filteredNetworks.filter { it.wpsEnabled }
                val otherNetworks = filteredNetworks.filter { !it.wpsEnabled }

                if (filteredNetworks.isEmpty() && !isScanning) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.FilterAltOff,
                                    contentDescription = null,
                                    tint = OnSurfaceVariantDark
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Gak ada jaringan yang cocok sama filter lo",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurfaceVariantDark
                                )
                            }
                        }
                    }
                }

                if (wpsNetworks.isNotEmpty()) {
                    item {
                        Text(
                            "WPS Aktif (${wpsNetworks.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = GreenAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(wpsNetworks, key = { it.bssid }) { network ->
                        val insight = securityAnalysis.insightsByBssid[network.bssid]
                        NetworkCard(
                            network = network,
                            insight = insight,
                            onClick = { onSelectNetwork(network) }
                        )
                    }
                }

                if (otherNetworks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Lainnya (${otherNetworks.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = OnSurfaceVariantDark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(otherNetworks, key = { it.bssid }) { network ->
                        val insight = securityAnalysis.insightsByBssid[network.bssid]
                        NetworkCard(
                            network = network,
                            insight = insight,
                            onClick = { onSelectNetwork(network) }
                        )
                    }
                }

                // Bottom padding for FAB
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun NetworkCard(
    network: WifiNetwork,
    insight: NetworkInsight?,
    onClick: () -> Unit
) {
    val statusColor = when {
        network.wpsEnabled -> StatusVulnerable
        network.wpsLocked -> StatusLocked
        else -> StatusUnknown
    }

    val signalColor = when (network.signalLevel) {
        SignalLevel.EXCELLENT -> SignalExcellent
        SignalLevel.GOOD -> SignalGood
        SignalLevel.FAIR -> SignalFair
        SignalLevel.WEAK -> SignalWeak
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceCardDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (network.signalLevel) {
                        SignalLevel.EXCELLENT -> Icons.Filled.NetworkWifi
                        SignalLevel.GOOD -> Icons.Filled.NetworkWifi3Bar
                        SignalLevel.FAIR -> Icons.Filled.NetworkWifi2Bar
                        SignalLevel.WEAK -> Icons.Filled.NetworkWifi1Bar
                    },
                    contentDescription = null,
                    tint = signalColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = network.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${network.bssid}  •  CH ${network.channel}  •  ${network.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
                if (insight?.fingerprint != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${insight.fingerprint.vendor} • OUI ${insight.fingerprint.oui}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Cyan400
                    )
                }

                if (network.wpsEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(GreenAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "WPS",
                            style = MaterialTheme.typography.labelMedium,
                            color = GreenAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!insight?.anomalyTags.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = insight?.anomalyTags?.joinToString("  •  ").orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Orange400
                    )
                }
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = OnSurfaceVariantDark.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun RogueAlertCard(alerts: List<com.wipwn.app.data.RogueAlert>) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Rogue AP Detection (${alerts.size})",
                style = MaterialTheme.typography.titleSmall,
                color = Red400,
                fontWeight = FontWeight.Bold
            )
            alerts.take(3).forEach { alert ->
                val severityColor = when (alert.severity) {
                    RogueSeverity.HIGH -> Red400
                    RogueSeverity.MEDIUM -> Orange400
                    RogueSeverity.LOW -> Yellow400
                }
                Text(
                    text = "[${alert.severity}] ${alert.ssid}: ${alert.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = severityColor
                )
            }
        }
    }
}

@Composable
private fun ChannelMapCard(
    map2Ghz: ChannelInterferenceMap,
    map5Ghz: ChannelInterferenceMap
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Channel Interference Map",
                style = MaterialTheme.typography.titleSmall,
                color = Blue400,
                fontWeight = FontWeight.Bold
            )
            BandSummary(map2Ghz)
            BandSummary(map5Ghz)
        }
    }
}

@Composable
private fun BandSummary(map: ChannelInterferenceMap) {
    val recommendation = if (map.recommendedChannels.isEmpty()) "-" else map.recommendedChannels.joinToString(", ")
    Text(
        text = "${map.bandLabel}: ${map.usage.size} channel aktif • rekomendasi: $recommendation",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurfaceVariantDark
    )
}
