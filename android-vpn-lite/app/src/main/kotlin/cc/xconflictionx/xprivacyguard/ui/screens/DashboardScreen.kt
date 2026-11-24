package cc.xconflictionx.xprivacyguard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.xconflictionx.xprivacyguard.R
import cc.xconflictionx.xprivacyguard.ui.MainViewModel

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val pauseMinutes = viewModel.pauseTimerMinutes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        // Status Card
        val statusColor = when {
            viewModel.isVpnActive -> Color(0xFF22C55E)
            pauseMinutes != null -> Color(0xFFFAAD3F)
            else -> Color(0xFFEF4444)
        }
        val statusContainer = when {
            viewModel.isVpnActive -> Color(0xFF0E2918)
            pauseMinutes != null -> Color(0xFF2B1B0E)
            else -> Color(0xFF331417)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = statusContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (viewModel.isVpnActive) "🛡️" else "🔒",
                        fontSize = 32.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (viewModel.isVpnActive) "PROTECTION ON" else if (pauseMinutes != null) "PAUSED ($pauseMinutes min left)" else "PROTECTION OFF",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (viewModel.isVpnActive) "Local Firewall & Encrypted DNS" else "Tap below to activate device firewall",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.toggleVpn(context, !viewModel.isVpnActive)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isVpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text(text = if (viewModel.isVpnActive) "STOP PROTECTION" else "START PROTECTION")
                }

                if (viewModel.isVpnActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Quick Pause:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.pauseProtection(context, 5) }) { Text("5m") }
                        OutlinedButton(onClick = { viewModel.pauseProtection(context, 15) }) { Text("15m") }
                        OutlinedButton(onClick = { viewModel.pauseProtection(context, 60) }) { Text("1h") }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats Grid (resets daily at midnight)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "Blocked",
                value = "${viewModel.blockedRequests}",
                subtitle = "Trackers & Ads",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Total Queries",
                value = "${viewModel.totalRequests}",
                subtitle = "Processed",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                title = "Trackers",
                value = "${viewModel.trackersBlocked}",
                subtitle = "Blocked",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Ads",
                value = "${viewModel.adsBlocked}",
                subtitle = "Blocked",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun StatCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
