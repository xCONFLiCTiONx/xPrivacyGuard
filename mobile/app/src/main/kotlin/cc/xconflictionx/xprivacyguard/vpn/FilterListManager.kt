package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

object FilterListManager {

    private const val TAG = "FilterListManager"
    private const val CACHE_FILE_NAME = "custom_filter_rules.cache"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var blockedDomains: Set<String> = emptySet()
        private set

    @Volatile
    var allowDomains: Set<String> = emptySet()
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var initialized = false

    const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L // 24 hours

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        scope.launch {
            loadFromDiskCache(context)
            if (blockedDomains.isEmpty() && allowDomains.isEmpty()) {
                loadFromAssets(context)
            }
            if (VpnConfig.customFiltersEnabled && VpnConfig.customFilterUrls.isNotEmpty()) {
                if (blockedDomains.isEmpty()) {
                    downloadAndRefresh(context, force = true)
                } else {
                    checkAutoUpdate(context)
                }
            }
        }
    }

    fun checkAutoUpdate(context: Context) {
        if (!VpnConfig.customFiltersEnabled || !VpnConfig.customFiltersAutoUpdate) return
        if (VpnConfig.customFiltersIsDownloading) return
        val urls = VpnConfig.customFilterUrls
        if (urls.isEmpty()) return

        val lastUpdated = VpnConfig.customFiltersLastUpdated
        val now = System.currentTimeMillis()
        if (now - lastUpdated >= ONE_DAY_MILLIS) {
            logInfo("Custom filter lists are due for daily update (${if (lastUpdated == 0L) "never updated" else "${(now - lastUpdated) / (1000 * 60 * 60)}h ago"}). Refreshing...")
            downloadAndRefresh(context, force = true)
        }
    }

    private fun loadFromDiskCache(context: Context) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists()) {
                VpnConfig.customFiltersRuleCount = 0
                return
            }

            val blocks = mutableSetOf<String>()
            val allows = mutableSetOf<String>()

            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("ALLOW:")) {
                        val domain = line.substring(6).trim()
                        if (domain.isNotEmpty()) allows.add(domain)
                    } else if (line.startsWith("BLOCK:")) {
                        val domain = line.substring(6).trim()
                        if (domain.isNotEmpty()) blocks.add(domain)
                    } else {
                        val domain = line.trim()
                        if (domain.isNotEmpty()) blocks.add(domain)
                    }
                }
            }

            blockedDomains = blocks
            allowDomains = allows
            VpnConfig.customFiltersRuleCount = blocks.size
            if (VpnConfig.customFilterUrls.isNotEmpty() && VpnConfig.customFiltersEnabled) {
                VpnConfig.customFiltersStatus = "Active (${blocks.size} rules loaded from cache)"
            }
            logInfo("Loaded ${blocks.size} blocked domains and ${allows.size} allow rules from disk cache.")
        } catch (e: Exception) {
            logError("Failed to load filter rules from disk cache", e)
        }
    }

    private fun loadFromAssets(context: Context) {
        try {
            context.assets.open("rules.json").use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }
                val (blocks, allows) = parseFilterContent(content)
                if (blocks.isNotEmpty() || allows.isNotEmpty()) {
                    blockedDomains = blocks
                    allowDomains = allows
                    VpnConfig.customFiltersRuleCount = blocks.size
                    VpnConfig.customFiltersStatus = "Active (${blocks.size} rules loaded from bundled rules.json)"
                    saveToDiskCache(context)
                    logInfo("Loaded ${blocks.size} block rules and ${allows.size} allow rules from asset rules.json")
                }
            }
        } catch (e: Exception) {
            logWarn("Could not load bundled asset rules.json: ${e.message}")
        }
    }

    private fun saveToDiskCache(context: Context) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.bufferedWriter().use { writer ->
                allowDomains.forEach { domain ->
                    writer.write("ALLOW:$domain\n")
                }
                blockedDomains.forEach { domain ->
                    writer.write("BLOCK:$domain\n")
                }
            }
            logInfo("Saved ${blockedDomains.size} block rules and ${allowDomains.size} allow rules to disk cache.")
        } catch (e: Exception) {
            logError("Failed to save filter rules to disk cache", e)
        }
    }

    fun downloadAndRefresh(context: Context, force: Boolean = false, onComplete: ((Boolean, String) -> Unit)? = null) {
        val urls = VpnConfig.customFilterUrls
        if (urls.isEmpty()) {
            blockedDomains = emptySet()
            allowDomains = emptySet()
            VpnConfig.customFiltersEnabled = false
            VpnConfig.customFiltersRuleCount = 0
            VpnConfig.customFiltersStatus = "Disabled (No URL provided)"
            VpnConfig.save(context)
            onComplete?.invoke(false, "No filter URLs provided.")
            return
        }

        if (VpnConfig.customFiltersIsDownloading) {
            onComplete?.invoke(false, "Download already in progress.")
            return
        }

        if (!VpnConfig.customFiltersEnabled && !force) {
            return
        }

        VpnConfig.customFiltersIsDownloading = true
        VpnConfig.customFiltersStatus = "Syncing filter list(s)..."

        scope.launch {
            val allBlocked = mutableSetOf<String>()
            val allAllows = mutableSetOf<String>()
            var successCount = 0
            var lastError = ""

            for (url in urls.toList()) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) xPrivacyGuardVPN/1.0")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyText = response.body?.string() ?: ""
                            val (parsedBlocks, parsedAllows) = parseFilterContent(bodyText)
                            allBlocked.addAll(parsedBlocks)
                            allAllows.addAll(parsedAllows)
                            successCount++
                        } else {
                            lastError = "HTTP ${response.code} for $url"
                            logWarn("Failed to download $url: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Network error downloading filter URL"
                    logError("Error downloading filter list from $url", e)
                }
            }

            withContext(Dispatchers.Main) {
                VpnConfig.customFiltersIsDownloading = false
                if (successCount > 0) {
                    val changed = allBlocked != blockedDomains || allAllows != allowDomains
                    blockedDomains = allBlocked
                    allowDomains = allAllows
                    saveToDiskCache(context)

                    VpnConfig.customFiltersRuleCount = allBlocked.size
                    VpnConfig.customFiltersLastUpdated = System.currentTimeMillis()
                    VpnConfig.customFiltersStatus = "Active (${allBlocked.size} rules loaded)"
                    VpnConfig.save(context)

                    val msg = "Successfully loaded ${allBlocked.size} filter rules from $successCount source(s)."
                    logInfo(msg)
                    if (changed) VpnConfig.applyChanges(context)
                    onComplete?.invoke(true, msg)
                } else {
                    if (blockedDomains.isNotEmpty()) {
                        VpnConfig.customFiltersStatus = "Error updating ($lastError) - Using ${blockedDomains.size} cached rules"
                    } else {
                        VpnConfig.customFiltersStatus = "Error downloading filter rules: $lastError"
                    }
                    onComplete?.invoke(false, "Failed to sync filter rules: $lastError")
                }
            }
        }
    }

    fun parseFilterContent(content: String): Pair<Set<String>, Set<String>> {
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) {
            return parseJsonFilterContent(trimmed)
        }
        return parseTextFilterContent(trimmed)
    }

    private fun parseJsonFilterContent(jsonText: String): Pair<Set<String>, Set<String>> {
        val blocks = mutableSetOf<String>()
        val allows = mutableSetOf<String>()

        var parsedWithOrgJson: Boolean
        try {
            val jsonArray = JSONArray(jsonText)
            for (i in 0 until jsonArray.length()) {
                val ruleObj = jsonArray.optJSONObject(i) ?: continue
                val action = ruleObj.optJSONObject("action")
                val actionType = action?.optString("type") ?: continue
                val isAllow = actionType.equals("allow", ignoreCase = true)
                val isBlock = actionType.equals("block", ignoreCase = true)

                if (!isAllow && !isBlock) continue

                val condition = ruleObj.optJSONObject("condition") ?: continue

                // 1. requestDomains array
                val requestDomains = condition.optJSONArray("requestDomains")
                if (requestDomains != null) {
                    for (j in 0 until requestDomains.length()) {
                        val rawDomain = requestDomains.optString(j)
                        val domain = cleanAndValidateDomain(rawDomain)
                        if (domain != null) {
                            if (isAllow) allows.add(domain) else blocks.add(domain)
                        }
                    }
                }

                // 2. urlFilter string
                val urlFilter = condition.optString("urlFilter", "")
                if (urlFilter.isNotEmpty()) {
                    val parsed = parseRule(urlFilter)
                    if (parsed != null) {
                        if (isAllow || parsed.isAllow) allows.add(parsed.domain)
                        else blocks.add(parsed.domain)
                    }
                }

                // 3. regexFilter string
                val regexFilter = condition.optString("regexFilter", "")
                if (regexFilter.isNotEmpty()) {
                    val extractedDomains = extractDomainsFromRegex(regexFilter)
                    for (domain in extractedDomains) {
                        if (isAllow) allows.add(domain) else blocks.add(domain)
                    }
                }
            }
            parsedWithOrgJson = true
        } catch (_: Throwable) {
            parsedWithOrgJson = false
        }

        if (parsedWithOrgJson) {
            return Pair(blocks, allows)
        }

        // Fallback text/regex scanning for JSON rule objects (e.g. JVM unit tests)
        try {
            val objectBlocks = jsonText.split(Regex("\\}\\s*,\\s*\\{"))
            for (objStr in objectBlocks) {
                val isAllow = objStr.contains("\"type\"\\s*:\\s*\"allow\"".toRegex(RegexOption.IGNORE_CASE))
                val isBlock = objStr.contains("\"type\"\\s*:\\s*\"block\"".toRegex(RegexOption.IGNORE_CASE))

                if (!isAllow && !isBlock) continue

                val reqDomMatch = Regex("\"requestDomains\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(objStr)
                if (reqDomMatch != null) {
                    val arrayContent = reqDomMatch.groupValues[1]
                    val stringMatches = Regex("\"([^\"]+)\"").findAll(arrayContent)
                    for (m in stringMatches) {
                        val domain = cleanAndValidateDomain(m.groupValues[1])
                        if (domain != null) {
                            if (isAllow) allows.add(domain) else blocks.add(domain)
                        }
                    }
                }

                val urlMatch = Regex("\"urlFilter\"\\s*:\\s*\"([^\"]+)\"").find(objStr)
                if (urlMatch != null) {
                    val parsed = parseRule(urlMatch.groupValues[1])
                    if (parsed != null) {
                        if (isAllow || parsed.isAllow) allows.add(parsed.domain)
                        else blocks.add(parsed.domain)
                    }
                }

                val regexMatch = Regex("\"regexFilter\"\\s*:\\s*\"([^\"]+)\"").find(objStr)
                if (regexMatch != null) {
                    val extractedDomains = extractDomainsFromRegex(regexMatch.groupValues[1])
                    for (domain in extractedDomains) {
                        if (isAllow) allows.add(domain) else blocks.add(domain)
                    }
                }
            }
        } catch (_: Throwable) {}

        return Pair(blocks, allows)
    }

    private fun parseTextFilterContent(content: String): Pair<Set<String>, Set<String>> {
        val blocks = mutableSetOf<String>()
        val allows = mutableSetOf<String>()

        content.lineSequence().forEach { rawLine ->
            val rule = parseRule(rawLine)
            if (rule != null) {
                if (rule.isAllow) {
                    allows.add(rule.domain)
                } else {
                    blocks.add(rule.domain)
                }
            }
        }

        return Pair(blocks, allows)
    }

    private fun extractDomainsFromRegex(regex: String): Set<String> {
        val results = mutableSetOf<String>()
        val cleaned = regex.replace("\\.", ".")
            .replace("https?://", "")
            .replace("^", "")
            .replace("$", "")
            .substringBefore("/")

        val domainRegex = Regex("[a-zA-Z0-9-]+\\.[a-zA-Z0-9.-]+")
        domainRegex.findAll(cleaned).forEach { match ->
            val raw = match.value.trimStart('.').trimEnd('.')
            val cleanToken = raw.replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("\\(.*\\)"), "")
                .trimStart('.').trimEnd('.')
            val domain = cleanAndValidateDomain(cleanToken)
            if (domain != null) {
                results.add(domain)
            }
        }
        return results
    }

    data class ParsedRule(val domain: String, val isAllow: Boolean)

    fun parseRule(line: String): ParsedRule? {
        var trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // Ignore comments and metadata lines
        if (trimmed.startsWith("!") ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith(";") ||
            trimmed.startsWith("/*")) {
            return null
        }

        // Strip inline comments starting with '#' or '!' preceded by whitespace
        val commentIdx = trimmed.indexOf(" #").let { if (it != -1) it else trimmed.indexOf(" !") }
        if (commentIdx != -1) {
            trimmed = trimmed.substring(0, commentIdx).trim()
        }

        if (trimmed.isEmpty()) return null

        // Ignore cosmetic / element hiding / DOM CSS rules
        if (trimmed.contains("##") ||
            trimmed.contains("#@#") ||
            trimmed.contains("#$#")) {
            return null
        }

        // Ignore regex URL patterns that start and end with /
        if (trimmed.startsWith("/") && trimmed.endsWith("/") && trimmed.length > 2) {
            return null
        }

        var text = trimmed

        // Check for exception rule (starts with @@)
        val isAllow = text.startsWith("@@")
        if (isAllow) {
            text = text.substring(2)
        }

        val cleanDomain = cleanAndValidateDomain(text)
        if (cleanDomain != null) {
            return ParsedRule(cleanDomain, isAllow)
        }

        return null
    }

    fun cleanAndValidateDomain(raw: String): String? {
        var text = raw.lowercase().trim()

        // Strip inline comments
        val commentIdx = text.indexOf(" #").let { if (it != -1) it else text.indexOf(" !") }
        if (commentIdx != -1) {
            text = text.substring(0, commentIdx).trim()
        }

        // Strip options after $
        if (text.contains("$")) {
            text = text.substringBefore("$")
        }

        // Handle Hosts file format e.g. "0.0.0.0 domain.com" or "127.0.0.1 domain.com"
        if (text.startsWith("0.0.0.0") || text.startsWith("127.0.0.1") || text.startsWith("::1")) {
            val parts = text.split(Regex("\\s+"))
            if (parts.size >= 2) {
                text = parts[1]
            }
        }

        // Strip protocol prefix if present
        if (text.startsWith("http://")) text = text.substring(7)
        if (text.startsWith("https://")) text = text.substring(8)

        // Strip paths, query params, ports
        text = text.substringBefore("/")
        text = text.substringBefore(":")
        text = text.substringBefore("?")

        // Strip leading || or |
        if (text.startsWith("||")) {
            text = text.substring(2)
        } else if (text.startsWith("|")) {
            text = text.substring(1)
        }

        // Strip trailing |
        if (text.endsWith("|")) {
            text = text.substring(0, text.length - 1)
        }

        // Strip ^ modifier
        if (text.contains("^")) {
            text = text.substringBefore("^")
        }

        // Clean wildcards or leading dots
        if (text.startsWith("*.")) {
            text = text.substring(2)
        }
        if (text.startsWith(".")) {
            text = text.substring(1)
        }
        if (text.startsWith("*")) {
            text = text.substring(1)
        }

        val cleanDomain = text.trimStart('.').trimEnd('.')

        if (cleanDomain.isNotEmpty() && isValidDomain(cleanDomain)) {
            return cleanDomain
        }

        return null
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain == "localhost" || domain == "local" || domain == "broadcasthost" || domain == "0.0.0.0" || domain == "127.0.0.1") return false
        if (!domain.contains(".")) return false
        if (domain.length > 253) return false
        return domain.all { it.isLetterOrDigit() || it == '-' || it == '.' }
    }

    fun isDomainAllowed(domain: String): Boolean {
        if (!VpnConfig.customFiltersEnabled || allowDomains.isEmpty()) return false
        val lower = domain.lowercase().trim().trimEnd('.')
        return matchesDomainOrParent(lower, allowDomains)
    }

    fun isDomainBlocked(domain: String): Boolean {
        if (!VpnConfig.customFiltersEnabled || blockedDomains.isEmpty()) return false
        val lower = domain.lowercase().trim().trimEnd('.')
        return matchesDomainOrParent(lower, blockedDomains)
    }

    private fun matchesDomainOrParent(domain: String, domainSet: Set<String>): Boolean {
        if (domainSet.contains(domain)) return true
        var current = domain
        while (current.contains(".")) {
            current = current.substringAfter(".")
            if (domainSet.contains(current)) {
                return true
            }
        }
        return false
    }

    private fun logInfo(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        try { Log.e(TAG, msg, tr) } catch (_: Throwable) {}
    }
}
