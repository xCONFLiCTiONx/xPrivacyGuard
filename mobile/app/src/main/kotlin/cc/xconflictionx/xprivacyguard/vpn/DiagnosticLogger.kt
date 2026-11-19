package cc.xconflictionx.xprivacyguard.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

object DiagnosticLogger {
    private const val MAX_LOGS = 100
    val logs = mutableStateListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        val addEntry = {
            logs.add(0, entry)
            while (logs.size > MAX_LOGS) {
                logs.removeAt(logs.size - 1)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addEntry()
        } else {
            mainHandler.post(addEntry)
        }
    }
}
