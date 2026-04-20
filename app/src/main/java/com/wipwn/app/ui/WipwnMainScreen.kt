package com.wipwn.app.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.ui.theme.*
import com.wipwn.app.viewmodel.AttackRunState
import com.wipwn.app.viewmodel.AttackViewModel
import com.wipwn.app.viewmodel.BatchAttackViewModel
import com.wipwn.app.viewmodel.MainViewModel
import com.wipwn.app.viewmodel.ResultsViewModel
import com.wipwn.app.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WipwnMainScreen(
    mainVm: MainViewModel = viewModel(),
    scanVm: ScanViewModel = viewModel(),
    attackVm: AttackViewModel = viewModel(),
    resultsVm: ResultsViewModel = viewModel(),
    batchVm: BatchAttackViewModel = viewModel()
) {
    val initState by mainVm.initState.collectAsStateWithLifecycle()
    val scanState by scanVm.state.collectAsStateWithLifecycle()
    val attackState by attackVm.state.collectAsStateWithLifecycle()
    val selectedNetwork by attackVm.selectedNetwork.collectAsStateWithLifecycle()
    val results by resultsVm.results.collectAsStateWithLifecycle()
    val batchState by batchVm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var currentScreen by remember { mutableStateOf("home") }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    fun navigateBackInApp(): Boolean {
        return when (currentScreen) {
            "attack" -> {
                scanVm.clearSelection()
                attackVm.clearLastResult()
                currentScreen = "scan"
                true
            }
            "batch" -> {
                // Block back-nav while a batch is running to avoid the user
                // accidentally starting multiple attacks from different
                // screens. Force cancel first if they really want out.
                if (batchState.isRunning) {
                    Toast.makeText(
                        context,
                        "Batch masih jalan — cancel dulu kalo mau keluar",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                } else {
                    currentScreen = "home"
                    true
                }
            }
            "scan", "results" -> {
                currentScreen = "home"
                true
            }
            else -> false
        }
    }

    BackHandler {
        if (navigateBackInApp()) return@BackHandler

        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressMs < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMs = now
            Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
        }
    }

    when (currentScreen) {
        "home" -> HomeContent(
            isRooted = initState.isRooted,
            isChecking = initState.isChecking,
            isReady = initState.isLibraryReady,
            error = initState.error,
            resultCount = results.size,
            scannedCount = scanState.networks.size,
            onRetry = { mainVm.retry() },
            onGoScan = { currentScreen = "scan" },
            onGoResults = { currentScreen = "results" },
            onGoBatch = { currentScreen = "batch" }
        )
        "scan" -> ScanContent(
            networks = scanState.networks,
            securityAnalysis = scanState.securityAnalysis,
            isScanning = scanState.isScanning,
            scanError = scanState.scanError,
            onScan = { scanVm.scan() },
            onSelectNetwork = { network ->
                scanVm.select(network)
                currentScreen = "attack"
            },
            onBack = { currentScreen = "home" }
        )
        "attack" -> AttackContent(
            network = selectedNetwork,
            isAttacking = attackState.isAttacking,
            runState = attackState.runState,
            progress = attackState.progress,
            pinCount = attackState.pinCount,
            totalPins = attackState.totalPins,
            logs = attackState.logs,
            lastResult = attackState.lastResult,
            onStartAttack = { type, pin -> attackVm.start(type, pin) },
            onRetryLast = { attackVm.retryLast() },
            onCancel = { attackVm.cancel() },
            onClearResult = { attackVm.clearLastResult() },
            onBack = {
                scanVm.clearSelection()
                attackVm.clearLastResult()
                currentScreen = "scan"
            }
        )
        "results" -> ResultsContent(
            results = results,
            onBack = { currentScreen = "home" }
        )
        "batch" -> BatchAttackContent(
            availableNetworks = scanState.networks,
            uiState = batchState,
            onStart = { targets -> batchVm.start(targets) },
            onCancel = { batchVm.cancel() },
            onClear = { batchVm.clearResults() },
            onRetryFailed = { batchVm.retryFailed() },
            onBack = {
                if (!batchState.isRunning) currentScreen = "home"
            }
        )
    }
}

// =============================================
// HOME SCREEN
// =============================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    isRooted: Boolean,
    isChecking: Boolean,
    isReady: Boolean,
    error: String?,
    resultCount: Int,
    scannedCount: Int,
    onRetry: () -> Unit,
    onGoScan: () -> Unit,
    onGoResults: () -> Unit,
    onGoBatch: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = SurfaceDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Logo
            val infiniteTransition = rememberInfiniteTransition(label = "glow")
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowAlpha"
            )

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                GreenAccent.copy(alpha = glowAlpha),
                                GreenDark.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "WIPWN",
                style = MaterialTheme.typography.headlineLarge,
                color = GreenAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Text(
                text = "WiFi WPS Penetration Testing",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariantDark
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceCardDark
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Status Sistem",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurfaceDark,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isChecking) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = GreenAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Memeriksa akses root & inisialisasi...",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariantDark
                            )
                        }
                    } else {
                        StatusRow(
                            label = "Root Access",
                            ok = isRooted,
                            icon = Icons.Filled.Security
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow(
                            label = "WPS Engine",
                            ok = isReady,
                            icon = Icons.Filled.SettingsInputAntenna
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow(
                            label = "wpa_supplicant",
                            ok = isReady,
                            icon = Icons.Filled.Cable
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow(
                            label = "pixiewps",
                            ok = isReady,
                            icon = Icons.Filled.BugReport
                        )
                    }

                    if (error != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Red400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = GreenAccent
                            )
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Coba Lagi")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Main action button
            Button(
                onClick = onGoScan,
                enabled = isReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenAccent,
                    contentColor = Color.Black,
                    disabledContainerColor = GreenAccent.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Mulai Scanning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mass Pixie Dust — batch queue entry
            OutlinedButton(
                onClick = onGoBatch,
                enabled = isReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Cyan400,
                    disabledContentColor = Cyan400.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        "Mass Pixie Dust",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (scannedCount > 0) "$scannedCount jaringan siap di-queue"
                        else "Scan dulu, baru queue semua AP",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariantDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Results button
            OutlinedButton(
                onClick = onGoResults,
                enabled = resultCount > 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Blue400
                )
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Riwayat ($resultCount)")
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Info card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceContainerDark
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Panduan Warna Jaringan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceDark
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LegendItem(color = StatusVulnerable, text = "WPS Aktif — Kemungkinan rentan")
                    LegendItem(color = StatusLocked, text = "WPS Terkunci — Router memblokir")
                    LegendItem(color = StatusUnknown, text = "Tidak Diketahui — Perlu analisis")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "v2.0.0 — Hanya untuk pengujian yang diizinkan",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariantDark.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (ok) GreenAccent else Red400,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDark,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (ok) GreenAccent else Red400,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariantDark
        )
    }
}
