package com.xconflictionx.xprivacyguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.xconflictionx.xprivacyguard.ui.MainViewModel
import com.xconflictionx.xprivacyguard.ui.XPrivacyGuardApp
import com.xconflictionx.xprivacyguard.vpn.VpnStateManager
import com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var startTunnelAfterVpnGrant: Boolean = true
    private var firstRunStep: Int = 0
    private var isRunningFirstRunFlow: Boolean = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.refreshPermissionStatus()
        if (isRunningFirstRunFlow) {
            advanceFirstRunPermissions()
        } else if (result.resultCode == Activity.RESULT_OK && startTunnelAfterVpnGrant) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissionStatus()
        if (isRunningFirstRunFlow) {
            advanceFirstRunPermissions()
        } else if (startTunnelAfterVpnGrant) {
            prepareAndStartVpn()
        }
    }

    private val settingsActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.refreshPermissionStatus()
        if (isRunningFirstRunFlow) {
            advanceFirstRunPermissions()
        }
    }

    private val importFilterFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFilterFiles(uris)
        }
    }

    private val importWgConfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importWgConfUri(uri)
        }
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportSettingsBackup(uri)
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importSettingsBackup(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#121214")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#121214"))
        )

        setContent {
            XPrivacyGuardApp(
                viewModel = viewModel,
                onToggleVpn = { shouldConnect ->
                    if (shouldConnect) {
                        startTunnelAfterVpnGrant = true
                        requestNotificationAndStartVpn()
                    } else {
                        stopVpnService()
                    }
                },
                onImportFilterFiles = {
                    importFilterFilesLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                },
                onImportWgConfFile = {
                    importWgConfLauncher.launch(arrayOf("*/*"))
                },
                onExportSettingsBackup = {
                    exportBackupLauncher.launch("xPrivacyGuard-backup.json")
                },
                onImportSettingsBackup = {
                    importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                onRequestDisableBatteryOptimization = {
                    openDisableBatteryOptimization()
                },
                onOpenBatteryOptimizationList = {
                    openBatteryOptimizationListSettings()
                },
                onRequestVpnPermissionOnly = {
                    startTunnelAfterVpnGrant = false
                    val prepareIntent = VpnService.prepare(this)
                    if (prepareIntent != null) {
                        vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        viewModel.refreshPermissionStatus()
                    }
                },
                onRequestNotificationPermissionOnly = {
                    startTunnelAfterVpnGrant = false
                    requestOrOpenNotificationPermission()
                },
                onOpenAlwaysOnVpnSettings = {
                    openAlwaysOnVpnSettings()
                },
                onOpenAppDetailsSettings = {
                    openAppDetailsSettings()
                }
            )
        }

        if (!viewModel.hasCompletedFirstRunSetup()) {
            startFirstRunPermissionFlow()
        }
    }

    /**
     * Sequentially requests all required permissions on first startup:
     * 1) Notifications (Android 13+)
     * 2) Battery optimization exemption
     * 3) VPN service consent
     * 4) Always-On VPN prompt (last step, opens Android VPN settings if accepted)
     */
    private fun startFirstRunPermissionFlow() {
        isRunningFirstRunFlow = true
        firstRunStep = 0
        advanceFirstRunPermissions()
    }

    private fun advanceFirstRunPermissions() {
        if (!isRunningFirstRunFlow) return

        while (true) {
            firstRunStep++
            when (firstRunStep) {
                1 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return
                    }
                }
                2 -> {
                    val pm = getSystemService(POWER_SERVICE) as? PowerManager
                    val ignored = pm?.isIgnoringBatteryOptimizations(packageName) ?: true
                    if (!ignored) {
                        openDisableBatteryOptimization()
                        return
                    }
                }
                3 -> {
                    val prepareIntent = runCatching { VpnService.prepare(this) }.getOrNull()
                    if (prepareIntent != null) {
                        startTunnelAfterVpnGrant = false
                        vpnPermissionLauncher.launch(prepareIntent)
                        return
                    }
                }
                else -> {
                    isRunningFirstRunFlow = false
                    viewModel.refreshPermissionStatus()
                    viewModel.requestAlwaysOnPrompt()
                    return
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VpnStateManager.setUiVisible(true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus()
    }

    override fun onStop() {
        VpnStateManager.setUiVisible(false)
        super.onStop()
    }

    private fun requestNotificationAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareAndStartVpn()
        }
    }

    private fun requestOrOpenNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                settingsActivityLauncher.launch(intent)
            }.onFailure {
                openAppDetailsSettings()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun openDisableBatteryOptimization() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            settingsActivityLauncher.launch(intent)
        }.onFailure {
            openBatteryOptimizationListSettings()
        }
    }

    private fun openBatteryOptimizationListSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            settingsActivityLauncher.launch(intent)
        }.onFailure {
            openAppDetailsSettings()
        }
    }

    private fun openAlwaysOnVpnSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_VPN_SETTINGS)
            settingsActivityLauncher.launch(intent)
        }.onFailure {
            runCatching {
                val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                settingsActivityLauncher.launch(fallback)
            }.onFailure {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            settingsActivityLauncher.launch(intent)
        }
    }

    private fun prepareAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, XPrivacyGuardService::class.java).apply {
            action = XPrivacyGuardService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, XPrivacyGuardService::class.java).apply {
            action = XPrivacyGuardService.ACTION_STOP
        }
        startService(intent)
    }
}
