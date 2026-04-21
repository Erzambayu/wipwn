package com.wipwn.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wipwn.app.data.AttackResult
import com.wipwn.app.data.AttackType
import com.wipwn.app.data.WifiNetwork
import com.wipwn.app.ui.theme.*
import com.wipwn.app.viewmodel.AttackRunState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttackContent(
    network: WifiNetwork?,
    isAttacking: Boolean,
    runState: AttackRunState,
    progress: String,
    pinCount: Int,
    totalPins: Int,
    logs: List<String>,
    lastResult: AttackResult?,
    onStartAttack: (AttackType, String?) -> Unit,
    onRetryLast: () -> Unit,
    onCancel: () -> Unit,
    onClearResult: () -> Unit,
    onBack: () -> Unit
) {
    if (network == null) {
        onBack()
        return
    }

    var customPin by remember { mutableStateOf("") }
    var logFilter by remember { mutableStateOf("all") }

    // Track if an attack was ever started in this session
    val hasAttacked = logs.isNotEmpty() || lastResult != null

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Animated dot indicator
                        if (isAttacking) {
                            val pulse = rememberInfiniteTransition(label = "pulse")
                            val alpha by pulse.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "dotPulse"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .alpha(alpha)
                                    .background(GreenAccent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                network.displayName,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                network.bssid,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceVariantDark.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ─── Network Info Card ───
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (network.wpsEnabled)
                                        GreenAccent.copy(alpha = 0.12f)
                                    else
                                        OnSurfaceVariantDark.copy(alpha = 0.08f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Router,
                                contentDescription = null,
                                tint = if (network.wpsEnabled) GreenAccent else OnSurfaceVariantDark,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            InfoChip("CH ${network.channel}", "Signal ${network.rssi}dBm")
                            Spacer(modifier = Modifier.height(4.dp))
                            StateChip(runState)
                        }
                        // WPS Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (network.wpsEnabled)
                                GreenAccent.copy(alpha = 0.15f)
                            else
                                Red400.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (network.wpsEnabled) "WPS ON" else "WPS OFF",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (network.wpsEnabled) GreenAccent else Red400
                            )
                        }
                    }
                }
            }

            // ─── Result Banner (persistent) ───
            if (lastResult != null) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + expandVertically()
                    ) {
                        ResultBanner(result = lastResult)
                    }
                }
            }

            // ─── Attack Progress ───
            if (isAttacking) {
                item {
                    AttackProgressSection(
                        progress = progress,
                        pinCount = pinCount,
                        totalPins = totalPins,
                        onCancel = onCancel
                    )
                }
            }

            // ─── Log Terminal (persistent — stays after attack ends) ───
            if (hasAttacked) {
                item {
                    LogFilterRow(
                        selected = logFilter,
                        onSelect = { logFilter = it }
                    )
                }
                item {
                    val filtered = remember(logs, logFilter) {
                        when (logFilter) {
                            "prepare" -> logs.filter { it.contains("[prepare]") }
                            "run" -> logs.filter { it.contains("[run]") }
                            "io" -> logs.filter { it.contains("[io]") }
                            "result" -> logs.filter { it.contains("[result]") || it.startsWith("✓") || it.startsWith("✗") }
                            else -> logs
                        }
                    }
                    LogTerminal(logs = filtered)
                }
            }

            // ─── Attack Buttons (always visible below) ───
            if (!isAttacking) {
                item {
                    Text(
                        "Pilih Serangan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceDark
                    )
                }

                item {
                    OutlinedButton(
                        onClick = onRetryLast,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Last Method")
                    }
                }

                item {
                    AttackOptionCard(
                        title = "Test Known PINs",
                        description = "Uji PIN default dari database vendor",
                        icon = Icons.Filled.Key,
                        accentColor = GreenAccent,
                        onClick = { onStartAttack(AttackType.KNOWN_PINS, null) }
                    )
                }

                item {
                    AttackOptionCard(
                        title = "Pixie Dust Attack",
                        description = "Eksploitasi kelemahan RNG di chipset WPS",
                        icon = Icons.Filled.AutoFixHigh,
                        accentColor = Cyan400,
                        onClick = { onStartAttack(AttackType.PIXIE_DUST, null) }
                    )
                }

                item {
                    AttackOptionCard(
                        title = "Brute Force",
                        description = "Coba semua kombinasi PIN (~11.000 percobaan)",
                        icon = Icons.Filled.Speed,
                        accentColor = Orange400,
                        onClick = { onStartAttack(AttackType.BRUTE_FORCE, null) }
                    )
                }

                // Custom PIN
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Blue400.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Pin,
                                        contentDescription = null,
                                        tint = Blue400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Custom PIN",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnSurfaceDark
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = customPin,
                                onValueChange = {
                                    if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                                        customPin = it
                                    }
                                },
                                label = { Text("PIN (8 digit)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Blue400,
                                    focusedLabelColor = Blue400,
                                    cursorColor = Blue400,
                                    unfocusedBorderColor = OnSurfaceVariantDark.copy(alpha = 0.2f)
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = { onStartAttack(AttackType.CUSTOM_PIN, customPin) },
                                enabled = customPin.length == 8,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Blue400,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Test PIN", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun StateChip(runState: AttackRunState) {
    val (bg, fg, text) = when (runState) {
        AttackRunState.IDLE -> Triple(SurfaceDark, OnSurfaceVariantDark, "idle")
        AttackRunState.PREPARING -> Triple(Blue400.copy(alpha = 0.2f), Blue400, "preparing")
        AttackRunState.RUNNING -> Triple(Cyan400.copy(alpha = 0.2f), Cyan400, "running")
        AttackRunState.SUCCESS -> Triple(GreenAccent.copy(alpha = 0.2f), GreenAccent, "success")
        AttackRunState.FAILED -> Triple(Red400.copy(alpha = 0.2f), Red400, "failed")
        AttackRunState.CANCELLED -> Triple(Orange400.copy(alpha = 0.2f), Orange400, "cancelled")
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}

@Composable
private fun LogFilterRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all", "prepare", "run", "io", "result").forEach { tag ->
            FilterChip(
                selected = selected == tag,
                onClick = { onSelect(tag) },
                label = { Text(tag) }
            )
        }
    }
}

