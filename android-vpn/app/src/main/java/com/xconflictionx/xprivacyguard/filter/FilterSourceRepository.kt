package com.xconflictionx.xprivacyguard.filter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsResolver
import com.xconflictionx.xprivacyguard.vpn.VpnStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class FilterSourceType {
    REMOTE_URL,
    LOCAL_FILE,
    CUSTOM_RULES
}

data class FilterSource(
    val id: String,
    val name: String,
    val type: FilterSourceType,
    val urlOrPath: String,
    val enabled: Boolean,
    val ruleCount: Int,
    val lastUpdatedMs: Long,
    val content: String
)

data class BatchUrlImportResult(
    val addedOrUpdated: List<FilterSource>,
    val errors: List<String>
)

class FilterSourceRepository(private val context: Context) {
    private val storageCtx: Context
        get() = VpnStateManager.deviceProtectedContext(context)
    private val prefs
        get() = storageCtx.getSharedPreferences("xprivacy_filters_prefs", Context.MODE_PRIVATE)
    private val filtersDir: File
        get() = File(storageCtx.filesDir, "filters").apply { mkdirs() }

    companion object {
        private const val KEY_SOURCES_JSON = "filter_sources_v2"
        private const val CUSTOM_SOURCE_ID = "custom_user_rules"

        fun normalizeFilterUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.contains("gitlab.com/") && trimmed.contains("/-/blob/")) {
                return trimmed.replace("/-/blob/", "/-/raw/")
            }
            if (trimmed.contains("github.com/") && trimmed.contains("/blob/")) {
                return trimmed
                    .replace("https://github.com/", "https://raw.githubusercontent.com/")
                    .replace("/blob/", "/")
            }
            return trimmed
        }

        fun countActiveRulesInText(content: String): Int {
            return content.lineSequence().count { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith('!') && !t.startsWith('#') && !t.startsWith('[')
            }
        }

        fun extractTitleOrNull(content: String): String? {
            content.lineSequence().take(25).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("! Title:", ignoreCase = true) ||
                    trimmed.startsWith("# Title:", ignoreCase = true)
                ) {
                    val title = trimmed.substringAfter(":").trim()
                    if (title.isNotEmpty()) return title
                }
            }
            return null
        }

        fun deriveReadableNameFromUrl(urlStr: String): String {
            return runCatching {
                val uri = URI(urlStr)
                val host = uri.host ?: "Remote"
                val segments = (uri.path ?: "").split('/').filter {
                    it.isNotBlank() && it != "-" && it != "raw" && it != "blob" && it != "main" && it != "master"
                }
                if (segments.size >= 2) {
                    val ownerOrFolder = segments[segments.size - 2]
                    val file = segments.last()
                    "$ownerOrFolder / $file"
                } else if (segments.isNotEmpty()) {
                    "$host / ${segments.last()}"
                } else {
                    host
                }
            }.getOrDefault(urlStr.substringAfterLast('/').ifEmpty { "filters.txt" })
        }
    }

    fun loadSources(): List<FilterSource> {
        val rawJson = prefs.getString(KEY_SOURCES_JSON, null)
        if (rawJson.isNullOrBlank()) {
            return emptyList()
        }

        val list = mutableListOf<FilterSource>()
        runCatching {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val file = File(filtersDir, "$id.txt")
                val content = if (file.exists()) file.readText() else ""
                val rawType = obj.optString("type", FilterSourceType.REMOTE_URL.name)
                val mappedType = runCatching { FilterSourceType.valueOf(rawType) }
                    .getOrDefault(FilterSourceType.REMOTE_URL)

                list.add(
                    FilterSource(
                        id = id,
                        name = obj.getString("name"),
                        type = mappedType,
                        urlOrPath = obj.optString("urlOrPath", ""),
                        enabled = obj.optBoolean("enabled", true),
                        ruleCount = countActiveRulesInText(content),
                        lastUpdatedMs = obj.optLong("lastUpdatedMs", System.currentTimeMillis()),
                        content = content
                    )
                )
            }
        }
        return list
    }

    fun saveSources(sources: List<FilterSource>) {
        val array = JSONArray()
        for (source in sources) {
            File(filtersDir, "${source.id}.txt").writeText(source.content)
            val obj = JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("type", source.type.name)
                put("urlOrPath", source.urlOrPath)
                put("enabled", source.enabled)
                put("ruleCount", countActiveRulesInText(source.content))
                put("lastUpdatedMs", source.lastUpdatedMs)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SOURCES_JSON, array.toString()).apply()
    }

    fun importLocalUri(uri: Uri): Result<FilterSource> = runCatching {
        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Unable to read selected filter file")
        val displayName = resolveFileName(uri) ?: "filters.txt"
        val title = extractTitleOrNull(content) ?: displayName
        val id = "local_${UUID.randomUUID()}"
        val newSource = FilterSource(
            id = id,
            name = title,
            type = FilterSourceType.LOCAL_FILE,
            urlOrPath = displayName,
            enabled = true,
            ruleCount = countActiveRulesInText(content),
            lastUpdatedMs = System.currentTimeMillis(),
            content = content
        )
        val updated = loadSources().toMutableList().apply { add(newSource) }
        saveSources(updated)
        newSource
    }

    suspend fun addRemoteUrls(
        rawUrlsInput: String,
        customNameIfSingle: String? = null
    ): BatchUrlImportResult = withContext(Dispatchers.IO) {
        val urls = rawUrlsInput
            .split('\n', '\r', ',', ' ', '\t')
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()

        if (urls.isEmpty()) {
            return@withContext BatchUrlImportResult(
                addedOrUpdated = emptyList(),
                errors = listOf("Please paste at least one valid http:// or https:// URL")
            )
        }

        val current = loadSources().toMutableList()
        val imported = mutableListOf<FilterSource>()
        val errors = mutableListOf<String>()

        for (rawUrl in urls) {
            val normalizedUrl = normalizeFilterUrl(rawUrl)
            runCatching {
                val content = downloadUrlText(normalizedUrl)
                val count = countActiveRulesInText(content)
                require(count > 0) { "No valid filter rules found at $normalizedUrl" }

                val resolvedName = (if (urls.size == 1) customNameIfSingle?.takeIf { it.isNotBlank() } else null)
                    ?: extractTitleOrNull(content)
                    ?: deriveReadableNameFromUrl(normalizedUrl)

                val existingIndex = current.indexOfFirst {
                    it.type == FilterSourceType.REMOTE_URL &&
                        normalizeFilterUrl(it.urlOrPath).equals(normalizedUrl, ignoreCase = true)
                }

                val source = if (existingIndex >= 0) {
                    val prev = current[existingIndex]
                    prev.copy(
                        name = resolvedName,
                        urlOrPath = normalizedUrl,
                        enabled = true,
                        ruleCount = count,
                        lastUpdatedMs = System.currentTimeMillis(),
                        content = content
                    ).also { current[existingIndex] = it }
                } else {
                    FilterSource(
                        id = "remote_${UUID.randomUUID()}",
                        name = resolvedName,
                        type = FilterSourceType.REMOTE_URL,
                        urlOrPath = normalizedUrl,
                        enabled = true,
                        ruleCount = count,
                        lastUpdatedMs = System.currentTimeMillis(),
                        content = content
                    ).also { current.add(it) }
                }
                imported.add(source)
            }.onFailure { err ->
                errors.add("$rawUrl: ${err.message}")
            }
        }

        if (imported.isNotEmpty()) {
            saveSources(current)
        }
        BatchUrlImportResult(addedOrUpdated = imported, errors = errors)
    }

    suspend fun refreshRemoteSources(): Result<List<FilterSource>> = withContext(Dispatchers.IO) {
        runCatching {
            val current = loadSources()
            val updated = current.map { source ->
                if (source.type == FilterSourceType.REMOTE_URL && source.urlOrPath.startsWith("http")) {
                    val fetched = runCatching { downloadUrlText(normalizeFilterUrl(source.urlOrPath)) }.getOrNull()
                    if (!fetched.isNullOrBlank() && countActiveRulesInText(fetched) > 0) {
                        source.copy(
                            name = extractTitleOrNull(fetched) ?: source.name,
                            content = fetched,
                            ruleCount = countActiveRulesInText(fetched),
                            lastUpdatedMs = System.currentTimeMillis()
                        )
                    } else {
                        source
                    }
                } else {
                    source
                }
            }
            saveSources(updated)
            updated
        }
    }

    fun addCustomRule(ruleLine: String): List<FilterSource> {
        val cleaned = ruleLine.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (cleaned.isEmpty()) return loadSources()
        val current = loadSources().toMutableList()
        val existingIdx = current.indexOfFirst { it.id == CUSTOM_SOURCE_ID }
        if (existingIdx >= 0) {
            val existing = current[existingIdx]
            val mergedContent = buildString {
                append(existing.content.trimEnd())
                if (existing.content.isNotBlank()) appendLine()
                appendLine(cleaned)
            }
            current[existingIdx] = existing.copy(
                content = mergedContent,
                ruleCount = countActiveRulesInText(mergedContent),
                lastUpdatedMs = System.currentTimeMillis(),
                enabled = true
            )
        } else {
            val content = "! Custom User Rules\n$cleaned\n"
            current.add(
                FilterSource(
                    id = CUSTOM_SOURCE_ID,
                    name = "Custom User Rules",
                    type = FilterSourceType.CUSTOM_RULES,
                    urlOrPath = "local://custom-rules",
                    enabled = true,
                    ruleCount = countActiveRulesInText(content),
                    lastUpdatedMs = System.currentTimeMillis(),
                    content = content
                )
            )
        }
        saveSources(current)
        return current
    }

    fun toggleSource(id: String, enabled: Boolean): List<FilterSource> {
        val updated = loadSources().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveSources(updated)
        return updated
    }

    fun deleteSource(id: String): List<FilterSource> {
        File(filtersDir, "$id.txt").delete()
        val updated = loadSources().filterNot { it.id == id }
        saveSources(updated)
        return updated
    }

    private fun downloadUrlText(urlStr: String): String {
        val ztDns = VpnStateManager.state.value.zeroTrustDns
        // 1. If custom Zero Trust DNS is not required, try standard HttpURLConnection first
        if (!ztDns.isCustomConfigured) {
            val directAttempt = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "xPrivacyGuard-Android/1.1")
                }
                try {
                    val code = conn.responseCode
                    require(code in 200..299) { "HTTP $code" }
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
            if (directAttempt.isSuccess) {
                return directAttempt.getOrThrow()
            }
        }

        // 2. Resolve hostname via ZeroTrustDnsResolver (supports DoH, DoT, IPv4, IPv6, and System DNS)
        return downloadViaZeroTrustDns(urlStr)
    }

    private fun downloadViaZeroTrustDns(urlStr: String, redirectsLeft: Int = 4): String {
        require(redirectsLeft > 0) { "Too many HTTP redirects" }
        val uri = URI(urlStr)
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = uri.host ?: throw IllegalArgumentException("Invalid URL host: $urlStr")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?').append(uri.rawQuery)
            }
        }

        val resolvedIp = ZeroTrustDnsResolver.resolveHostnameToIp(
            context = context,
            hostname = host,
            config = VpnStateManager.state.value.zeroTrustDns,
            vpnService = VpnStateManager.activeVpnService
        ) ?: throw IllegalStateException("Could not resolve host '$host'")

        val rawSocket = Socket()
        try {
            VpnStateManager.activeVpnService?.protect(rawSocket)
            rawSocket.connect(InetSocketAddress(InetAddress.getByName(resolvedIp), port), 10_000)
            rawSocket.soTimeout = 15_000

            val activeSocket = if (scheme == "https") {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                    sslParameters = sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    startHandshake()
                }
            } else {
                rawSocket
            }

            val reqHeaders = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("User-Agent: xPrivacyGuard-Android/1.1\r\n")
                append("Accept: text/plain, */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            activeSocket.outputStream.write(reqHeaders.toByteArray(StandardCharsets.US_ASCII))
            activeSocket.outputStream.flush()

            val fullBytes = activeSocket.inputStream.readBytes()
            val headerEnd = findHeaderEnd(fullBytes)
                ?: throw IllegalStateException("Invalid HTTP response from $host")
            val headerStr = String(fullBytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
            val headerLines = headerStr.split("\r\n")
            val statusLine = headerLines.firstOrNull().orEmpty()
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0

            if (statusCode in 301..308) {
                val location = headerLines.firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                if (!location.isNullOrBlank()) {
                    val redirectUrl = if (location.startsWith("http")) location else uri.resolve(location).toString()
                    return downloadViaZeroTrustDns(redirectUrl, redirectsLeft - 1)
                }
            }

            require(statusCode in 200..299) { "HTTP $statusCode from $host" }

            val bodyOffset = headerEnd + 4
            if (bodyOffset >= fullBytes.size) return ""
            val rawBody = fullBytes.copyOfRange(bodyOffset, fullBytes.size)
            val decodedBody = if (headerStr.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
                decodeChunked(rawBody)
            } else {
                rawBody
            }
            return String(decodedBody, StandardCharsets.UTF_8)
        } finally {
            runCatching { rawSocket.close() }
        }
    }

    private fun findHeaderEnd(buf: ByteArray): Int? {
        for (i in 0 until buf.size - 3) {
            if (buf[i] == '\r'.code.toByte() &&
                buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() &&
                buf[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun decodeChunked(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < raw.size) {
            var lineEnd = pos
            while (lineEnd < raw.size - 1 &&
                !(raw[lineEnd] == '\r'.code.toByte() && raw[lineEnd + 1] == '\n'.code.toByte())
            ) {
                lineEnd++
            }
            if (lineEnd >= raw.size - 1) break
            val sizeHex = String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim().substringBefore(';')
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            pos = lineEnd + 2
            if (pos + chunkSize > raw.size) break
            out.write(raw, pos, chunkSize)
            pos += chunkSize + 2
        }
        return out.toByteArray()
    }

    private fun resolveFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
