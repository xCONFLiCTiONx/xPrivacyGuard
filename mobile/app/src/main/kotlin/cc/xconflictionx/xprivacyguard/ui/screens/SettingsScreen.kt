package cc.xconflictionx.xprivacyguard.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.xconflictionx.xprivacyguard.ui.MainViewModel
import cc.xconflictionx.xprivacyguard.vpn.VpnConfig

data class DnsPreset(
    val name: String,
    val dotHost: String,
    val primaryIpv4: String,
    val secondaryIpv4: String,
    val primaryIpv6: String,
    val secondaryIpv6: String
)

val dnsPresets = listOf(
    DnsPreset(
        name = "Cloudflare Security (Malware)",
        dotHost = "security.cloudflare-dns.com",
        primaryIpv4 = "1.1.1.2",
        secondaryIpv4 = "1.0.0.2",
        primaryIpv6 = "2606:4700:4700::1112",
        secondaryIpv6 = "2606:4700:4700::1002"
    ),
    DnsPreset(
        name = "AdGuard DNS (Ad & Tracker)",
        dotHost = "dns.adguard-dns.com",
        primaryIpv4 = "94.140.14.14",
        secondaryIpv4 = "94.140.15.15",
        primaryIpv6 = "2a10:50c0::ad1:ff",
        secondaryIpv6 = "2a10:50c0::ad2:ff"
    ),
    DnsPreset(
        name = "Quad9 (Malware & DNSSEC)",
        dotHost = "dns.quad9.net",
        primaryIpv4 = "9.9.9.9",
        secondaryIpv4 = "149.112.112.112",
        primaryIpv6 = "2620:fe::fe",
        secondaryIpv6 = "2620:fe::9"
    ),
    DnsPreset(
        name = "Mullvad (Ad & Tracker)",
        dotHost = "adblock.doh.mullvad.net",
        primaryIpv4 = "194.242.2.3",
        secondaryIpv4 = "194.242.2.4",
        primaryIpv6 = "2a07:e340::3",
        secondaryIpv6 = "2a07:e340::4"
    ),
    DnsPreset(
        name = "Control D (Malware & Ads)",
        dotHost = "p2.freedns.controld.com",
        primaryIpv4 = "76.76.2.2",
        secondaryIpv4 = "76.76.10.2",
        primaryIpv6 = "2606:1a40::2",
        secondaryIpv6 = "2606:1a40:1::2"
    ),
    DnsPreset(
        name = "Cloudflare Standard (1.1.1.1)",
        dotHost = "one.one.one.one",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "1.0.0.1",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2606:4700:4700::1001"
    ),
    DnsPreset(
        name = "Cloudflare Family (Adult Block)",
        dotHost = "family.cloudflare-dns.com",
        primaryIpv4 = "1.1.1.3",
        secondaryIpv4 = "1.0.0.3",
        primaryIpv6 = "2606:4700:4700::1113",
        secondaryIpv6 = "2606:4700:4700::1003"
    ),
    DnsPreset(
        name = "Google DNS",
        dotHost = "dns.google",
        primaryIpv4 = "8.8.8.8",
        secondaryIpv4 = "8.8.4.4",
        primaryIpv6 = "2001:4860:4860::8888",
        secondaryIpv6 = "2001:4860:4860::8844"
    )
)

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    var secureDns by remember { mutableStateOf(VpnConfig.secureDnsEnabled) }
    var adBlock by remember { mutableStateOf(VpnConfig.adBlockingEnabled) }
    var trackerBlock by remember { mutableStateOf(VpnConfig.trackerBlockingEnabled) }
    var malware by remember { mutableStateOf(VpnConfig.malwareProtectionEnabled) }
    var killSwitch by remember { mutableStateOf(VpnConfig.killSwitchEnabled) }
    var dnsEndpoint by remember { mutableStateOf(VpnConfig.dnsEndpoint) }
    var dnsIpv4 by remember { mutableStateOf(VpnConfig.dnsIpv4) }
    var dnsIpv6 by remember { mutableStateOf(VpnConfig.dnsIpv6) }

    var customBlockInput by remember { mutableStateOf("") }
    var customAllowInput by remember { mutableStateOf("") }
    var customFilterUrlInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Protection Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "SECURE DNS & PROTOCOL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }


            item {
                SettingSwitchRow(
                    title = "Secure DNS",
                    subtitle = "Enable secure encrypted DNS over TLS (DoT - Port 853)",
                    checked = secureDns
                ) {
                    secureDns = it
                    viewModel.updateSettings(it, adBlock, trackerBlock, malware, killSwitch)
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "DNS Presets (Dual-Stack IPv4 & IPv6)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dnsPresets.forEach { preset ->
                                FilterChip(
                                    selected = dnsEndpoint == preset.dotHost,
                                    onClick = {
                                        dnsEndpoint = preset.dotHost
                                        dnsIpv4 = "${preset.primaryIpv4}, ${preset.secondaryIpv4}"
                                        dnsIpv6 = "${preset.primaryIpv6}, ${preset.secondaryIpv6}"
                                        viewModel.updateDnsSettings(dnsEndpoint, dnsIpv4, dnsIpv6)
                                    },
                                    label = { Text(preset.name) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Encrypted DNS Gateway (DoT) & Upstream IPs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Enter DoT hostname (e.g. security.cloudflare-dns.com, dns.adguard-dns.com, or 1.1.1.2)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = dnsEndpoint,
                            onValueChange = {
                                dnsEndpoint = it
                                viewModel.updateDnsSettings(dnsEndpoint, dnsIpv4, dnsIpv6)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("DoT Hostname (Port 853)") }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "IPv4 DNS Servers (Primary & Secondary)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = dnsIpv4,
                            onValueChange = {
                                dnsIpv4 = it
                                viewModel.updateDnsSettings(dnsEndpoint, dnsIpv4, dnsIpv6)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("IPv4 DNS (e.g. 94.140.14.14, 94.140.15.15)") }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "IPv6 DNS Servers (Primary & Secondary)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = dnsIpv6,
                            onValueChange = {
                                dnsIpv6 = it
                                viewModel.updateDnsSettings(dnsEndpoint, dnsIpv4, dnsIpv6)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("IPv6 DNS (e.g. 2a10:50c0::ad1:ff, 2a10:50c0::ad2:ff)") }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "BLOCKING & FILTERS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                SettingSwitchRow(
                    title = "Ad Blocking",
                    subtitle = "Block known advertisement domains",
                    checked = adBlock
                ) {
                    adBlock = it
                    viewModel.updateSettings(secureDns, it, trackerBlock, malware, killSwitch)
                }
            }

            item {
                SettingSwitchRow(
                    title = "Tracker Blocking",
                    subtitle = "Prevent analytics and telemetry trackers",
                    checked = trackerBlock
                ) {
                    trackerBlock = it
                    viewModel.updateSettings(secureDns, adBlock, it, malware, killSwitch)
                }
            }

            item {
                SettingSwitchRow(
                    title = "Malware Protection",
                    subtitle = "Block malicious and deceptive sites",
                    checked = malware
                ) {
                    malware = it
                    viewModel.updateSettings(secureDns, adBlock, trackerBlock, it, killSwitch)
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CUSTOM FILTER LISTS (FILTERS.TXT)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom Filter Lists Module",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (VpnConfig.customFilterUrls.isEmpty())
                                        "Disabled — Add a filters.txt URL below to enable"
                                    else
                                        "Filter domains using remote filters.txt lists",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = VpnConfig.customFiltersEnabled,
                                enabled = VpnConfig.customFilterUrls.isNotEmpty(),
                                onCheckedChange = { enabled ->
                                    viewModel.toggleCustomFilters(context, enabled)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Update Filters Daily",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Automatically check & download new rules every 24 hours",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = VpnConfig.customFiltersAutoUpdate,
                                enabled = VpnConfig.customFilterUrls.isNotEmpty() && VpnConfig.customFiltersEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.toggleCustomFiltersAutoUpdate(context, enabled)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Add Filter List URL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Supports Chrome-ready filters.txt, EasyList, uBlock, or Hosts URLs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customFilterUrlInput,
                                onValueChange = { customFilterUrlInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("https://example.com/filters.txt") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    if (customFilterUrlInput.isNotBlank()) {
                                        viewModel.addFilterUrl(context, customFilterUrlInput)
                                        customFilterUrlInput = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Filter URL")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val sampleUrl = "https://gitlab.com/xCONFLiCTiONx/privacyfilters/-/raw/main/xCONFLiCTiONx/filters.txt?ref_type=heads"
                        if (!VpnConfig.customFilterUrls.contains(sampleUrl)) {
                            Button(
                                onClick = {
                                    viewModel.addFilterUrl(context, sampleUrl)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add & Download xCONFLiCTiONx filters.txt", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (VpnConfig.customFilterUrls.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Active Rules: ${VpnConfig.customFiltersRuleCount}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Status: ${VpnConfig.customFiltersStatus}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val lastUpdatedText = remember(VpnConfig.customFiltersLastUpdated) {
                                                if (VpnConfig.customFiltersLastUpdated > 0L) {
                                                    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
                                                    sdf.format(java.util.Date(VpnConfig.customFiltersLastUpdated))
                                                } else {
                                                    "Never"
                                                }
                                            }
                                            Text(
                                                text = "Last Updated: $lastUpdatedText",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (VpnConfig.customFiltersAutoUpdate) {
                                                Text(
                                                    text = "Auto-Update: Daily (every 24h)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        if (VpnConfig.customFiltersIsDownloading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            IconButton(onClick = { viewModel.refreshCustomFilters(context) }) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Sync Filters")
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Configured Filter URLs (${VpnConfig.customFilterUrls.size}):",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            VpnConfig.customFilterUrls.forEach { url ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2
                                    )
                                    IconButton(onClick = { viewModel.removeFilterUrl(context, url) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove URL",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CUSTOM RULES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Custom Blocklist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customBlockInput,
                                onValueChange = { customBlockInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("e.g. tracker.domain.com") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                if (customBlockInput.isNotBlank()) {
                                    VpnConfig.addCustomBlock(context, customBlockInput)
                                    customBlockInput = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                            }
                        }

                        if (VpnConfig.customBlocklist.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            VpnConfig.customBlocklist.forEach { domain ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = domain, style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { VpnConfig.removeCustomBlock(context, domain) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Custom Allowlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customAllowInput,
                                onValueChange = { customAllowInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("e.g. mybank.com") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                if (customAllowInput.isNotBlank()) {
                                    VpnConfig.addCustomAllow(context, customAllowInput)
                                    customAllowInput = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                            }
                        }

                        if (VpnConfig.customAllowlist.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            VpnConfig.customAllowlist.forEach { domain ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = domain, style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { VpnConfig.removeCustomAllow(context, domain) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "SAFETY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Always-On VPN",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Open Android VPN settings to enable Always-on VPN",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = { viewModel.openVpnSettings(context) }) {
                            Text("Open")
                        }
                    }
                }
            }
            item {
                if (false) Text(
                    text = "SAFETY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                SettingSwitchRow(
                    title = "Kill Switch",
                    subtitle = "Block unencrypted DNS fallback if DoT fails",
                    checked = killSwitch
                ) {
                    killSwitch = it
                    viewModel.updateSettings(secureDns, adBlock, trackerBlock, malware, it)
                }
            }
        }
    }
}

@Composable
fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
