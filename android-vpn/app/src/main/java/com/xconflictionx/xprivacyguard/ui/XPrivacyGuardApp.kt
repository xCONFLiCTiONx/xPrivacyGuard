package com.xconflictionx.xprivacyguard.ui

import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.xconflictionx.xprivacyguard.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xconflictionx.xprivacyguard.config.CloudflareOneConfig
import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.filter.FilterDecision
import com.xconflictionx.xprivacyguard.filter.FilterSource
import com.xconflictionx.xprivacyguard.firewall.FirewallManager
import com.xconflictionx.xprivacyguard.firewall.InstalledAppInfo
import com.xconflictionx.xprivacyguard.security.DeviceSecurityReport
import com.xconflictionx.xprivacyguard.security.XSecuritySyncResult
import com.xconflictionx.xprivacyguard.vpn.TunnelStatus
import com.xconflictionx.xprivacyguard.vpn.VpnRuntimeState
import com.xconflictionx.xprivacyguard.vpn.transport.TunnelProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val appIconCache = LruCache<String, ImageBitmap>(256)

// Antigravity Dark Gray + Cloudflare Orange Design System
private val CfOrange = Color(0xFFF38020)
private val CfOrangeBright = Color(0xFFFAAD3F)
private val CfOrangeContainer = Color(0xFF2B1B0E)
private val AgBackground = Color(0xFF121214)
private val AgSurface = Color(0xFF1A1A1E)
private val AgSurfaceElevated = Color(0xFF222227)
private val AgBorder = Color(0xFF2C2C33)
private val AgTextPrimary = Color(0xFFF4F4F5)
private val AgTextSecondary = Color(0xFFA1A1AA)
private val AgTextMuted = Color(0xFF71717A)
private val AgSuccess = Color(0xFF22C55E)
private val AgSuccessContainer = Color(0xFF0E2918)
private val AgError = Color(0xFFEF4444)
private val AgErrorContainer = Color(0xFF331417)
private val AgDrawerBg = Color(0xFF151518)

private val ZeroTrustColorScheme = darkColorScheme(
    primary = CfOrange,
    onPrimary = Color(0xFF121214),
    primaryContainer = CfOrangeContainer,
    onPrimaryContainer = CfOrangeBright,
    secondary = CfOrangeBright,
    onSecondary = Color(0xFF121214),
    background = AgBackground,
    onBackground = AgTextPrimary,
    surface = AgSurface,
    onSurface = AgTextPrimary,
    surfaceVariant = AgSurfaceElevated,
    onSurfaceVariant = AgTextSecondary,
    outline = AgBorder,
    error = AgError,
    onError = Color.White
)

