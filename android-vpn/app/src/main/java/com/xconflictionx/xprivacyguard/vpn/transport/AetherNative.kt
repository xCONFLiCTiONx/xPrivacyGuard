package com.xconflictionx.xprivacyguard.vpn.transport

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.TimeoutException

/**
 * Thin JNI facade for Aether's upstream JSON C ABI.
 *
 * Async operations return {"job": id}; awaitJob() polls the job and returns its
 * final JSON result. Socket protection must be registered with the active VPN service
 * before any Aether operation that can open an outbound socket.
 */
object AetherNative {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("aether")
        System.loadLibrary("xprivacy_aether_jni")
    }

    external fun nativeRegisterSocketProtector(service: android.net.VpnService?): Boolean
    external fun nativeVersion(): String
    external fun nativeIdentityOpen(payloadJson: String): String
    external fun nativeIdentitySummary(identity: Long): String
    external fun nativeIdentityFree(identity: Long): String
    external fun nativeScanStart(identity: Long, payloadJson: String): String
    external fun nativeVerifyStart(identity: Long, payloadJson: String): String
    external fun nativeTunnelStart(identity: Long, payloadJson: String): String
    external fun nativeJobPoll(job: Long): String
    external fun nativeJobCancel(job: Long): String
    external fun nativeJobFree(job: Long): String
    external fun nativeTeamSignIn(payloadJson: String): String
    external fun nativeTeamCodeRequest(payloadJson: String): String
    external fun nativeTeamCodeResend(session: Long): String
    external fun nativeTeamCodeSubmit(session: Long, code: String): String
    external fun nativeTeamSessionFree(session: Long): String
    external fun nativeTeamTokenSet(token: String): String
    external fun nativeTeamTokenClear(): String
    external fun nativeRawTunnelStart(identity: Long, payloadJson: String): String
    external fun nativeRawTunnelSend(tunnel: Long, packet: ByteArray, length: Int): Int
    external fun nativeRawTunnelRecv(tunnel: Long, outBuf: ByteArray, timeoutMs: Int): Int
    external fun nativeRawTunnelIsAlive(tunnel: Long): Boolean
    external fun nativeRawTunnelMigrate(tunnel: Long): Boolean
    external fun nativeRawTunnelStop(tunnel: Long): String

    /**
     * Register Android's VPN socket-protection callback before starting Aether jobs.
     * Pass null only after all Aether jobs and sockets have stopped.
     */
    fun registerSocketProtector(service: android.net.VpnService) {
        check(nativeRegisterSocketProtector(service)) {
            "Unable to register Android VpnService socket protection"
        }
    }

    fun unregisterSocketProtector() {
        check(nativeRegisterSocketProtector(null)) {
            "Unable to unregister Android VpnService socket protection"
        }
    }

    /**
     * Poll an Aether asynchronous operation until it finishes.
     *
     * The returned JSONObject is the completed operation's result (including
     * its "ok" field). The native job handle is always released.
     */
    suspend fun awaitJob(startReply: String, timeoutMs: Long = 180_000L): JSONObject {
        val start = JSONObject(startReply)
        if (!start.optBoolean("ok", false)) {
            throw IllegalStateException(start.optString("error", "Aether operation could not start"))
        }
        val jobId = start.optLong("job", 0L)
        require(jobId > 0L) { "Aether did not return a valid job handle" }

        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var completed = false
        try {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val poll = JSONObject(nativeJobPoll(jobId))
                if (!poll.optBoolean("ok", false)) {
                    throw IllegalStateException(poll.optString("error", "Aether job polling failed"))
                }
                when (poll.optString("state")) {
                    "running" -> delay(150L)
                    "done" -> {
                        val result = poll.optJSONObject("result")
                            ?: throw IllegalStateException("Aether returned an empty job result")
                        if (!result.optBoolean("ok", false)) {
                            throw IllegalStateException(result.optString("error", "Aether operation failed"))
                        }
                        completed = true
                        return result
                    }
                    else -> throw IllegalStateException("Unknown Aether job state: ${poll.optString("state")}")
                }
            }
            throw TimeoutException("Aether operation timed out after ${timeoutMs}ms")
        } finally {
            // A canceled coroutine, malformed poll response, or timeout must not leave
            // the Rust task running after its handle is released.
            if (!completed) runCatching { nativeJobCancel(jobId) }
            runCatching { nativeJobFree(jobId) }
        }
    }
}
