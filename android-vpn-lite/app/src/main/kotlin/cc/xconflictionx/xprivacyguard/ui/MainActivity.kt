package cc.xconflictionx.xprivacyguard.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.xconflictionx.xprivacyguard.ui.screens.DashboardScreen
import cc.xconflictionx.xprivacyguard.ui.screens.FirewallScreen
import cc.xconflictionx.xprivacyguard.ui.screens.LogsScreen
import cc.xconflictionx.xprivacyguard.ui.screens.SettingsScreen
import cc.xconflictionx.xprivacyguard.ui.theme.PrivacyFirewallTheme
import cc.xconflictionx.xprivacyguard.vpn.PrivacyVpnService
import cc.xconflictionx.xprivacyguard.vpn.VpnConfig

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isPreparingVpn = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isPreparingVpn = false
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpnService(this)
        }
    }

    fun prepareVpn() {
        if (isPreparingVpn) return
        val intent = VpnService.prepare(this)
        if (intent != null) {
            isPreparingVpn = true
            vpnPermissionLauncher.launch(intent)
        } else {
            isPreparingVpn = false
            viewModel.startVpnService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        cc.xconflictionx.xprivacyguard.vpn.VpnStatsManager.checkAndResetDailyStats()
        if (!isPreparingVpn &&
            VpnConfig.isVpnEnabled &&
            !PrivacyVpnService.isOurVpnConnected(this) &&
            !PrivacyVpnService.shouldDeferAutoReconnect(this, recordObservation = false)
        ) {
            prepareVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VpnConfig.init(this)

        // Auto-install and auto-start VPN immediately on app launch unless another VPN is active or connecting
        if (VpnConfig.isVpnEnabled &&
            !PrivacyVpnService.isOurVpnConnected(this) &&
            !PrivacyVpnService.shouldDeferAutoReconnect(this, recordObservation = false)
        ) {
            prepareVpn()
        }

        setContent {
            PrivacyFirewallTheme {
                val navController = rememberNavController()
                val items = listOf(
                    Screen.Dashboard,
                    Screen.Firewall,
                    Screen.Logs,
                    Screen.Settings
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = navBackStackEntry?.destination?.route

                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = {
                                        when (screen) {
                                            Screen.Dashboard -> Icon(Icons.Default.Security, contentDescription = null)
                                            Screen.Firewall -> Icon(Icons.Default.Lock, contentDescription = null)
                                            Screen.Logs -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                            Screen.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
                                        }
                                    },
                                    label = { Text(screen.title) },
                                    selected = currentRoute == screen.route,
                                    onClick = {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Dashboard.route) { DashboardScreen(viewModel) }
                        composable(Screen.Firewall.route) { FirewallScreen(viewModel) }
                        composable(Screen.Logs.route) { LogsScreen(viewModel) }
                        composable(Screen.Settings.route) { SettingsScreen(viewModel) }
                    }
                }
            }
        }
    }
}

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Shield")
    object Firewall : Screen("firewall", "Firewall")
    object Logs : Screen("logs", "Logs")
    object Settings : Screen("settings", "Settings")
}