// ─── COMPONENTS ───

@Composable
private fun InfoChip(vararg items: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceDark.copy(alpha = 0.6f)
            ) {
                Text(
                    text = item,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
            }
        }
    }
}

@Composable
private fun AttackOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariantDark.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun AttackProgressSection(
    progress: String,
    pinCount: Int,
    totalPins: Int,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(GreenAccent.copy(alpha = 0.4f), Cyan400.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GreenAccent.copy(alpha = 0.06f),
                        SurfaceCardDark
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated indicator
            val pulse = rememberInfiniteTransition(label = "progress")
            val scale by pulse.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(GreenAccent.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = GreenAccent,
                    strokeWidth = 2.5.dp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Serangan Berlangsung",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = GreenAccent
            )

            if (totalPins > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (totalPins > 0) pinCount.toFloat() / totalPins else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = GreenAccent,
                    trackColor = GreenAccent.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$pinCount / $totalPins PINs",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariantDark.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red400),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = Brush.linearGradient(listOf(Red400.copy(alpha = 0.4f), Red400.copy(alpha = 0.2f)))
                )
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Batalkan", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LogTerminal(logs: List<String>) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0A0F14)
        ),
        modifier = Modifier.border(
            width = 1.dp,
            color = GreenAccent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(14.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Terminal header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Fake window dots
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Red400.copy(alpha = 0.7f)))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Orange400.copy(alpha = 0.7f)))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GreenAccent.copy(alpha = 0.7f)))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "wipwn — attack log",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Log content
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 260.dp)
            ) {
                items(logs) { log ->
                    val color = when {
                        log.startsWith("✓") -> GreenAccent
                        log.startsWith("✗") || log.startsWith("ERROR") || log.contains("FAILED") -> Red400
                        log.startsWith("▶") -> Cyan400
                        log.startsWith("[") -> Blue400.copy(alpha = 0.7f)
                        else -> OnSurfaceVariantDark.copy(alpha = 0.6f)
                    }

                    Text(
                        text = log,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier.padding(vertical = 1.dp),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(result: AttackResult) {
    val isSuccess = result.success
    val bgBrush = if (isSuccess) {
        Brush.horizontalGradient(
            colors = listOf(GreenAccent.copy(alpha = 0.12f), GreenDark.copy(alpha = 0.06f))
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(Red400.copy(alpha = 0.12f), Red400.copy(alpha = 0.04f))
        )
    }
    val accentColor = if (isSuccess) GreenAccent else Red400

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(bgBrush, shape = RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isSuccess) "Berhasil!" else "Gagal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    val errMsg = result.errorMessage
                    if (!isSuccess && errMsg != null) {
                        Text(
                            text = errMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariantDark.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (isSuccess) {
                Spacer(modifier = Modifier.height(14.dp))

                if (result.pin != null) {
                    CredentialRow(label = "WPS PIN", value = result.pin)
                }
                if (result.password != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CredentialRow(label = "Password", value = result.password)
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceVariantDark,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = GreenAccent
        )
    }
}