private enum class AppDestination(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Shield),
    STATS("Stats", Icons.Default.BarChart),
    TUNNEL("Tunnel", Icons.Default.SettingsEthernet),
    APPS("Apps", Icons.Default.Apps),
    SECURITY("Security", Icons.Default.Lock),
    FILTERS("Filters", Icons.Default.FilterList),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun XPrivacyGuardApp(
    viewModel: MainViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onImportFilterFiles: () -> Unit,
    onImportWgConfFile: () -> Unit,
    onExportSettingsBackup: () -> Unit,
    onImportSettingsBackup: () -> Unit,
    onRequestDisableBatteryOptimization: () -> Unit,
    onOpenBatteryOptimizationList: () -> Unit,
    onRequestVpnPermissionOnly: () -> Unit,
    onRequestNotificationPermissionOnly: () -> Unit,
    onOpenAlwaysOnVpnSettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val filterSources by viewModel.filterSources.collectAsState()
    val wgConfText by viewModel.wgConfText.collectAsState()
    val ztDnsDraft by viewModel.ztDnsDraft.collectAsState()
    val cfOneDraft by viewModel.cfOneDraft.collectAsState()
    val cfOtpSessionId by viewModel.cfOtpSessionId.collectAsState()
    val cfAuthBusy by viewModel.cfAuthBusy.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()
    val isSyncing by viewModel.isSyncingFilters.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val xSecurityDomain by viewModel.xSecurityDomain.collectAsState()
    val xSecurityApiKey by viewModel.xSecurityApiKey.collectAsState()
    val deviceSecurityReport by viewModel.deviceSecurityReport.collectAsState()
    val xSecuritySyncResult by viewModel.xSecuritySyncResult.collectAsState()
    val isSyncingSecurity by viewModel.isSyncingSecurity.collectAsState()
    val showAlwaysOnPrompt by viewModel.showAlwaysOnPrompt.collectAsState()

    var currentDest by remember { mutableStateOf(AppDestination.HOME) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = ZeroTrustColorScheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = AgDrawerBg,
                    drawerContentColor = AgTextPrimary,
                    modifier = Modifier.width(292.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                    ) {
                        // Futuristic Drawer Brand Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AgSurface)
                                    .border(1.dp, CfOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_shield),
                                    contentDescription = "xPrivacyGuard",
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "xPrivacyGuard",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AgTextPrimary,
                                    letterSpacing = 0.4.sp
                                )
                                Text(
                                    text = "ZERO TRUST",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CfOrange,
                                    letterSpacing = 2.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        // Subtle live status indicator inside drawer
                        val statusDotColor = when (vpnState.status) {
                            TunnelStatus.CONNECTED -> AgSuccess
                            TunnelStatus.CONNECTING -> CfOrangeBright
                            TunnelStatus.ERROR -> AgError
                            TunnelStatus.DISCONNECTED -> AgError
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AgSurface)
                                .border(1.dp, AgBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when (vpnState.status) {
                                    TunnelStatus.CONNECTED -> "Connected"
                                    TunnelStatus.CONNECTING -> "Connecting…"
                                    TunnelStatus.ERROR -> "Attention Needed"
                                    TunnelStatus.DISCONNECTED -> "Disconnected"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = AgTextPrimary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (vpnState.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE) "MASQUE" else "WG",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CfOrange,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = AgBorder)
                        Spacer(modifier = Modifier.height(12.dp))

                        AppDestination.entries.forEach { dest ->
                            val selected = currentDest == dest
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = dest.title,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.title,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                selected = selected,
                                onClick = {
                                    if (dest == AppDestination.SECURITY) {
                                        viewModel.refreshDeviceSecurityReport()
                                    }
                                    currentDest = dest
                                    scope.launch { drawerState.close() }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = CfOrangeContainer,
                                    selectedIconColor = CfOrange,
                                    selectedTextColor = AgTextPrimary,
                                    unselectedContainerColor = Color.Transparent,
                                    unselectedIconColor = AgTextSecondary,
                                    unselectedTextColor = AgTextSecondary
                                ),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = AgBackground,
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Menu",
                                tint = AgTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        if (currentDest == AppDestination.HOME) {
                            Text(
                                text = "xSERVER ZERO TRUST",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AgTextSecondary,
                                letterSpacing = 2.2.sp
                            )
                        } else {
                            Text(
                                text = currentDest.title.uppercase(Locale.ROOT),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AgTextPrimary,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (banner != null) {
                        Surface(
                            color = CfOrangeContainer,
                            border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { viewModel.dismissBanner() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = banner.orEmpty(),
                                    color = AgTextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Dismiss",
                                    color = CfOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    when (currentDest) {
                        AppDestination.HOME -> HomeScreen(
                            state = vpnState,
                            onToggleConnect = {
                                val isActive = vpnState.status == TunnelStatus.CONNECTED ||
                                    vpnState.status == TunnelStatus.CONNECTING
                                if (!isActive && !viewModel.hasValidActiveTransportConfig()) {
                                    val msg = if (vpnState.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE) {
                                        "Sign in to Cloudflare One in Tunnel settings first."
                                    } else {
                                        "Import or paste your WireGuard .conf file first."
                                    }
                                    viewModel.showBanner(msg)
                                    currentDest = AppDestination.TUNNEL
                                } else {
                                    onToggleVpn(!isActive)
                                }
                            },
                            onOpenTunnel = { currentDest = AppDestination.TUNNEL }
                        )
                        AppDestination.STATS -> StatsScreen(
                            state = vpnState,
                            onOpenTunnel = { currentDest = AppDestination.TUNNEL },
                            onOpenApps = { currentDest = AppDestination.APPS },
                            onOpenSettings = { currentDest = AppDestination.SETTINGS }
                        )
                        AppDestination.TUNNEL -> WireGuardAndDnsScreen(
                            activeTransport = vpnState.activeTransport,
                            cfOneDraft = cfOneDraft,
                            activeCfOne = vpnState.cloudflareOneConfig,
                            cfOtpSessionId = cfOtpSessionId,
                            cfAuthBusy = cfAuthBusy,
                            onSelectTransport = { viewModel.selectTransport(it) },
                            onCfOneDraftChange = { viewModel.updateCfOneDraft(it) },
                            onSaveCfOneSettings = { viewModel.saveCfOneSettings() },
                            onRequestCfOtp = { viewModel.requestCloudflareOtpCode() },
                            onResendCfOtp = { viewModel.resendCloudflareOtpCode() },
                            onSubmitCfOtp = { viewModel.submitCloudflareOtpCode(it) },
                            onLogoutCfOne = { viewModel.logoutCloudflareOne() },
                            confText = wgConfText,
                            ztDnsDraft = ztDnsDraft,
                            activeZtDns = vpnState.zeroTrustDns,
                            onConfTextChange = { viewModel.updateWgConfDraft(it) },
                            onSaveConf = { viewModel.saveWgConf(wgConfText) },
                            onImportConfFile = onImportWgConfFile,
                            onClearConf = { viewModel.clearWgConf() },
                            onZtDnsChange = { viewModel.updateZtDnsDraft(it) },
                            onSaveZtDns = { viewModel.saveZtDnsConfig() },
                            onResetZtDns = { viewModel.resetZtDnsToSystemDefault() },
                            onClearDnsCache = { viewModel.clearDnsCache() },
                            onLoadDefaultStaticWarp = { viewModel.loadDefaultStaticWarpConfig() },
                            onExportConfigFile = { viewModel.exportConfigFile() }
                        )
                        AppDestination.APPS -> FirewallScreen(
                            state = vpnState,
                            installedApps = installedApps,
                            isLoadingApps = isLoadingApps,
                            onRefreshApps = { viewModel.refreshInstalledApps() },
                            onToggleAppBypass = { app, shouldBypass -> viewModel.toggleAppBypass(app, shouldBypass) },
                            onClearAllBypassed = { viewModel.clearAllBypassedApps() }
                        )
                        AppDestination.SECURITY -> SecurityScreen(
                            report = deviceSecurityReport,
                            syncResult = xSecuritySyncResult,
                            domain = xSecurityDomain,
                            hasApiKey = xSecurityApiKey.isNotBlank(),
                            isSyncing = isSyncingSecurity,
                            onRefreshChecks = { viewModel.refreshDeviceSecurityReport() },
                            onSyncToXServer = { viewModel.syncDeviceSecurityToXServer() },
                            onGoToSettings = { currentDest = AppDestination.SETTINGS }
                        )
                        AppDestination.FILTERS -> FiltersScreen(
                            state = vpnState,
                            sources = filterSources,
                            isSyncing = isSyncing,
                            onImportFiles = onImportFilterFiles,
                            onAddUrls = { urlsText -> viewModel.addRemoteFilterUrls(urlsText) },
                            onRefreshRemote = { viewModel.refreshAllRemoteFilters() },
                            onAddCustomRule = { rule -> viewModel.addCustomRule(rule) },
                            onToggleSource = { id, enabled -> viewModel.toggleFilterSource(id, enabled) },
                            onDeleteSource = { id -> viewModel.deleteFilterSource(id) },
                            onTestQuery = { q -> viewModel.testFilterQuery(q) }
                        )
                        AppDestination.SETTINGS -> SettingsScreen(
                            state = vpnState,
                            permissionStatus = permissionStatus,
                            xSecurityDomain = xSecurityDomain,
                            xSecurityApiKey = xSecurityApiKey,
                            onSaveXSecuritySettings = { dom, key -> viewModel.saveXSecuritySettings(dom, key) },
                            onExportSettingsBackup = onExportSettingsBackup,
                            onImportSettingsBackup = onImportSettingsBackup,
                            onClearLogs = { viewModel.clearTrafficLogs() },
                            onChangeFilterSettings = { dns, deep, nx, boot ->
                                viewModel.setFilterSettings(dns, deep, nx, boot)
                            },
                            onRequestDisableBatteryOptimization = onRequestDisableBatteryOptimization,
                            onOpenBatteryOptimizationList = onOpenBatteryOptimizationList,
                            onRequestVpnPermissionOnly = onRequestVpnPermissionOnly,
                            onRequestNotificationPermissionOnly = onRequestNotificationPermissionOnly,
                            onOpenAlwaysOnVpnSettings = onOpenAlwaysOnVpnSettings,
                            onOpenAppDetailsSettings = onOpenAppDetailsSettings
                        )
                    }
                }
            }
        }

        // First-Run Final Step: Always-On VPN Prompt
        if (showAlwaysOnPrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissAlwaysOnPrompt() },
                containerColor = AgSurface,
                titleContentColor = AgTextPrimary,
                textContentColor = AgTextSecondary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.VpnLock,
                        contentDescription = null,
                        tint = CfOrange,
                        modifier = Modifier.size(30.dp)
                    )
                },
                title = {
                    Text(
                        text = "Enable Always-On VPN?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "To keep xPrivacyGuard Zero Trust connected automatically across Wi-Fi/5G switches and phone restarts, open Android VPN Settings, tap the gear icon next to xPrivacyGuard, and turn on Always-on VPN.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.dismissAlwaysOnPrompt()
                            onOpenAlwaysOnVpnSettings()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CfOrange,
                            contentColor = AgBackground
                        )
                    ) {
                        Text("Open VPN Settings", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissAlwaysOnPrompt() }) {
                        Text("Not Now", color = AgTextSecondary)
                    }
                }
            )
        }
    }
}

