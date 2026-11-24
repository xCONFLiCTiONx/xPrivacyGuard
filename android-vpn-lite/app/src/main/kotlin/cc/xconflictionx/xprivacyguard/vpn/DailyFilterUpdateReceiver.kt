package cc.xconflictionx.xprivacyguard.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DailyFilterUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received filter update broadcast: $action")
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        scope.launch {
            try {
                VpnConfig.init(appContext)
                FilterListManager.checkAutoUpdate(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing scheduled daily filter update", e)
            } finally {
                // Schedule next daily run
                schedule(appContext)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DailyFilterUpdate"
        const val ACTION_DAILY_FILTER_UPDATE = "cc.xconflictionx.xprivacyguard.action.DAILY_FILTER_UPDATE"
        private const val REQUEST_CODE = 4401

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun schedule(context: Context) {
            try {
                val appContext = context.applicationContext
                VpnConfig.init(appContext)
                if (!VpnConfig.customFiltersAutoUpdate) {
                    cancel(appContext)
                    return
                }

                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(appContext, DailyFilterUpdateReceiver::class.java).apply {
                    action = ACTION_DAILY_FILTER_UPDATE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val intervalMillis = TimeUnit.DAYS.toMillis(1) // 24 hours
                val lastUpdated = VpnConfig.customFiltersLastUpdated
                val now = System.currentTimeMillis()

                val nextTriggerMillis = if (lastUpdated > 0L) {
                    maxOf(now + TimeUnit.MINUTES.toMillis(5), lastUpdated + intervalMillis)
                } else {
                    now + intervalMillis
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerMillis,
                        pendingIntent
                    )
                }
                Log.i(TAG, "Scheduled next daily filter update for ${java.util.Date(nextTriggerMillis)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule daily filter update alarm", e)
            }
        }

        fun cancel(context: Context) {
            try {
                val appContext = context.applicationContext
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(appContext, DailyFilterUpdateReceiver::class.java).apply {
                    action = ACTION_DAILY_FILTER_UPDATE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    Log.i(TAG, "Cancelled scheduled daily filter update alarm.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel daily filter update alarm", e)
            }
        }
    }
}

