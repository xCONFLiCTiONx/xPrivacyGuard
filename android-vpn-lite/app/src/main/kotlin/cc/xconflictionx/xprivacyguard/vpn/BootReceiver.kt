package cc.xconflictionx.xprivacyguard.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Received broadcast $action, checking auto-start...")
            try {
                VpnConfig.init(context)
                if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
                    PrivacyVpnService.clearYieldState(context)
                }
                VpnWatchdog.start(context)
                if (VpnConfig.isVpnEnabled && !PrivacyVpnService.shouldDeferAutoReconnect(context, recordObservation = false)) {
                    Log.i("BootReceiver", "Protection was enabled by user. Starting PrivacyVpnService on boot...")
                    val serviceIntent = Intent(context, PrivacyVpnService::class.java).apply {
                        this.action = PrivacyVpnService.ACTION_AUTO_RECONNECT
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    Log.i("BootReceiver", "Protection is disabled by user or another VPN is connected. Skipping immediate start on boot.")
                }
                DailyFilterUpdateReceiver.schedule(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start VPN on boot", e)
            }
        }
    }
}
