package cc.xconflictionx.xprivacyguard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.xconflictionx.xprivacyguard.ui.MainViewModel
import cc.xconflictionx.xprivacyguard.vpn.VpnStatsManager

data class LogEntry(val time: String, val appName: String, val domain: String, val type: String, val blocked: Boolean)

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    var selectedFilter by remember { mutableStateOf("All") }

    val recentLogs = viewModel.logEntries.take(VpnStatsManager.MAX_LOG_ENTRIES)
    val blockedCount = recentLogs.count { it.blocked }
    val allowedCount = recentLogs.count { !it.blocked }

    val filteredLogs = remember(selectedFilter, recentLogs) {
        when (selectedFilter) {
            "Blocked" -> recentLogs.filter { it.blocked }
            "Allowed" -> recentLogs.filter { !it.blocked }
            else -> recentLogs
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Connection Logs",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { viewModel.clearLogs() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs")
            }
        }

        Text(
            text = "Real-time inspection log of network & DNS queries (last ${VpnStatsManager.MAX_LOG_ENTRIES}).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedFilter == "All",
                onClick = { selectedFilter = "All" },
                label = { Text("All (${recentLogs.size})") }
            )
            FilterChip(
                selected = selectedFilter == "Blocked",
                onClick = { selectedFilter = "Blocked" },
                label = { Text("Blocked ($blockedCount)") }
            )
            FilterChip(
                selected = selectedFilter == "Allowed",
                onClick = { selectedFilter = "Allowed" },
                label = { Text("Allowed ($allowedCount)") }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No query logs available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs) { log ->
                    LogCard(log = log)
                }
            }
        }
    }
}

@Composable
fun LogCard(log: LogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = log.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = log.type, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = log.domain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (log.blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = if (log.blocked) "BLOCKED" else "ALLOWED",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (log.blocked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