/**
 * Clean, futuristic xServer Zero Trust Home Screen — zero cards, zero clutter.
 */
@Composable
private fun HomeScreen(
    state: VpnRuntimeState,
    onToggleConnect: () -> Unit,
    onOpenTunnel: () -> Unit
) {
    val isConnected = state.status == TunnelStatus.CONNECTED
    val isConnecting = state.status == TunnelStatus.CONNECTING
    val isError = state.status == TunnelStatus.ERROR

    val accentColor by animateColorAsState(
        targetValue = when (state.status) {
            TunnelStatus.CONNECTED -> AgSuccess
            TunnelStatus.CONNECTING -> CfOrangeBright
            TunnelStatus.ERROR -> AgError
            TunnelStatus.DISCONNECTED -> AgError
        },
        animationSpec = tween(durationMillis = 450),
        label = "homeAccent"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnecting) 900 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isConnecting) 900 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val transportLabel = if (state.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE) {
        "Cloudflare MASQUE"
    } else {
        "WireGuard"
    }

    val cleanSubtitle = when (state.status) {
        TunnelStatus.CONNECTED -> "$transportLabel • Encrypted"
        TunnelStatus.CONNECTING -> state.statusMessage.ifBlank { "Establishing tunnel…" }
        TunnelStatus.ERROR -> state.statusMessage.ifBlank { "Connection error" }
        TunnelStatus.DISCONNECTED -> "Tap to secure your connection"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AgBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Futuristic Centerpiece Orb + Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(260.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggleConnect() }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension * 0.34f

                        // Ambient radial Cloudflare orange glow when active
                        if (isConnected || isConnecting || isError) {
                            val glowRadius = size.minDimension * 0.50f * pulseScale
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = glowAlpha),
                                        accentColor.copy(alpha = glowAlpha * 0.25f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = glowRadius
                                ),
                                radius = glowRadius,
                                center = center
                            )
                        }

                        // Outer orbital ring
                        drawCircle(
                            color = if (isConnected || isConnecting) {
                                accentColor.copy(alpha = 0.22f)
                            } else {
                                AgBorder.copy(alpha = 0.7f)
                            },
                            radius = baseRadius * 1.32f,
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        // Mid orbital accent ring
                        drawCircle(
                            color = if (isConnected || isConnecting) {
                                accentColor.copy(alpha = 0.45f)
                            } else {
                                AgBorder
                            },
                            radius = baseRadius * 1.15f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // Core orb surface
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    if (isConnected) Color(0xFF281B11) else AgSurfaceElevated,
                                    AgSurface
                                ),
                                center = center,
                                radius = baseRadius
                            ),
                            radius = baseRadius,
                            center = center
                        )

                        // Crisp glowing core border
                        drawCircle(
                            color = accentColor,
                            radius = baseRadius,
                            center = center,
                            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Toggle Zero Trust VPN",
                        tint = if (isConnected || isConnecting || isError) accentColor else AgTextPrimary,
                        modifier = Modifier.size(62.dp)
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = when (state.status) {
                        TunnelStatus.CONNECTED -> "CONNECTED"
                        TunnelStatus.CONNECTING -> "CONNECTING"
                        TunnelStatus.ERROR -> "DISCONNECTED"
                        TunnelStatus.DISCONNECTED -> "DISCONNECTED"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) CfOrange else AgTextPrimary,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = cleanSubtitle,
                    fontSize = 13.sp,
                    color = if (isError) AgError else AgTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Minimal futuristic protocol indicator pill at bottom
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AgSurface)
                    .border(1.dp, AgBorder, RoundedCornerShape(999.dp))
                    .clickable { onOpenTunnel() }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) CfOrange else AgTextMuted)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = transportLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AgTextSecondary
                )
                if (state.isAlwaysOnActive) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "• ALWAYS-ON",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CfOrange,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

/**
 * Dedicated Stats & Telemetry Screen (moved off the Home screen).
 */
@Composable
private fun StatsScreen(
    state: VpnRuntimeState,
    onOpenTunnel: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Tunnel Telemetry & Stats",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AgTextPrimary
        )
        Text(
            text = "Live connection metrics, routing details, and traffic counters.",
            fontSize = 12.sp,
            color = AgTextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "Download",
                value = "↓ ${formatBytes(state.rxBytes)}",
                subtitle = "Received via tunnel",
                accent = CfOrange
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "Upload",
                value = "↑ ${formatBytes(state.txBytes)}",
                subtitle = "Sent via tunnel",
                accent = CfOrangeBright
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "DNS Queries",
                value = (state.allowedCount + state.blockedCount).toString(),
                subtitle = "${state.allowedCount} allowed • ${state.blockedCount} blocked",
                accent = AgTextPrimary
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "Protocol",
                value = if (state.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE) "MASQUE" else "WireGuard",
                subtitle = if (state.handshakeEstablished) "Session Verified" else state.status.name,
                accent = CfOrange
            )
        }

        AgCard(modifier = Modifier.fillMaxWidth().clickable { onOpenTunnel() }) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Endpoint & Interface",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = AgTextPrimary
                )
                StatDetailRow("Endpoint", state.endpoint)
                StatDetailRow("Assigned IPs", state.tunnelAddresses)
                StatDetailRow("DNS Mode", state.tunnelDns)
                StatDetailRow("Always-On VPN", if (state.isAlwaysOnActive) "Active" else "Not Detected")
            }
        }

        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Adaptive Tunnel Recovery",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = AgTextPrimary
                    )
                    StatusBadge(ok = true, okText = "ACTIVE", warnText = "IDLE")
                }
                Text(
                    text = if (state.isScreenOn) {
                        "Screen On • Warm keepalive (${state.dynamicKeepaliveSec}s) & active NAT watchdog."
                    } else {
                        "Screen Off • Low-power idle with instant wake on screen-on or network change."
                    },
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )
            }
        }

        AgCard(modifier = Modifier.fillMaxWidth().clickable { onOpenApps() }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tunneled Apps",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = AgTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.effectiveBypassedPackages.isEmpty()) {
                            "Only xPrivacyGuard & xAssistant routed through tunnel"
                        } else {
                            "${state.bypassedPackages.size} selected (${state.effectiveBypassedPackages.size + 1} total packages)"
                        },
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(
                    ok = state.effectiveBypassedPackages.isNotEmpty(),
                    okText = "${state.effectiveBypassedPackages.size + 1} APPS",
                    warnText = "DEFAULT"
                )
            }
        }

        OutlinedButton(
            onClick = onOpenSettings,
            border = BorderStroke(1.dp, AgBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Activity Logs & Settings", color = AgTextPrimary)
        }
    }
}

