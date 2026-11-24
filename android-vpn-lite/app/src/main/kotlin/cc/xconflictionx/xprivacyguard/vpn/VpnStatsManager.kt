package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.xconflictionx.xprivacyguard.ui.screens.LogEntry
import java.text.SimpleDateFormat
import java.util.*

object VpnStatsManager {
    const val MAX_LOG_ENTRIES = 100
    private const val PREFS_NAME = "vpn_daily_stats_prefs"
    private const val KEY_STATS_DATE = "stats_date"
    private const val KEY_TOTAL_REQUESTS = "total_requests"
    private const val KEY_BLOCKED_REQUESTS = "blocked_requests"
    private const val KEY_TRACKERS_BLOCKED = "trackers_blocked"
    private const val KEY_ADS_BLOCKED = "ads_blocked"

    var totalRequests by mutableStateOf(0)
        private set
    var blockedRequests by mutableStateOf(0)
        private set
    var trackersBlocked by mutableStateOf(0)
        private set
    var adsBlocked by mutableStateOf(0)
        private set

    val logEntries = mutableStateListOf<LogEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var currentStatsDate: String = getTodayDateString()

    @Volatile
    private var isInitialized = false

    private val midnightResetRunnable = object : Runnable {
        override fun run() {
            checkAndResetDailyStats()
            scheduleNextMidnightReset()
        }
    }

    init {
        scheduleNextMidnightReset()
    }

    fun init(context: Context) {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs = sp
                    val today = getTodayDateString()
                    val savedDate = sp.getString(KEY_STATS_DATE, null)
                    val initAction = {
                        if (savedDate == today) {
                            currentStatsDate = today
                            totalRequests = sp.getInt(KEY_TOTAL_REQUESTS, 0)
                            blockedRequests = sp.getInt(KEY_BLOCKED_REQUESTS, 0)
                            trackersBlocked = sp.getInt(KEY_TRACKERS_BLOCKED, 0)
                            adsBlocked = sp.getInt(KEY_ADS_BLOCKED, 0)
                        } else {
                            currentStatsDate = today
                            totalRequests = 0
                            blockedRequests = 0
                            trackersBlocked = 0
                            adsBlocked = 0
                            saveStatsToPrefs()
                        }
                        scheduleNextMidnightReset()
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        initAction()
                    } else {
                        mainHandler.post(initAction)
                    }
                    isInitialized = true
                    return
                }
            }
        }
        checkAndResetDailyStats()
    }

    private fun getTodayDateString(now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

    private fun getMillisUntilNextMidnight(): Long {
        val now = Calendar.getInstance()
        val nextMidnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(1000L)
    }

    private fun scheduleNextMidnightReset() {
        mainHandler.removeCallbacks(midnightResetRunnable)
        mainHandler.postDelayed(midnightResetRunnable, getMillisUntilNextMidnight())
    }

    fun checkAndResetDailyStats() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            checkAndResetDailyStatsOnMain()
        } else {
            mainHandler.post { checkAndResetDailyStatsOnMain() }
        }
    }

    private fun checkAndResetDailyStatsOnMain() {
        val today = getTodayDateString()
        if (today != currentStatsDate) {
            currentStatsDate = today
            totalRequests = 0
            blockedRequests = 0
            trackersBlocked = 0
            adsBlocked = 0
            PrivacyVpnService.tunDnsPacketsSeen = 0L
            saveStatsToPrefs()
        }
    }

    private fun saveStatsToPrefs() {
        prefs?.edit()
            ?.putString(KEY_STATS_DATE, currentStatsDate)
            ?.putInt(KEY_TOTAL_REQUESTS, totalRequests)
            ?.putInt(KEY_BLOCKED_REQUESTS, blockedRequests)
            ?.putInt(KEY_TRACKERS_BLOCKED, trackersBlocked)
            ?.putInt(KEY_ADS_BLOCKED, adsBlocked)
            ?.apply()
    }

    fun recordQuery(appName: String, domain: String, type: String, blocked: Boolean) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = LogEntry(currentTime, appName, domain, type, blocked)

        mainHandler.post {
            checkAndResetDailyStatsOnMain()

            totalRequests++
            if (blocked) {
                blockedRequests++
                if (type.contains("Ad", true)) {
                    adsBlocked++
                } else if (type.contains("Tracker", true)) {
                    trackersBlocked++
                } else {
                    trackersBlocked++
                }
            }
            saveStatsToPrefs()

            logEntries.add(0, newEntry)
            while (logEntries.size > MAX_LOG_ENTRIES) {
                logEntries.removeAt(logEntries.size - 1)
            }
        }
    }

    fun clearLogs() {
        mainHandler.post {
            logEntries.clear()
        }
    }

    fun resetDailyStats() {
        mainHandler.post {
            currentStatsDate = getTodayDateString()
            totalRequests = 0
            blockedRequests = 0
            trackersBlocked = 0
            adsBlocked = 0
            PrivacyVpnService.tunDnsPacketsSeen = 0L
            saveStatsToPrefs()
        }
    }
}
