package cc.xconflictionx.xprivacyguard

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.Build
import cc.xconflictionx.xprivacyguard.vpn.PrivacyVpnService
import cc.xconflictionx.xprivacyguard.vpn.VpnConfig
import cc.xconflictionx.xprivacyguard.vpn.VpnWatchdog

class PrivacyFirewallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        VpnConfig.init(this)
        VpnWatchdog.start(this)
        cc.xconflictionx.xprivacyguard.vpn.DailyFilterUpdateReceiver.schedule(this)

        if (VpnConfig.isVpnEnabled &&
            !PrivacyVpnService.isOurVpnConnected(this) &&
            !PrivacyVpnService.shouldDeferAutoReconnect(this, recordObservation = false) &&
            VpnService.prepare(this) == null
        ) {
            val serviceIntent = Intent(this, PrivacyVpnService::class.java).apply {
                action = PrivacyVpnService.ACTION_AUTO_RECONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    companion object {
        lateinit var instance: PrivacyFirewallApp
            private set
    }
}
