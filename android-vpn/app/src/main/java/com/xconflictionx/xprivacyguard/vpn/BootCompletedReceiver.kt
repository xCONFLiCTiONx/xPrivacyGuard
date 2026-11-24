package com.xconflictionx.xprivacyguard.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching {
                val appCtx = context.applicationContext
                VpnStateManager.ensureInitialized(appCtx)
                val currentStatus = VpnStateManager.state.value.status
                if (currentStatus == TunnelStatus.CONNECTED || currentStatus == TunnelStatus.CONNECTING) {
                    (VpnStateManager.activeVpnService as? XPrivacyGuardService)?.syncTunnelIfConfigChanged()
                    return@runCatching
                }
                if (VpnStateManager.hasConfiguredTunnel(appCtx) &&
                    VpnStateManager.isAutoStartOnBootEnabled(appCtx) &&
                    VpnService.prepare(appCtx) == null
                ) {
                    val vpnIntent = Intent(appCtx, XPrivacyGuardService::class.java).apply {
                        this.action = XPrivacyGuardService.ACTION_START
                    }
                    ContextCompat.startForegroundService(appCtx, vpnIntent)
                }
            }
        }
    }
}
