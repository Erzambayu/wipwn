package com.wipwn.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wipwn.app.data.AttackResult
import com.wipwn.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsContent(
    results: List<AttackResult>,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            TopAppBar(
                title = { Text("Riwayat Serangan", fontWeight = FontWeight.Bold) },
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
        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = OnSurfaceVariantDark.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Belum ada riwayat",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurfaceVariantDark
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results.reversed()) { result ->
                    HistoryCard(result = result)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(result: AttackResult) {
    val accentColor = if (result.success) GreenAccent else Red400
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceCardDark
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = result.ssid.ifBlank { result.bssid },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnSurfaceDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateFormat.format(Date(result.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariantDark
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = result.bssid,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariantDark
            )

            if (result.success) {
                Spacer(modifier = Modifier.height(10.dp))
                if (result.pin != null) {
                    Row {
                        Text("PIN: ", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark)
                        Text(
                            result.pin,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = GreenAccent
                        )
                    }
                }
                if (result.password != null) {
                    Row {
                        Text("Password: ", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariantDark)
                        Text(
                            result.password,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = GreenAccent
                        )
                    }
                }
            } else {
                val errMsg = result.errorMessage
                if (errMsg != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Red400.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