@Composable
private fun StatDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = AgTextSecondary)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = AgTextPrimary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FirewallScreen(
    state: VpnRuntimeState,
    installedApps: List<InstalledAppInfo>,
    isLoadingApps: Boolean,
    onRefreshApps: () -> Unit,
    onToggleAppBypass: (InstalledAppInfo, Boolean) -> Unit,
    onClearAllBypassed: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableIntStateOf(0) }
    var showSystemApps by remember { mutableStateOf(false) }

    val userApps = remember(installedApps, showSystemApps) {
        if (showSystemApps) {
            installedApps
        } else {
            installedApps.filter { FirewallManager.isUserApp(it) || it.hasLauncherIcon }
        }
    }

    val tunneledAppsCount = remember(userApps, state.effectiveBypassedPackages) {
        userApps.count {
            state.effectiveBypassedPackages.contains(it.packageName) ||
                it.packageName == "com.xconflictionx.xprivacyguard" ||
                FirewallManager.ALWAYS_TUNNELED_PACKAGES.contains(it.packageName)
        }
    }
    val bypassedAppsCount = (userApps.size - tunneledAppsCount).coerceAtLeast(0)

    val filteredApps = remember(userApps, searchQuery, filterMode, state.effectiveBypassedPackages) {
        userApps.filter { app ->
            val isTunneled = state.effectiveBypassedPackages.contains(app.packageName) ||
                app.packageName == "com.xconflictionx.xprivacyguard" ||
                FirewallManager.ALWAYS_TUNNELED_PACKAGES.contains(app.packageName)
            val modeMatches = when (filterMode) {
                1 -> isTunneled
                2 -> !isTunneled
                else -> true
            }
            modeMatches && FirewallManager.matchesSearchQuery(app, searchQuery)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "App Tunnel Routing",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgTextPrimary
                    )
                    Text(
                        text = "${userApps.size} apps • $tunneledAppsCount tunneled • $bypassedAppsCount bypassed",
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                }
                if (state.bypassedPackages.isNotEmpty()) {
                    TextButton(onClick = onClearAllBypassed) {
                        Text("Clear", fontSize = 12.sp, color = CfOrange)
                    }
                }
                IconButton(onClick = onRefreshApps, enabled = !isLoadingApps) {
                    if (isLoadingApps) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CfOrange)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh installed apps",
                            tint = CfOrange
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    label = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgFilterChip(selected = filterMode == 0, onClick = { filterMode = 0 }, label = "All (${userApps.size})")
                    AgFilterChip(selected = filterMode == 1, onClick = { filterMode = 1 }, label = "Tunneled ($tunneledAppsCount)")
                    AgFilterChip(selected = filterMode == 2, onClick = { filterMode = 2 }, label = "Bypassed ($bypassedAppsCount)")
                    AgFilterChip(selected = showSystemApps, onClick = { showSystemApps = !showSystemApps }, label = "System")
                }
            }
        }

        if (isLoadingApps && userApps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CfOrange)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Scanning installed apps…",
                            color = AgTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else if (filteredApps.isEmpty()) {
            item {
                AgCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "No matching apps found for '${searchQuery.trim()}'."
                        } else {
                            "No apps in this filter."
                        },
                        color = AgTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(filteredApps, key = { it.packageName }) { app ->
                val isAlwaysTunneled = app.packageName == "com.xconflictionx.xprivacyguard" ||
                    FirewallManager.ALWAYS_TUNNELED_PACKAGES.contains(app.packageName)
                val isDirectlySelected = state.bypassedPackages.contains(app.packageName) || isAlwaysTunneled
                val isEffectivelyBypassed = state.effectiveBypassedPackages.contains(app.packageName) || isAlwaysTunneled

                AppFirewallItemCard(
                    app = app,
                    isDirectlySelected = isDirectlySelected,
                    isEffectivelyBypassed = isEffectivelyBypassed,
                    onToggleBypass = { shouldBypass -> onToggleAppBypass(app, shouldBypass) }
                )
            }
        }
    }
}

@Composable
private fun AppFirewallItemCard(
    app: InstalledAppInfo,
    isDirectlySelected: Boolean,
    isEffectivelyBypassed: Boolean,
    onToggleBypass: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val iconBitmap by produceState<ImageBitmap?>(
        initialValue = appIconCache.get(app.packageName),
        key1 = app.packageName
    ) {
        if (value == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(app.packageName)
                    drawable.toBitmap(width = 96, height = 96).asImageBitmap()
                }.getOrNull()
            }
            if (loaded != null) {
                appIconCache.put(app.packageName, loaded)
                value = loaded
            }
        }
    }

    val isOwnApp = app.packageName == "com.xconflictionx.xprivacyguard"
    val isSwitchChecked = isOwnApp || isEffectivelyBypassed

    AgCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isOwnApp) { onToggleBypass(!isSwitchChecked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap!!,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AgSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = CfOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = app.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    color = AgTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val statusText = when {
                    isOwnApp -> "ALWAYS TUNNELED"
                    isDirectlySelected -> "TUNNELED"
                    isEffectivelyBypassed -> "AUTO-INCLUDED"
                    else -> "BYPASSED"
                }
                StatusBadge(
                    ok = isEffectivelyBypassed,
                    okText = statusText,
                    warnText = statusText
                )
            }

            Switch(
                checked = isSwitchChecked,
                onCheckedChange = { onToggleBypass(it) },
                enabled = !isOwnApp,
                colors = agSwitchColors()
            )
        }
    }
}

