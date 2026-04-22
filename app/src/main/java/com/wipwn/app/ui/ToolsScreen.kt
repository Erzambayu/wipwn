package com.wipwn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wipwn.app.data.*
import com.wipwn.app.ui.theme.*
import com.wipwn.app.util.MacSpoofer
import com.wipwn.app.viewmodel.*

// ═══════════════════════════════════════════════════════════════════
// TOOLS HUB — main entry point for all advanced tools
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsContent(
    network: WifiNetwork?,
    macState: MacSpoofState,
    vulnState: VulnAssessmentState,
    reconState: ReconState,
    advancedState: AdvancedAttackState,
    results: List<AttackResult>,
    // scan + target
    scannedNetworks: List<WifiNetwork>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onSelectTarget: (WifiNetwork) -> Unit,
    // tools
    onSpoofMac: (String?) -> Unit,
    onRestoreMac: () -> Unit,
    onRefreshMac: () -> Unit,
    onAssessNetwork: (WifiNetwork) -> Unit,
    onRunAdvancedAttack: (WifiNetwork, AttackType, AttackConfig) -> Unit,
    onStopEvilTwin: () -> Unit,
    onStopDeauth: () -> Unit,
    onCancelAdvanced: () -> Unit,
    onQuickRecon: () -> Unit,
    onExport: (List<AttackResult>, CredentialExporter.ExportFormat) -> CredentialExporter.ExportResult,
    onFormatClipboard: (AttackResult) -> String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentTool by remember { mutableStateOf("hub") }

    // local selected target — starts from repo's selectedNetwork, can be changed inline
    var localTarget by remember(network) { mutableStateOf(network) }

    when (currentTool) {
        "hub" -> ToolsHub(
            network = localTarget,
            macState = macState,
            advancedState = advancedState,
            onSelectTool = { currentTool = it },
            onRefreshMac = onRefreshMac,
            onBack = onBack
        )
        "mac" -> MacSpoofContent(
            macState = macState,
            onSpoof = onSpoofMac,
            onRestore = onRestoreMac,
            onRefresh = onRefreshMac,
            onBack = { currentTool = "hub" }
        )
        "vuln" -> VulnAssessmentContent(
            network = localTarget,
            vulnState = vulnState,
            scannedNetworks = scannedNetworks,
            isScanning = isScanning,
            onScan = onScan,
            onSelectTarget = { picked ->
                localTarget = picked
                onSelectTarget(picked)
                onAssessNetwork(picked)
            },
            onAssess = { localTarget?.let { onAssessNetwork(it) } },
            onBack = { currentTool = "hub" }
        )
        "advanced" -> AdvancedAttackContent(
            network = localTarget,
            advancedState = advancedState,
            scannedNetworks = scannedNetworks,
            isScanning = isScanning,
            onScan = onScan,
            onSelectTarget = { picked ->
                localTarget = picked
                onSelectTarget(picked)
            },
            onRunAttack = { type, config ->
                localTarget?.let { onRunAdvancedAttack(it, type, config) }
            },
            onStopEvilTwin = onStopEvilTwin,
            onStopDeauth = onStopDeauth,
            onCancel = onCancelAdvanced,
            onBack = { currentTool = "hub" }
        )
        "recon" -> ReconContent(
            reconState = reconState,
            onQuickRecon = onQuickRecon,
            onBack = { currentTool = "hub" }
        )
        "export" -> ExportContent(
            results = results,
            onExport = onExport,
            onFormatClipboard = onFormatClipboard,
            onBack = { currentTool = "hub" }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// TOOLS HUB
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsHub(
    network: WifiNetwork?,
    macState: MacSpoofState,
    advancedState: AdvancedAttackState,
    onSelectTool: (String) -> Unit,
    onRefreshMac: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onRefreshMac() }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Advanced Tools", fontWeight = FontWeight.Bold)
                        if (network != null) {
                            Text(
                                "Target: ${network.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariantDark
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = OnSurfaceDark,
                    navigationIconContentColor = OnSurfaceDark
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Quick status
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Quick Status", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusPill("MAC", macState.currentMac?.takeLast(8) ?: "?", if (macState.isSpoofed) Orange400 else GreenAccent)
                            if (advancedState.evilTwinActive) StatusPill("Evil Twin", "ACTIVE", Red400)
                            if (advancedState.deauthActive) StatusPill("Deauth", "ACTIVE", Red400)
                        }
                    }
                }
            }

            // Tool categories
            item {
                Text("Exploit Tools", style = MaterialTheme.typography.labelLarge, color = GreenAccent, fontWeight = FontWeight.Bold)
            }

            item {
                ToolCard(
                    title = "MAC Spoofing",
                    description = "Ganti MAC address buat bypass filtering & reset rate-limit",
                    icon = Icons.Filled.Shuffle,
                    accentColor = Orange400,
                    badge = if (macState.isSpoofed) "SPOOFED" else null,
                    onClick = { onSelectTool("mac") }
                )
            }

            item {
                ToolCard(
                    title = "Vulnerability Assessment",
                    description = if (network != null) "Analisis kerentanan ${network.displayName}" else "Analisis kerentanan target — pilih target dari Scan dulu",
                    icon = Icons.Filled.Shield,
                    accentColor = Yellow400,
                    onClick = { onSelectTool("vuln") }
                )
            }

            item {
                ToolCard(
                    title = "Advanced Attacks",
                    description = if (network != null) "Deauth, Handshake, PMKID, Evil Twin → ${network.displayName}" else "Deauth, Handshake, PMKID, Evil Twin — pilih target dari Scan dulu",
                    icon = Icons.Filled.Bolt,
                    accentColor = Red400,
                    onClick = { onSelectTool("advanced") }
                )
            }

            if (network == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Orange400.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Info, tint = Orange400, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Beberapa tool butuh target. Scan dulu → pilih network → balik ke sini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Orange400
                            )
                        }
                    }
                }
            }

            item {
                ToolCard(
                    title = "Network Recon",
                    description = "Discover devices, port scan, default creds check",
                    icon = Icons.Filled.Radar,
                    accentColor = Cyan400,
                    onClick = { onSelectTool("recon") }
                )
            }

            item {
                Text("Utilities", style = MaterialTheme.typography.labelLarge, color = Blue400, fontWeight = FontWeight.Bold)
            }

            item {
                ToolCard(
                    title = "Export Credentials",
                    description = "Export hasil ke CSV, JSON, atau share",
                    icon = Icons.Filled.FileDownload,
                    accentColor = Blue400,
                    onClick = { onSelectTool("export") }
                )
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// MAC SPOOFING
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacSpoofContent(
    macState: MacSpoofState,
    onSpoof: (String?) -> Unit,
    onRestore: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    var customMac by remember { mutableStateOf("") }
    var selectedVendor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onRefresh() }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = { Text("MAC Spoofing", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark, titleContentColor = OnSurfaceDark, navigationIconContentColor = OnSurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Current MAC
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current MAC", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariantDark)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            macState.currentMac ?: "Unknown",
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (macState.isSpoofed) Orange400 else GreenAccent
                        )
                        if (macState.isSpoofed && macState.originalMac != null) {
                            Text("Original: ${macState.originalMac}", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
                        }
                    }
                }
            }

            // Quick actions
            item {
                Text("Quick Spoof", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
            }

            item {
                Button(
                    onClick = { onSpoof(null) },
                    enabled = !macState.isWorking,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange400, contentColor = Color.Black)
                ) {
                    if (macState.isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Random MAC", fontWeight = FontWeight.Bold)
                }
            }

            if (macState.isSpoofed) {
                item {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = !macState.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenAccent)
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Original MAC")
                    }
                }
            }

            // Vendor spoof
            item {
                Text("Vendor Spoof (Stealth)", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MacSpoofer.COMMON_OUIS.keys.take(3).forEach { vendor ->
                        AssistChip(
                            onClick = {
                                selectedVendor = vendor
                                val oui = MacSpoofer.COMMON_OUIS[vendor] ?: return@AssistChip
                                // Generate and spoof
                                onSpoof(null) // simplified — in real impl would use vendor MAC
                            },
                            label = { Text(vendor, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Custom MAC
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Custom MAC", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customMac,
                            onValueChange = { if (it.length <= 17) customMac = it },
                            label = { Text("MAC Address (XX:XX:XX:XX:XX:XX)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange400, focusedLabelColor = Orange400, cursorColor = Orange400)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onSpoof(customMac) },
                            enabled = customMac.length == 17 && !macState.isWorking,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Orange400, contentColor = Color.Black)
                        ) {
                            Text("Apply Custom MAC", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Error
            if (macState.lastError != null) {
                item {
                    Text(macState.lastError, style = MaterialTheme.typography.bodySmall, color = Red400)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// VULNERABILITY ASSESSMENT
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VulnAssessmentContent(
    network: WifiNetwork?,
    vulnState: VulnAssessmentState,
    scannedNetworks: List<WifiNetwork>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onSelectTarget: (WifiNetwork) -> Unit,
    onAssess: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(network) { if (network != null) onAssess() }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vulnerability Assessment", fontWeight = FontWeight.Bold)
                        if (network != null) {
                            Text("Target: ${network.displayName}", style = MaterialTheme.typography.bodySmall, color = GreenAccent)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark, titleContentColor = OnSurfaceDark, navigationIconContentColor = OnSurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Inline network picker (always shown at top) ────────────────
            item {
                InlineNetworkPicker(
                    currentTarget = network,
                    scannedNetworks = scannedNetworks,
                    isScanning = isScanning,
                    onScan = onScan,
                    onSelect = onSelectTarget
                )
            }

            if (network == null) {
                item {
                    Text(
                        "Scan dan pilih target di atas buat mulai assessment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariantDark
                    )
                }
                return@LazyColumn
            }

            val assessment = vulnState.assessment
            if (assessment != null) {
                // Overall level
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(network.displayName, style = MaterialTheme.typography.titleMedium, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
                            Text(network.bssid, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
                            Spacer(modifier = Modifier.height(16.dp))
                            val levelColor = vulnLevelColor(assessment.overallLevel)
                            Text(assessment.overallLevel.emoji, fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(assessment.overallLevel.label, style = MaterialTheme.typography.titleLarge, color = levelColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Attack chances
                item {
                    Text("Attack Success Chance", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VulnChip("Pixie Dust", assessment.pixieDustChance, Modifier.weight(1f))
                        VulnChip("Known PIN", assessment.knownPinChance, Modifier.weight(1f))
                        VulnChip("Brute Force", assessment.bruteForceChance, Modifier.weight(1f))
                    }
                }

                // Reasons
                item {
                    Text("Analysis", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold)
                }

                items(assessment.reasons) { reason ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("•", color = OnSurfaceVariantDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark)
                    }
                }

                // Recommendations
                if (assessment.recommendations.isNotEmpty()) {
                    item {
                        Text("Recommendations", style = MaterialTheme.typography.titleSmall, color = GreenAccent, fontWeight = FontWeight.Bold)
                    }
                    items(assessment.recommendations) { rec ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("→", color = GreenAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(rec, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDark)
                        }
                    }
                }

                // Generated PINs
                if (vulnState.generatedPins.isNotEmpty()) {
                    item {
                        Text("Algorithmic PINs (${vulnState.generatedPins.size})", style = MaterialTheme.typography.titleSmall, color = Cyan400, fontWeight = FontWeight.Bold)
                    }
                    items(vulnState.generatedPins) { gp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceCardDark)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(gp.pin, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = GreenAccent, modifier = Modifier.weight(1f))
                            Text(gp.algorithm, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
                            Spacer(modifier = Modifier.width(8.dp))
                            val confColor = when (gp.confidence) {
                                PinGenerator.PinConfidence.HIGH -> GreenAccent
                                PinGenerator.PinConfidence.MEDIUM -> Yellow400
                                PinGenerator.PinConfidence.LOW -> OnSurfaceVariantDark
                            }
                            Text(gp.confidence.name, style = MaterialTheme.typography.labelSmall, color = confColor)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ADVANCED ATTACKS
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedAttackContent(
    network: WifiNetwork?,
    advancedState: AdvancedAttackState,
    scannedNetworks: List<WifiNetwork>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onSelectTarget: (WifiNetwork) -> Unit,
    onRunAttack: (AttackType, AttackConfig) -> Unit,
    onStopEvilTwin: () -> Unit,
    onStopDeauth: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Advanced Attacks", fontWeight = FontWeight.Bold)
                        when {
                            advancedState.isRunning -> Text("${advancedState.currentType?.displayName ?: ""} running...", style = MaterialTheme.typography.bodySmall, color = Cyan400)
                            network != null -> Text("Target: ${network.displayName}", style = MaterialTheme.typography.bodySmall, color = GreenAccent)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark, titleContentColor = OnSurfaceDark, navigationIconContentColor = OnSurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Inline network picker ───────────────────────────────────────
            item {
                InlineNetworkPicker(
                    currentTarget = network,
                    scannedNetworks = scannedNetworks,
                    isScanning = isScanning,
                    onScan = onScan,
                    onSelect = onSelectTarget
                )
            }

            if (network == null) {
                item {
                    Text(
                        "Scan dan pilih target di atas buat mulai attack.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariantDark
                    )
                }
                return@LazyColumn
            }

            // Active controls
            if (advancedState.evilTwinActive) {
                item {
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Red400.copy(alpha = 0.15f))) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Wifi, tint = Red400, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Evil Twin ACTIVE", color = Red400, fontWeight = FontWeight.Bold)
                                Text("Fake AP broadcasting...", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark)
                            }
                            Button(onClick = onStopEvilTwin, colors = ButtonDefaults.buttonColors(containerColor = Red400)) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }

            if (advancedState.isRunning) {
                item {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red400)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel Attack")
                    }
                }
            }

            // Attack options
            if (!advancedState.isRunning) {
                item { Text("WiFi Attacks", style = MaterialTheme.typography.labelLarge, color = Red400, fontWeight = FontWeight.Bold) }

                item {
                    ToolCard(
                        title = "Deauthentication",
                        description = "Kirim deauth frames — disconnect semua client dari AP",
                        icon = Icons.Filled.WifiOff,
                        accentColor = Red400,
                        onClick = { onRunAttack(AttackType.DEAUTH, AttackConfig()) }
                    )
                }

                item {
                    ToolCard(
                        title = "Handshake Capture",
                        description = "Capture WPA handshake + crack dengan wordlist",
                        icon = Icons.Filled.Handshake,
                        accentColor = Orange400,
                        onClick = { onRunAttack(AttackType.HANDSHAKE_CAPTURE, AttackConfig()) }
                    )
                }

                item {
                    ToolCard(
                        title = "PMKID Attack",
                        description = "Clientless attack — ambil PMKID langsung dari AP",
                        icon = Icons.Filled.Key,
                        accentColor = Yellow400,
                        onClick = { onRunAttack(AttackType.PMKID, AttackConfig()) }
                    )
                }

                item {
                    ToolCard(
                        title = "Evil Twin AP",
                        description = "Spawn fake AP + captive portal buat credential phishing",
                        icon = Icons.Filled.ContentCopy,
                        accentColor = Red400,
                        onClick = { onRunAttack(AttackType.EVIL_TWIN, AttackConfig()) }
                    )
                }

                item { Text("WPS Extended", style = MaterialTheme.typography.labelLarge, color = Cyan400, fontWeight = FontWeight.Bold) }

                item {
                    ToolCard(
                        title = "Algorithmic PIN",
                        description = "Generate & test PINs berdasarkan BSSID + vendor algorithm",
                        icon = Icons.Filled.Calculate,
                        accentColor = Cyan400,
                        onClick = { onRunAttack(AttackType.ALGORITHMIC_PIN, AttackConfig()) }
                    )
                }

                item {
                    ToolCard(
                        title = "Null PIN Attack",
                        description = "Test empty/null PIN variants",
                        icon = Icons.Filled.RemoveCircle,
                        accentColor = OnSurfaceVariantDark,
                        onClick = { onRunAttack(AttackType.NULL_PIN, AttackConfig()) }
                    )
                }
            }

            // Logs
            if (advancedState.logs.isNotEmpty()) {
                item { Text("Attack Log", style = MaterialTheme.typography.labelLarge, color = OnSurfaceVariantDark, fontWeight = FontWeight.Bold) }
                item { AdvancedLogTerminal(logs = advancedState.logs) }
            }

            // Result
            if (advancedState.lastResult != null) {
                item {
                    val r = advancedState.lastResult
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.success) GreenAccent.copy(alpha = 0.1f) else Red400.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (r.success) "✓ ${r.attackType?.displayName ?: "Attack"} Berhasil!" else "✗ ${r.attackType?.displayName ?: "Attack"} Gagal",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (r.success) GreenAccent else Red400,
                                fontWeight = FontWeight.Bold
                            )
                            if (r.password != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Password: ${r.password}", fontFamily = FontFamily.Monospace, color = GreenAccent)
                            }
                            if (r.reconData != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(r.reconData, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = OnSurfaceVariantDark)
                            }
                            if (r.error != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(r.errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = Red400)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// NETWORK RECON
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReconContent(
    reconState: ReconState,
    onQuickRecon: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = { Text("Network Recon", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark, titleContentColor = OnSurfaceDark, navigationIconContentColor = OnSurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    onClick = onQuickRecon,
                    enabled = !reconState.isRunning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Color.Black)
                ) {
                    if (reconState.isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Radar, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (reconState.isRunning) "Scanning..." else "Quick Recon", fontWeight = FontWeight.Bold)
                }
            }

            val result = reconState.result
            if (result != null) {
                item {
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Network Info", style = MaterialTheme.typography.titleSmall, color = Cyan400, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            ReconInfoRow("Gateway", result.gateway ?: "N/A")
                            ReconInfoRow("Local IP", result.localIp ?: "N/A")
                            ReconInfoRow("Subnet", result.subnet ?: "N/A")
                            ReconInfoRow("Admin Panel", result.adminPanelUrl ?: "N/A")
                            ReconInfoRow("Panel Status", if (result.adminPanelReachable) "Reachable" else "Unreachable")
                        }
                    }
                }

                if (result.devices.isNotEmpty()) {
                    item { Text("Discovered Devices (${result.devices.size})", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold) }
                    items(result.devices) { device ->
                        Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (device.isGateway) Icons.Filled.Router else Icons.Filled.Devices,
                                    contentDescription = null,
                                    tint = if (device.isGateway) Cyan400 else OnSurfaceVariantDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(device.ip, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${device.mac ?: "?"}${if (device.isGateway) " [GATEWAY]" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnSurfaceVariantDark
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (reconState.error != null) {
                item { Text(reconState.error, style = MaterialTheme.typography.bodySmall, color = Red400) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// EXPORT
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportContent(
    results: List<AttackResult>,
    onExport: (List<AttackResult>, CredentialExporter.ExportFormat) -> CredentialExporter.ExportResult,
    onFormatClipboard: (AttackResult) -> String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val successResults = results.filter { it.success }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Export Credentials", fontWeight = FontWeight.Bold)
                        Text("${results.size} total, ${successResults.size} sukses", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark, titleContentColor = OnSurfaceDark, navigationIconContentColor = OnSurfaceDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Export Format", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.Bold) }

            item {
                ExportButton("Export All as CSV", Icons.Filled.TableChart, Blue400) {
                    val export = onExport(results, CredentialExporter.ExportFormat.CSV)
                    shareExport(context, export)
                }
            }
            item {
                ExportButton("Export All as JSON", Icons.Filled.Code, Cyan400) {
                    val export = onExport(results, CredentialExporter.ExportFormat.JSON)
                    shareExport(context, export)
                }
            }
            item {
                ExportButton("Export Report (Text)", Icons.Filled.Description, GreenAccent) {
                    val export = onExport(results, CredentialExporter.ExportFormat.TEXT)
                    shareExport(context, export)
                }
            }

            if (successResults.isNotEmpty()) {
                item { Text("Credentials Only", style = MaterialTheme.typography.titleSmall, color = GreenAccent, fontWeight = FontWeight.Bold) }

                items(successResults) { result ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark),
                        modifier = Modifier.clickable {
                            val text = onFormatClipboard(result)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("WiPwn", text))
                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.ssid, style = MaterialTheme.typography.titleSmall, color = OnSurfaceDark, fontWeight = FontWeight.SemiBold)
                                if (result.pin != null) Text("PIN: ${result.pin}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = GreenAccent)
                                if (result.password != null) Text("PSK: ${result.password}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = GreenAccent)
                            }
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = OnSurfaceVariantDark, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FolderOff, contentDescription = null, tint = OnSurfaceVariantDark, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Belum ada hasil buat di-export", color = OnSurfaceVariantDark)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════
// INLINE NETWORK PICKER — scan + pick target tanpa keluar screen
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun InlineNetworkPicker(
    currentTarget: WifiNetwork?,
    scannedNetworks: List<WifiNetwork>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onSelect: (WifiNetwork) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row: current target + scan button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Sensors,
                    contentDescription = null,
                    tint = if (currentTarget != null) GreenAccent else OnSurfaceVariantDark,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (currentTarget != null) {
                        Text(
                            currentTarget.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = OnSurfaceDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${currentTarget.bssid} • CH ${currentTarget.channel} • ${currentTarget.rssi}dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariantDark
                        )
                    } else {
                        Text("Belum ada target", style = MaterialTheme.typography.titleSmall, color = OnSurfaceVariantDark)
                        Text("Tap Scan buat cari jaringan", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark.copy(alpha = 0.6f))
                    }
                }
                FilledTonalButton(
                    onClick = { onScan(); expanded = true },
                    enabled = !isScanning,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = GreenAccent.copy(alpha = 0.15f),
                        contentColor = GreenAccent
                    )
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GreenAccent, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isScanning) "Scanning" else "Scan", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Expandable network list
            if (scannedNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${scannedNetworks.size} jaringan ditemukan", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null, tint = OnSurfaceVariantDark, modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val sorted = scannedNetworks.sortedByDescending { it.wpsEnabled }
                        sorted.take(20).forEach { net ->
                            val isSelected = currentTarget?.bssid == net.bssid
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) GreenAccent.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { onSelect(net); expanded = false }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape)
                                        .background(if (net.wpsEnabled) GreenAccent else OnSurfaceVariantDark.copy(alpha = 0.3f))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        net.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GreenAccent else OnSurfaceDark,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text("${net.bssid} • ${net.rssi}dBm", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark.copy(alpha = 0.7f))
                                }
                                if (net.wpsEnabled) {
                                    Text("WPS", style = MaterialTheme.typography.labelSmall, color = GreenAccent, fontWeight = FontWeight.Bold)
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GreenAccent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) SurfaceCardDark else SurfaceCardDark.copy(alpha = 0.5f)
        )
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (enabled) OnSurfaceDark else OnSurfaceVariantDark)
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = accentColor.copy(alpha = 0.2f)) {
                            Text(badge, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark.copy(alpha = 0.7f), lineHeight = 16.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = accentColor.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f)) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
            Spacer(modifier = Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VulnChip(label: String, level: VulnerabilityAssessor.VulnLevel, modifier: Modifier = Modifier) {
    val color = vulnLevelColor(level)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariantDark)
            Spacer(modifier = Modifier.height(4.dp))
            Text(level.label.split(" ").first(), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReconInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDark, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ExportButton(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AdvancedLogTerminal(logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F14)),
        modifier = Modifier.border(1.dp, GreenAccent.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp).padding(12.dp)
        ) {
            items(logs) { log ->
                val color = when {
                    log.contains("✓") -> GreenAccent
                    log.contains("✗") || log.contains("ERROR") || log.contains("FAILED") -> Red400
                    log.startsWith("[") -> Blue400.copy(alpha = 0.7f)
                    else -> OnSurfaceVariantDark.copy(alpha = 0.6f)
                }
                Text(log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = color, modifier = Modifier.padding(vertical = 1.dp), lineHeight = 16.sp)
            }
        }
    }
}

private fun vulnLevelColor(level: VulnerabilityAssessor.VulnLevel): Color = when (level) {
    VulnerabilityAssessor.VulnLevel.CRITICAL -> Red400
    VulnerabilityAssessor.VulnLevel.HIGH -> Orange400
    VulnerabilityAssessor.VulnLevel.MEDIUM -> Yellow400
    VulnerabilityAssessor.VulnLevel.LOW -> GreenAccent
    VulnerabilityAssessor.VulnLevel.UNKNOWN -> OnSurfaceVariantDark
}

private fun shareExport(context: Context, export: CredentialExporter.ExportResult) {
    val intent = CredentialExporter.createShareIntent(export.content, export.mimeType)
    context.startActivity(android.content.Intent.createChooser(intent, "Share ${export.filename}"))
}
