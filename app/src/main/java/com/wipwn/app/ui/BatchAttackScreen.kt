package com.wipwn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.ui.theme.*
import com.wipwn.app.viewmodel.BatchTarget
import com.wipwn.app.viewmodel.BatchTargetState
import com.wipwn.app.viewmodel.BatchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAttackContent(
    availableNetworks: List<WifiNetwork>,
    uiState: BatchUiState,
    onStart: (List<WifiNetwork>) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onRetryFailed: () -> Unit,
    onBack: () -> Unit
) {
    // Default selection: all WPS-enabled networks
    val defaultSelection = remember(availableNetworks) {
        availableNetworks.filter { it.wpsEnabled }.map { it.bssid }.toMutableSet()
    }
    val selectedBssids = remember {
        mutableStateOf<Set<String>>(defaultSelection)
    }

    // When the batch is running or finished, hide the picker and show live panel.
    val showPicker = uiState.targets.isEmpty()

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mass Pixie Dust", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                uiState.isRunning -> "Lagi jalan — ${uiState.currentIndex + 1}/${uiState.total}"
                                uiState.finished -> "Selesai — ${uiState.successCount} sukses / ${uiState.failedCount} gagal"
                                else -> "Pilih target dan gas"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariantDark
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (uiState.targets.isNotEmpty() && !uiState.isRunning) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = OnSurfaceDark,
                    navigationIconContentColor = OnSurfaceDark,
                    actionIconContentColor = OnSurfaceDark
                )
            )
        },
        bottomBar = {
            BatchBottomBar(
                uiState = uiState,
                selectedCount = selectedBssids.value.size,
                onStart = {
                    val picked = availableNetworks.filter { it.bssid in selectedBssids.value }
                    if (picked.isNotEmpty()) onStart(picked)
                },
                onCancel = onCancel,
                onRetryFailed = onRetryFailed
            )
        }
    ) { padding ->
        if (showPicker) {
            TargetPicker(
                padding = padding,
                availableNetworks = availableNetworks,
                selectedBssids = selectedBssids.value,
                onToggle = { bssid ->
                    selectedBssids.value = selectedBssids.value.toMutableSet().also {
                        if (!it.add(bssid)) it.remove(bssid)
                    }
                },
                onSelectAll = {
                    selectedBssids.value = availableNetworks.map { it.bssid }.toSet()
                },
                onSelectWpsOnly = {
                    selectedBssids.value = availableNetworks.filter { it.wpsEnabled }
                        .map { it.bssid }.toSet()
                },
                onClearSelection = { selectedBssids.value = emptySet() }
            )
        } else {
            RunningPanel(padding = padding, uiState = uiState)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Target picker (pre-run)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun TargetPicker(
    padding: PaddingValues,
    availableNetworks: List<WifiNetwork>,
    selectedBssids: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectWpsOnly: () -> Unit,
    onClearSelection: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Pilih jaringan yang mau di-pentest sekaligus.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDark
                    )
                    Text(
                        "Default udah nge-ceklis semua jaringan WPS-aktif. Tiap target dijalanin satu-per-satu — AP yang ga vulnerable bakal auto-skip ke target berikutnya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariantDark
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = onSelectWpsOnly,
                            label = { Text("WPS only") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        AssistChip(
                            onClick = onSelectAll,
                            label = { Text("Semua (${availableNetworks.size})") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.DoneAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        AssistChip(
                            onClick = onClearSelection,
                            label = { Text("Clear") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

        if (availableNetworks.isEmpty()) {
            item {
                EmptyScanHint()
            }
        }

        items(availableNetworks, key = { it.bssid }) { network ->
            val checked = network.bssid in selectedBssids
            TargetRow(
                network = network,
                checked = checked,
                onToggle = { onToggle(network.bssid) }
            )
        }

        item { Spacer(modifier = Modifier.height(120.dp)) }
    }
}

@Composable
private fun EmptyScanHint() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.WifiFind,
                contentDescription = null,
                tint = OnSurfaceVariantDark,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Belum ada hasil scan. Balik ke Scan dulu bre.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariantDark
            )
        }
    }
}

@Composable
private fun TargetRow(
    network: WifiNetwork,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val accent = if (network.wpsEnabled) GreenAccent else OnSurfaceVariantDark
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = GreenAccent,
                    uncheckedColor = OnSurfaceVariantDark
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    network.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfaceDark,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${network.bssid} • CH ${network.channel} • ${network.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
            }
            if (network.wpsEnabled) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "WPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Running / finished panel
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun RunningPanel(
    padding: PaddingValues,
    uiState: BatchUiState
) {
    val listState = rememberLazyListState()
    val logState = rememberLazyListState()

    // Auto-scroll log to the newest line
    LaunchedEffect(uiState.currentLogs.size) {
        if (uiState.currentLogs.isNotEmpty()) {
            logState.animateScrollToItem(uiState.currentLogs.lastIndex)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SummaryCard(uiState) }

        if (uiState.isRunning) {
            item {
                val progress = if (uiState.total == 0) 0f
                    else (uiState.currentIndex.coerceAtLeast(0)).toFloat() / uiState.total.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = GreenAccent,
                    trackColor = GreenAccent.copy(alpha = 0.15f)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            "Live log (${uiState.currentLogs.size} line)",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariantDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(
                            state = logState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(SurfaceDark, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            items(uiState.currentLogs) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = logLineColor(line)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Queue",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceVariantDark,
                fontWeight = FontWeight.Bold
            )
        }

        items(uiState.targets, key = { it.network.bssid }) { target ->
            TargetResultCard(target)
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
private fun SummaryCard(uiState: BatchUiState) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                when {
                    uiState.isRunning -> "Pentest batch lagi jalan"
                    uiState.finished -> "Batch selesai"
                    else -> "Siap dijalanin"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurfaceDark
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat("Total", uiState.total.toString(), OnSurfaceDark)
                Stat("Sukses", uiState.successCount.toString(), GreenAccent)
                Stat("Gagal", uiState.failedCount.toString(), Red400)
                Stat("Skip", uiState.skippedCount.toString(), Yellow400)
            }
            if (uiState.isRunning) {
                val cur = uiState.targets.getOrNull(uiState.currentIndex)
                if (cur != null) {
                    Text(
                        "Sedang menyerang: ${cur.network.displayName} (${cur.network.bssid})",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cyan400
                    )
                }
            }
            if (uiState.finished && uiState.successCount > 0) {
                Divider(color = OnSurfaceVariantDark.copy(alpha = 0.2f))
                Text(
                    "Credential yang ketemu:",
                    style = MaterialTheme.typography.labelMedium,
                    color = GreenAccent,
                    fontWeight = FontWeight.Bold
                )
                uiState.targets
                    .filter { it.state == BatchTargetState.SUCCESS }
                    .forEach { target ->
                        Column {
                            Text(
                                target.network.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceDark,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "PIN: ${target.result?.pin ?: "-"}  •  PSK: ${target.result?.password ?: "-"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = OnSurfaceVariantDark
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariantDark
        )
    }
}

@Composable
private fun TargetResultCard(target: BatchTarget) {
    val (iconVec, tint) = when (target.state) {
        BatchTargetState.PENDING -> Icons.Filled.Schedule to OnSurfaceVariantDark
        BatchTargetState.RUNNING -> Icons.Filled.Autorenew to Cyan400
        BatchTargetState.SUCCESS -> Icons.Filled.CheckCircle to GreenAccent
        BatchTargetState.FAILED -> Icons.Filled.Cancel to Red400
        BatchTargetState.SKIPPED -> Icons.Filled.SkipNext to Yellow400
        BatchTargetState.CANCELLED -> Icons.Filled.Block to Orange400
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconVec, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    target.network.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfaceDark,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${target.network.bssid} • ${target.state.label()}${target.durationMs?.let { " • ${it}ms" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
                when (target.state) {
                    BatchTargetState.SUCCESS -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "PIN: ${target.result?.pin ?: "-"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = GreenAccent
                        )
                        if (!target.result?.password.isNullOrEmpty()) {
                            Text(
                                "PSK: ${target.result?.password}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = GreenAccent
                            )
                        }
                    }
                    BatchTargetState.FAILED,
                    BatchTargetState.CANCELLED -> {
                        if (!target.errorMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                target.errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = Red400.copy(alpha = 0.9f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun BatchBottomBar(
    uiState: BatchUiState,
    selectedCount: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit
) {
    Surface(color = SurfaceVariantDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                uiState.isRunning -> {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Red400,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel Batch", fontWeight = FontWeight.Bold)
                    }
                }
                uiState.finished -> {
                    if (uiState.failedCount > 0) {
                        OutlinedButton(
                            onClick = onRetryFailed,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange400)
                        ) {
                            Icon(Icons.Filled.Replay, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Failed (${uiState.failedCount})")
                        }
                    }
                    Button(
                        onClick = onStart,
                        enabled = selectedCount > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenAccent,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Again", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Button(
                        onClick = onStart,
                        enabled = selectedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenAccent,
                            contentColor = Color.Black,
                            disabledContainerColor = GreenAccent.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Gas Pixie Dust $selectedCount target",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun BatchTargetState.label(): String = when (this) {
    BatchTargetState.PENDING -> "antre"
    BatchTargetState.RUNNING -> "lagi jalan"
    BatchTargetState.SUCCESS -> "sukses"
    BatchTargetState.FAILED -> "gagal"
    BatchTargetState.SKIPPED -> "dilewati"
    BatchTargetState.CANCELLED -> "dibatalin"
}

private fun logLineColor(line: String): Color = when {
    line.startsWith("[batch]") -> Cyan400
    line.contains("✓") -> GreenAccent
    line.contains("✗") -> Red400
    line.startsWith("[lib]") -> OnSurfaceVariantDark
    line.startsWith("[pixie]") -> Yellow400
    else -> OnSurfaceDark
}