@Composable
private fun WireGuardAndDnsScreen(
    activeTransport: TunnelProtocol,
    cfOneDraft: CloudflareOneConfig,
    activeCfOne: CloudflareOneConfig,
    cfOtpSessionId: Long,
    cfAuthBusy: Boolean,
    onSelectTransport: (TunnelProtocol) -> Unit,
    onCfOneDraftChange: (CloudflareOneConfig) -> Unit,
    onSaveCfOneSettings: () -> Unit,
    onRequestCfOtp: () -> Unit,
    onResendCfOtp: () -> Unit,
    onSubmitCfOtp: (String) -> Unit,
    onLogoutCfOne: () -> Unit,
    confText: String,
    ztDnsDraft: ZeroTrustDnsConfig,
    activeZtDns: ZeroTrustDnsConfig,
    onConfTextChange: (String) -> Unit,
    onSaveConf: () -> Unit,
    onImportConfFile: () -> Unit,
    onClearConf: () -> Unit,
    onZtDnsChange: (ZeroTrustDnsConfig) -> Unit,
    onSaveZtDns: () -> Unit,
    onResetZtDns: () -> Unit,
    onClearDnsCache: () -> Unit,
    onLoadDefaultStaticWarp: () -> Unit = {},
    onExportConfigFile: () -> Unit = {}
) {
    val parsedResult = remember(confText) { WireGuardConfig.parse(confText) }
    val parsed = parsedResult.getOrNull()
    var otpCodeDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 0: VPN Transport Selector
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SettingsEthernet, contentDescription = null, tint = CfOrange, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("VPN Transport Protocol", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AgTextPrimary)
                }
                Text(
                    "Choose Cloudflare One MASQUE (HTTP/3 QUIC CONNECT-IP) or WireGuard. Both preserve Zero Trust DNS and per-app routing.",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AgFilterChip(
                        selected = activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE,
                        onClick = { onSelectTransport(TunnelProtocol.CLOUDFLARE_MASQUE) },
                        label = "MASQUE",
                        modifier = Modifier.weight(1f)
                    )
                    AgFilterChip(
                        selected = activeTransport == TunnelProtocol.WIREGUARD,
                        onClick = { onSelectTransport(TunnelProtocol.WIREGUARD) },
                        label = "WireGuard",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Section 0b: Cloudflare One Zero Trust Authentication & MASQUE Config
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = CfOrange, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cloudflare One (MASQUE)", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AgTextPrimary)
                    Spacer(modifier = Modifier.weight(1f))
                    StatusBadge(
                        ok = activeCfOne.canConnect,
                        okText = if (activeCfOne.isEnrolled) "ENROLLED" else "SIGNED IN",
                        warnText = "SIGN IN"
                    )
                }

                if (activeCfOne.isEnrolled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CfOrangeContainer)
                            .border(1.dp, CfOrange.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "Device Enrolled • Org: ${activeCfOne.organization.ifBlank { activeCfOne.normalizedTeam }}",
                                color = CfOrangeBright,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "ID: ${activeCfOne.deviceId.take(18)} • ${activeCfOne.displayAddresses}",
                                fontSize = 11.sp,
                                color = AgTextPrimary
                            )
                            Text(
                                text = "Edge: ${activeCfOne.displayEndpoint}",
                                fontSize = 11.sp,
                                color = AgTextSecondary
                            )
                        }
                    }
                } else if (activeCfOne.hasValidToken) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CfOrangeContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Cloudflare Access token active for team '${activeCfOne.normalizedTeam}'. Connect on Home to complete MASQUE enrollment.",
                            color = CfOrangeBright,
                            fontSize = 12.sp
                        )
                    }
                } else if (activeCfOne.accessToken.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AgErrorContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Access token expired. Request a new email verification code below to refresh.",
                            color = Color(0xFFF87171),
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = cfOneDraft.teamName,
                    onValueChange = { onCfOneDraftChange(cfOneDraft.copy(teamName = it)) },
                    label = { Text("Zero Trust Team Name") },
                    placeholder = { Text("my-team (or my-team.cloudflareaccess.com)") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cfOneDraft.userEmail,
                    onValueChange = { onCfOneDraftChange(cfOneDraft.copy(userEmail = it)) },
                    label = { Text("User Email (One-Time Code)") },
                    placeholder = { Text("you@example.com") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRequestCfOtp,
                        enabled = !cfAuthBusy,
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (cfAuthBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgBackground)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(if (cfOtpSessionId > 0L) "New Code" else "Send Login Code")
                    }
                    if (cfOtpSessionId > 0L) {
                        OutlinedButton(
                            onClick = onResendCfOtp,
                            enabled = !cfAuthBusy,
                            border = BorderStroke(1.dp, AgBorder)
                        ) {
                            Text("Resend", color = AgTextPrimary)
                        }
                    }
                }

                if (cfOtpSessionId > 0L) {
                    OutlinedTextField(
                        value = otpCodeDraft,
                        onValueChange = { otpCodeDraft = it },
                        label = { Text("One-Time Verification Code") },
                        placeholder = { Text("Enter 6-digit code from email") },
                        singleLine = true,
                        colors = agTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onSubmitCfOtp(otpCodeDraft)
                            otpCodeDraft = ""
                        },
                        enabled = !cfAuthBusy && otpCodeDraft.isNotBlank(),
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verify & Sign In")
                    }
                }

                OutlinedTextField(
                    value = cfOneDraft.customPeerEndpoint,
                    onValueChange = { onCfOneDraftChange(cfOneDraft.copy(customPeerEndpoint = it)) },
                    label = { Text("Custom MASQUE Edge (Optional)") },
                    placeholder = { Text("Auto-discover if blank (e.g. 162.159.198.1:443)") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                SettingToggleRow(
                    title = "HTTP/2 TCP Fallback",
                    subtitle = "Use MASQUE over HTTP/2 TCP instead of HTTP/3 QUIC UDP",
                    checked = cfOneDraft.preferHttp2,
                    onCheckedChange = { onCfOneDraftChange(cfOneDraft.copy(preferHttp2 = it)) }
                )

                SettingToggleRow(
                    title = "Encrypted Client Hello (ECH)",
                    subtitle = "Encrypt TLS SNI during MASQUE handshake",
                    checked = cfOneDraft.enableEch,
                    onCheckedChange = { onCfOneDraftChange(cfOneDraft.copy(enableEch = it)) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSaveCfOneSettings,
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                    if (activeCfOne.canConnect || activeCfOne.accessToken.isNotBlank()) {
                        OutlinedButton(onClick = onLogoutCfOne, border = BorderStroke(1.dp, AgBorder)) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = AgError)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Logout", color = AgTextPrimary)
                        }
                    }
                }
            }
        }

        // Section 1: Private split DNS
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = CfOrange, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Private Network DNS", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AgTextPrimary)
                    Spacer(modifier = Modifier.weight(1f))
                    StatusBadge(ok = activeZtDns.isPrivateDnsConfigured, okText = "SPLIT DNS", warnText = "DEFAULT")
                }
                OutlinedTextField(
                    value = ztDnsDraft.privateDomain,
                    onValueChange = { onZtDnsChange(ztDnsDraft.copy(privateDomain = it)) },
                    label = { Text("Private DNS domain") },
                    placeholder = { Text("home.example.com") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ztDnsDraft.privateDnsServer,
                    onValueChange = { onZtDnsChange(ztDnsDraft.copy(privateDnsServer = it)) },
                    label = { Text("Private DNS server IP") },
                    placeholder = { Text("DNS server reachable through tunnel") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Your tunnel must route the private DNS server's network. A full-tunnel 0.0.0.0/0 route already includes it.",
                    fontSize = 11.sp,
                    color = AgTextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveZtDns, colors = agPrimaryButtonColors()) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                    OutlinedButton(onClick = onClearDnsCache, border = BorderStroke(1.dp, AgBorder)) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear DNS Cache", color = AgTextPrimary)
                }
                }
            }
        }

        // Section 2: WireGuard Configuration (.conf)
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "WireGuard Configuration (.conf)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgTextPrimary
                )
                Text(
                    text = "Import your WireGuard .conf file from your device or paste it below. Stored locally on your phone only.",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onImportConfFile, colors = agPrimaryButtonColors()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import .conf")
                    }
                    OutlinedButton(
                        onClick = onSaveConf,
                        enabled = confText.isNotBlank(),
                        border = BorderStroke(1.dp, AgBorder)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save", color = AgTextPrimary)
                    }
                    if (confText.isNotBlank()) {
                        OutlinedButton(onClick = onClearConf, border = BorderStroke(1.dp, AgBorder)) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = AgError)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear", color = AgTextPrimary)
                        }
                    }
                    OutlinedButton(onClick = onExportConfigFile, border = BorderStroke(1.dp, AgBorder)) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export", color = AgTextPrimary)
                    }
                }

                if (parsed != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CfOrangeContainer)
                            .border(1.dp, CfOrange.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "WireGuard Config Valid • Endpoint: ${parsed.endpoint}",
                                color = CfOrangeBright,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Addresses: ${parsed.addresses.joinToString(", ")} • MTU: ${parsed.mtu}",
                                fontSize = 11.sp,
                                color = AgTextPrimary
                            )
                        }
                    }
                } else if (confText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AgErrorContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Config Error: ${parsedResult.exceptionOrNull()?.message}",
                            color = Color(0xFFF87171),
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = confText,
                    onValueChange = onConfTextChange,
                    label = { Text("WireGuard .conf") },
                    placeholder = {
                        Text(
                            "[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = ...\n\n[Peer]\nPublicKey = ...\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = ...",
                            fontSize = 12.sp
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = AgTextPrimary
                    ),
                    colors = agTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: VpnRuntimeState,
    permissionStatus: SystemPermissionStatus,
    xSecurityDomain: String,
    xSecurityApiKey: String,
    onSaveXSecuritySettings: (String, String) -> Unit,
    onExportSettingsBackup: () -> Unit,
    onImportSettingsBackup: () -> Unit,
    onClearLogs: () -> Unit,
    onChangeFilterSettings: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onRequestDisableBatteryOptimization: () -> Unit,
    onOpenBatteryOptimizationList: () -> Unit,
    onRequestVpnPermissionOnly: () -> Unit,
    onRequestNotificationPermissionOnly: () -> Unit,
    onOpenAlwaysOnVpnSettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    var domainDraft by remember(xSecurityDomain) { mutableStateOf(xSecurityDomain) }
    var apiKeyDraft by remember(xSecurityApiKey) { mutableStateOf(xSecurityApiKey) }
    var logFilterMode by remember { mutableIntStateOf(0) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val filteredLogs = remember(state.recentLogs, logFilterMode) {
        when (logFilterMode) {
            1 -> state.recentLogs.filter { it.blocked }
            2 -> state.recentLogs.filter { !it.blocked }
            else -> state.recentLogs
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Settings & System",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AgTextPrimary
        )
        Text(
            text = "Configure Always-On VPN, startup auto-connect, battery optimization, and permissions.",
            fontSize = 12.sp,
            color = AgTextSecondary
        )

        // 1. Always-On VPN & Startup Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VpnLock,
                            contentDescription = null,
                            tint = CfOrange,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Always-On VPN",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = AgTextPrimary
                        )
                    }
                    StatusBadge(
                        ok = state.isAlwaysOnActive,
                        okText = "ACTIVE",
                        warnText = "SYSTEM SETTING"
                    )
                }

                Text(
                    text = "Enable Always-On VPN in Android Settings so xServer Zero Trust stays connected automatically across network switches and reboots.",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )

                Button(
                    onClick = onOpenAlwaysOnVpnSettings,
                    colors = agPrimaryButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Phone VPN Settings")
                }

                HorizontalDivider(color = AgBorder)

                SettingToggleRow(
                    title = "Auto-Connect on Phone Boot / Update",
                    subtitle = "Automatically start xServer Zero Trust when your phone restarts",
                    checked = state.autoStartOnBoot,
                    onCheckedChange = {
                        onChangeFilterSettings(
                            state.dnsFilteringEnabled,
                            state.deepInspectionEnabled,
                            state.useNxDomain,
                            it
                        )
                    }
                )
            }
        }

        // 2. Battery Optimization Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (permissionStatus.batteryOptimizationIgnored) CfOrange else CfOrangeBright,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Battery Optimization",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = AgTextPrimary
                        )
                    }

                    StatusBadge(
                        ok = permissionStatus.batteryOptimizationIgnored,
                        okText = "UNRESTRICTED",
                        warnText = "RESTRICTED"
                    )
                }

                Text(
                    text = if (permissionStatus.batteryOptimizationIgnored) {
                        "Battery optimization is disabled. Android will not put the Zero Trust tunnel to sleep."
                    } else {
                        "Disable battery optimization so Android Doze mode does not interrupt your tunnel."
                    },
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRequestDisableBatteryOptimization,
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (permissionStatus.batteryOptimizationIgnored) "Verify Unrestricted"
                            else "Disable Optimization"
                        )
                    }
                    OutlinedButton(onClick = onOpenBatteryOptimizationList, border = BorderStroke(1.dp, AgBorder)) {
                        Text("All Apps", color = AgTextPrimary)
                    }
                }
            }
        }

        // 3. Required Permissions Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Required App Permissions",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AgTextPrimary
                )

                PermissionItemRow(
                    icon = Icons.Default.VpnKey,
                    title = "VPN Service Tunnel Permission",
                    subtitle = "Allows xServer Zero Trust to create the local TUN interface",
                    granted = permissionStatus.vpnPermissionGranted,
                    actionText = if (permissionStatus.vpnPermissionGranted) "Granted" else "Grant VPN",
                    onAction = onRequestVpnPermissionOnly
                )

                PermissionItemRow(
                    icon = Icons.Default.Notifications,
                    title = "Foreground Service Notification",
                    subtitle = "Keeps the VPN service alive and shows tunnel status",
                    granted = permissionStatus.notificationPermissionGranted,
                    actionText = if (permissionStatus.notificationPermissionGranted) "Settings" else "Grant",
                    onAction = onRequestNotificationPermissionOnly
                )

                OutlinedButton(
                    onClick = onOpenAppDetailsSettings,
                    border = BorderStroke(1.dp, AgBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Android App Info", color = AgTextPrimary)
                }
            }
        }

        // 4. Local Inspection & DNS Engine Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Local DNS & Inspection Engine",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AgTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                SettingToggleRow(
                    title = "DNS Sinkhole (UDP Port 53)",
                    subtitle = "Applies local backup filter rules if lists are enabled",
                    checked = state.dnsFilteringEnabled,
                    onCheckedChange = {
                        onChangeFilterSettings(
                            it,
                            state.deepInspectionEnabled,
                            state.useNxDomain,
                            state.autoStartOnBoot
                        )
                    }
                )

                SettingToggleRow(
                    title = "TLS SNI & HTTP Path Inspection",
                    subtitle = "Enforces path rules on ports 80/443 when local filters are loaded",
                    checked = state.deepInspectionEnabled,
                    onCheckedChange = {
                        onChangeFilterSettings(
                            state.dnsFilteringEnabled,
                            it,
                            state.useNxDomain,
                            state.autoStartOnBoot
                        )
                    }
                )

                SettingToggleRow(
                    title = "Return NXDOMAIN instead of 0.0.0.0 / ::",
                    subtitle = "When disabled, returns 0.0.0.0 (A) and :: (AAAA) sinkhole responses",
                    checked = state.useNxDomain,
                    onCheckedChange = {
                        onChangeFilterSettings(
                            state.dnsFilteringEnabled,
                            state.deepInspectionEnabled,
                            it,
                            state.autoStartOnBoot
                        )
                    }
                )
            }
        }

        // 5. xSecurity (xServer) Connection Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = CfOrange,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "xSecurity Server Connection",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = AgTextPrimary
                    )
                }
                Text(
                    text = "Set your xSecurity domain name (not IP) and an API key from your xServer /keys/ page.",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )
                OutlinedTextField(
                    value = domainDraft,
                    onValueChange = { domainDraft = it },
                    label = { Text("xSecurity Domain Name") },
                    placeholder = { Text("security.home.example.com") },
                    singleLine = true,
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("xServer API Key") },
                    placeholder = { Text("xsk_...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = agTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onSaveXSecuritySettings(domainDraft, apiKeyDraft) },
                    colors = agPrimaryButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save xSecurity Settings")
                }
            }
        }

        // 6. Backup & Restore Settings Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = CfOrange,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Backup & Restore",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = AgTextPrimary
                    )
                }
                Text(
                    text = "Export or import your tunnel config, routed apps, DNS, local filter lists, and xSecurity settings as JSON.",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportSettingsBackup,
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = onImportSettingsBackup,
                        border = BorderStroke(1.dp, AgBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import", color = AgTextPrimary)
                    }
                }
            }
        }

        // 7. Terminal-style Activity Logs Card
        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Activity Log",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = AgTextPrimary
                        )
                        Text(
                            text = "${state.blockedCount} blocked • ${state.allowedCount} allowed",
                            fontSize = 11.sp,
                            color = AgTextSecondary
                        )
                    }
                    OutlinedButton(onClick = onClearLogs, border = BorderStroke(1.dp, AgBorder)) {
                        Text("Clear", fontSize = 12.sp, color = AgTextPrimary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgFilterChip(selected = logFilterMode == 0, onClick = { logFilterMode = 0 }, label = "All (${state.recentLogs.size})")
                    AgFilterChip(selected = logFilterMode == 1, onClick = { logFilterMode = 1 }, label = "Blocked")
                    AgFilterChip(selected = logFilterMode == 2, onClick = { logFilterMode = 2 }, label = "Allowed")
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0C0C0E))
                        .border(1.dp, AgBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Text(
                            text = "$ waiting for traffic logs...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = AgTextMuted
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredLogs.take(120), key = { it.id }) { entry ->
                                val ts = timeFormatter.format(Date(entry.timestampMs))
                                val tag = if (entry.blocked) "BLK" else "OK "
                                val color = if (entry.blocked) Color(0xFFF87171) else CfOrangeBright
                                val ruleSuffix = if (entry.blocked && !entry.matchedRule.isNullOrBlank()) " [${entry.matchedRule}]" else ""
                                Text(
                                    text = "[$ts] $tag ${entry.protocol} ${entry.target}$ruleSuffix",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }

        val context = LocalContext.current
        val versionName = remember(context) {
            runCatching {
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                pkgInfo.versionName ?: "Unknown"
            }.getOrDefault("Unknown")
        }

        Text(
            text = "xPrivacyGuard Zero Trust • v$versionName",
            fontSize = 12.sp,
            color = AgTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun FiltersScreen(
    state: VpnRuntimeState,
    sources: List<FilterSource>,
    isSyncing: Boolean,
    onImportFiles: () -> Unit,
    onAddUrls: (String) -> Unit,
    onRefreshRemote: () -> Unit,
    onAddCustomRule: (String) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onDeleteSource: (String) -> Unit,
    onTestQuery: (String) -> FilterDecision
) {
    var urlsInput by remember { mutableStateOf("") }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var inspectSource by remember { mutableStateOf<FilterSource?>(null) }

    var testInput by remember { mutableStateOf("") }
    var testResult by remember(testInput, state.filterStats) {
        mutableStateOf(onTestQuery(testInput))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Local Backup Filters",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AgTextPrimary
            )
            Text(
                text = "Optional on-device filtering backup (${sources.size} list(s) • ${state.filterStats.totalRules} rules).",
                fontSize = 12.sp,
                color = AgTextSecondary
            )
        }

        item {
            AgCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Add Filter List URLs",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = AgTextPrimary
                    )
                    Text(
                        text = "Paste one or multiple filters.txt URLs (one per line).",
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = urlsInput,
                        onValueChange = { urlsInput = it },
                        placeholder = {
                            Text(
                                "https://.../filters.txt\nhttps://.../more-filters.txt",
                                fontSize = 12.sp
                            )
                        },
                        minLines = 3,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = AgTextPrimary
                        ),
                        colors = agTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (urlsInput.isNotBlank()) {
                                    val toSubmit = urlsInput
                                    urlsInput = ""
                                    onAddUrls(toSubmit)
                                }
                            },
                            enabled = !isSyncing && urlsInput.isNotBlank(),
                            colors = agPrimaryButtonColors()
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AgBackground
                                )
                            } else {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add URL(s)")
                        }

                        OutlinedButton(onClick = onImportFiles, border = BorderStroke(1.dp, AgBorder)) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import File(s)", color = AgTextPrimary)
                        }

                        OutlinedButton(
                            onClick = onRefreshRemote,
                            enabled = !isSyncing && sources.isNotEmpty(),
                            border = BorderStroke(1.dp, AgBorder)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync All", color = AgTextPrimary)
                        }

                        OutlinedButton(onClick = { showAddRuleDialog = true }, border = BorderStroke(1.dp, AgBorder)) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = CfOrange)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Custom Rule", color = AgTextPrimary)
                        }
                    }
                }
            }
        }

        item {
            AgCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Rule Tester",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = AgTextPrimary
                    )
                    Text(
                        text = "Test any domain or URL path against loaded backup lists:",
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = testInput,
                        onValueChange = {
                            testInput = it
                            testResult = onTestQuery(it)
                        },
                        singleLine = true,
                        label = { Text("Enter domain or URL to test") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        colors = agTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (testInput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val badgeColor = if (testResult.blocked) AgErrorContainer else CfOrangeContainer
                        val textColor = if (testResult.blocked) Color(0xFFF87171) else CfOrangeBright
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeColor)
                                .padding(10.dp)
                        ) {
                            if (testResult.blocked) {
                                Text(
                                    text = "BLOCKED by '${testResult.matchedRule}' (${testResult.sourceName})",
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "ALLOWED • No blocking rule matched '${testInput.trim()}'",
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            item {
                AgCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No local filter lists configured. Add URLs or local files above if you want on-device backup filtering.",
                        color = AgTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(sources, key = { it.id }) { source ->
            AgCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { inspectSource = source }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AgTextPrimary
                        )
                        Text(
                            text = source.urlOrPath,
                            fontSize = 11.sp,
                            color = CfOrangeBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${source.ruleCount} rules • Tap to inspect",
                            fontSize = 12.sp,
                            color = AgTextSecondary
                        )
                    }

                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { onToggleSource(source.id, it) },
                        colors = agSwitchColors()
                    )

                    IconButton(onClick = { onDeleteSource(source.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete filter list",
                            tint = AgError
                        )
                    }
                }
            }
        }
    }

    if (showAddRuleDialog) {
        var ruleInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            containerColor = AgSurface,
            titleContentColor = AgTextPrimary,
            textContentColor = AgTextSecondary,
            title = { Text("Add Custom Filter Rule(s)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Supports ||domain.com^, @@||allow.com^, */path.js, or 0.0.0.0 domain.com (one per line):",
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                    OutlinedTextField(
                        value = ruleInput,
                        onValueChange = { ruleInput = it },
                        placeholder = { Text("||ads.example.com^") },
                        minLines = 2,
                        maxLines = 6,
                        colors = agTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (ruleInput.isNotBlank()) {
                            onAddCustomRule(ruleInput)
                            showAddRuleDialog = false
                        }
                    },
                    colors = agPrimaryButtonColors()
                ) {
                    Text("Add Rule(s)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text("Cancel", color = AgTextSecondary)
                }
            }
        )
    }

    inspectSource?.let { src ->
        AlertDialog(
            onDismissRequest = { inspectSource = null },
            containerColor = AgSurface,
            titleContentColor = AgTextPrimary,
            textContentColor = AgTextSecondary,
            title = { Text(src.name) },
            text = {
                val previewLines = remember(src.content) {
                    src.content.lines().take(250).joinToString("\n")
                }
                Box(
                    modifier = Modifier
                        .height(340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = previewLines,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AgTextPrimary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectSource = null }) {
                    Text("Close", color = CfOrange)
                }
            }
        )
    }
}

@Composable
private fun SecurityScreen(
    report: DeviceSecurityReport,
    syncResult: XSecuritySyncResult?,
    domain: String,
    hasApiKey: Boolean,
    isSyncing: Boolean,
    onRefreshChecks: () -> Unit,
    onSyncToXServer: () -> Unit,
    onGoToSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device Security",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgTextPrimary
                )
                Text(
                    text = "${report.deviceModel} • Android ${report.androidVersion}",
                    fontSize = 12.sp,
                    color = AgTextSecondary
                )
            }
            IconButton(onClick = onRefreshChecks) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Checks", tint = CfOrange)
            }
        }

        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Device Posture",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AgTextPrimary
                )
                SecurityCheckRow("Security Patch Level", report.securityPatch, true, report.securityPatch, "Unknown")
                SecurityCheckRow("Screen Lock & Encryption", "KeyguardManager secure lock", report.deviceSecure, "Secured", "Not Set")
                if (report.autoBlockerEnabled != null) {
                    val abSubtitle = if (report.autoBlockerMaxRestrictions == true) {
                        "Samsung One UI (Maximum restrictions ON)"
                    } else {
                        "Samsung One UI sideload & USB guard"
                    }
                    SecurityCheckRow("Samsung Auto Blocker", abSubtitle, report.autoBlockerEnabled, "Enabled", "Disabled")
                }
                SecurityCheckRow("ADB Debugging", "USB / Wireless debugging", !report.adbEnabled, "Disabled", "Enabled")
                SecurityCheckRow("Developer Options", "Android developer mode", !report.developerOptionsEnabled, "Off", "On")
                SecurityCheckRow("Zero Trust Tunnel", "Encrypted tunnel status", report.vpnConnected, "Connected", "Disconnected")
                SecurityCheckRow("Verified Build Tags", report.verifiedBootTags, report.verifiedBootTags.contains("release-keys"), "Release", report.verifiedBootTags)
            }
        }

        AgCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (domain.isNotBlank()) "xServer Sync ($domain)" else "xServer Sync",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AgTextPrimary
                )
                if (syncResult != null) {
                    Text(
                        text = if (syncResult.ok) {
                            "${syncResult.message} • Posture: ${syncResult.postureScore ?: "--"}/100 (${syncResult.openAlerts ?: 0} open alerts)"
                        } else {
                            syncResult.message
                        },
                        fontSize = 12.sp,
                        color = if (syncResult.ok) AgSuccess else AgError
                    )
                } else {
                    Text(
                        text = if (hasApiKey) "Ready to report device posture to xSecurity." else "Configure your xSecurity domain and API key in Settings.",
                        fontSize = 12.sp,
                        color = AgTextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onSyncToXServer,
                        enabled = !isSyncing,
                        colors = agPrimaryButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AgBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isSyncing) "Syncing…" else "Sync to xSecurity")
                    }
                    if (!hasApiKey) {
                        OutlinedButton(onClick = onGoToSettings, border = BorderStroke(1.dp, AgBorder)) {
                            Text("Set API Key", color = AgTextPrimary)
                        }
                    }
                }
            }
        }
    }
}

// Shared Antigravity Dark Gray + Cloudflare Orange Components

@Composable
private fun AgCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AgSurface),
        border = BorderStroke(1.dp, AgBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        content()
    }
}

@Composable
private fun agPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = CfOrange,
    contentColor = AgBackground,
    disabledContainerColor = CfOrange.copy(alpha = 0.35f),
    disabledContentColor = AgBackground.copy(alpha = 0.6f)
)

@Composable
private fun agSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AgBackground,
    checkedTrackColor = CfOrange,
    uncheckedThumbColor = AgTextSecondary,
    uncheckedTrackColor = AgSurfaceElevated,
    uncheckedBorderColor = AgBorder
)

@Composable
private fun agTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CfOrange,
    unfocusedBorderColor = AgBorder,
    focusedLabelColor = CfOrange,
    unfocusedLabelColor = AgTextSecondary,
    cursorColor = CfOrange,
    focusedTextColor = AgTextPrimary,
    unfocusedTextColor = AgTextPrimary
)

@Composable
private fun AgFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CfOrangeContainer,
            selectedLabelColor = CfOrangeBright,
            containerColor = AgSurface,
            labelColor = AgTextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = AgBorder,
            selectedBorderColor = CfOrange.copy(alpha = 0.7f)
        ),
        modifier = modifier
    )
}

@Composable
private fun PermissionItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) AgSuccess else AgError,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AgTextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = AgTextSecondary)
        }
        if (granted) {
            OutlinedButton(onClick = onAction, border = BorderStroke(1.dp, AgBorder)) {
                Text(actionText, fontSize = 12.sp, color = AgTextPrimary)
            }
        } else {
            Button(onClick = onAction, colors = agPrimaryButtonColors()) {
                Text(actionText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusBadge(ok: Boolean, okText: String, warnText: String) {
    val bg = if (ok) AgSuccessContainer else AgErrorContainer
    val fg = if (ok) AgSuccess else AgError
    val borderColor = if (ok) AgSuccess.copy(alpha = 0.45f) else AgError.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (ok) okText else warnText,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    accent: Color
) {
    AgCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, fontSize = 12.sp, color = AgTextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = AgTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AgTextPrimary)
            Text(text = subtitle, fontSize = 12.sp, color = AgTextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = agSwitchColors())
    }
}

@Composable
private fun SecurityCheckRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    okText: String,
    warnText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AgTextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = AgTextSecondary)
        }
        StatusBadge(ok = ok, okText = okText, warnText = warnText)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
