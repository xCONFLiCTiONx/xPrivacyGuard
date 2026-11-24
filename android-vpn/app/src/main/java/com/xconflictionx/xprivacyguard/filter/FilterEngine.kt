package com.xconflictionx.xprivacyguard.filter

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class FilterDecision(
    val blocked: Boolean,
    val matchedRule: String? = null,
    val sourceName: String? = null,
    val category: String = "DOMAIN"
)

data class CompiledPathRule(
    val rawRule: String,
    val sourceName: String,
    val domainRestriction: String?,
    val regex: Regex,
    val isException: Boolean
)

data class FilterStats(
    val totalRules: Int,
    val domainBlockRules: Int,
    val pathBlockRules: Int,
    val exceptionRules: Int,
    val enabledSources: Int
)

private class FilterSnapshot(
    val blockedDomains: Map<String, Pair<String, String>>,
    val exceptionDomains: Map<String, Pair<String, String>>,
    val pathRules: List<CompiledPathRule>,
    val exceptionPathRules: List<CompiledPathRule>
)

class FilterEngine {

    companion object {
        private val ALLOWED_DECISION = FilterDecision(blocked = false)
        private const val MAX_DOMAIN_CACHE_SIZE = 1024
    }

    @Volatile
    private var snapshot = FilterSnapshot(
        blockedDomains = emptyMap(),
        exceptionDomains = emptyMap(),
        pathRules = emptyList(),
        exceptionPathRules = emptyList()
    )

    // Lock-free fast-path cache for domain lookups
    private val domainDecisionCache = ConcurrentHashMap<String, FilterDecision>(512)

    @Volatile
    var stats: FilterStats = FilterStats(0, 0, 0, 0, 0)
        private set

    @Synchronized
    fun loadSources(sources: List<FilterSource>) {
        val blockedDomains = HashMap<String, Pair<String, String>>(4096)
        val exceptionDomains = HashMap<String, Pair<String, String>>(256)
        val pathRules = ArrayList<CompiledPathRule>(128)
        val exceptionPathRules = ArrayList<CompiledPathRule>(32)

        var enabledCount = 0
        for (source in sources) {
            if (!source.enabled) continue
            enabledCount++
            parseFilterContent(
                content = source.content,
                sourceName = source.name,
                blockedDomains = blockedDomains,
                exceptionDomains = exceptionDomains,
                pathRules = pathRules,
                exceptionPathRules = exceptionPathRules
            )
        }

        snapshot = FilterSnapshot(
            blockedDomains = blockedDomains,
            exceptionDomains = exceptionDomains,
            pathRules = pathRules,
            exceptionPathRules = exceptionPathRules
        )
        domainDecisionCache.clear()

        stats = FilterStats(
            totalRules = blockedDomains.size + pathRules.size + exceptionDomains.size + exceptionPathRules.size,
            domainBlockRules = blockedDomains.size,
            pathBlockRules = pathRules.size,
            exceptionRules = exceptionDomains.size + exceptionPathRules.size,
            enabledSources = enabledCount
        )
    }

    private fun parseFilterContent(
        content: String,
        sourceName: String,
        blockedDomains: HashMap<String, Pair<String, String>>,
        exceptionDomains: HashMap<String, Pair<String, String>>,
        pathRules: ArrayList<CompiledPathRule>,
        exceptionPathRules: ArrayList<CompiledPathRule>
    ) {
        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.startsWith('!') || trimmed.startsWith('#') || trimmed.startsWith('[')) {
                return@forEach
            }
            // Ignore cosmetic / HTML element hiding rules (##, #?#, #@#, #$#)
            if (trimmed.contains("##") || trimmed.contains("#?#") || trimmed.contains("#@#") || trimmed.contains("#$#")) {
                return@forEach
            }

            parseSingleRule(
                rawRule = trimmed,
                sourceName = sourceName,
                blockedDomains = blockedDomains,
                exceptionDomains = exceptionDomains,
                pathRules = pathRules,
                exceptionPathRules = exceptionPathRules
            )
        }
    }

    private fun parseSingleRule(
        rawRule: String,
        sourceName: String,
        blockedDomains: HashMap<String, Pair<String, String>>,
        exceptionDomains: HashMap<String, Pair<String, String>>,
        pathRules: ArrayList<CompiledPathRule>,
        exceptionPathRules: ArrayList<CompiledPathRule>
    ) {
        var rule = rawRule
        var isException = false

        if (rule.startsWith("@@")) {
            isException = true
            rule = rule.substring(2).trim()
        }

        // Strip Adblock modifiers after '$' (e.g., $third-party, $script, $important)
        val dollarIndex = rule.indexOf('$')
        if (dollarIndex >= 0) {
            val modifiers = rule.substring(dollarIndex + 1).lowercase(Locale.ROOT)
            if (modifiers.contains("badfilter")) return
            rule = rule.substring(0, dollarIndex).trim()
        }

        if (rule.isEmpty()) return

        // Handle hosts-file syntax: "0.0.0.0 domain.com" or "127.0.0.1 domain.com"
        if (rule.startsWith("0.0.0.0 ") || rule.startsWith("127.0.0.1 ") ||
            rule.startsWith("0.0.0.0\t") || rule.startsWith("127.0.0.1\t") ||
            rule.startsWith("::1 ") || rule.startsWith(":: ")
        ) {
            val parts = rule.substringBefore('#').trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val host = parts[1].lowercase(Locale.ROOT).trimEnd('.')
                if (isValidDomain(host) && !isLocalhostDomain(host)) {
                    if (isException) {
                        exceptionDomains[host] = rawRule to sourceName
                    } else {
                        blockedDomains[host] = rawRule to sourceName
                    }
                }
            }
            return
        }

        // Handle Adblock domain anchor syntax: "||domain.com^" or "||domain.com/path^"
        if (rule.startsWith("||")) {
            val body = rule.substring(2)
            val slashIdx = body.indexOf('/')
            if (slashIdx < 0) {
                val cleanDomain = body.trimEnd('^', '/').lowercase(Locale.ROOT).trimEnd('.')
                if (cleanDomain.contains('*')) {
                    val regex = wildcardToRegex(cleanDomain)
                    val compiled = CompiledPathRule(rawRule, sourceName, null, regex, isException)
                    if (isException) exceptionPathRules.add(compiled) else pathRules.add(compiled)
                } else if (isValidDomain(cleanDomain)) {
                    if (isException) {
                        exceptionDomains[cleanDomain] = rawRule to sourceName
                    } else {
                        blockedDomains[cleanDomain] = rawRule to sourceName
                    }
                }
            } else {
                // Domain + path rule such as ||vk.com/rtrg^
                val domainPart = body.substring(0, slashIdx).lowercase(Locale.ROOT).trimEnd('.')
                val pathPart = body.substring(slashIdx) // includes leading '/'
                val regex = adblockPathToRegex(pathPart)
                val compiled = CompiledPathRule(
                    rawRule = rawRule,
                    sourceName = sourceName,
                    domainRestriction = domainPart.takeIf { isValidDomain(it) },
                    regex = regex,
                    isException = isException
                )
                if (isException) exceptionPathRules.add(compiled) else pathRules.add(compiled)
            }
            return
        }

        // Handle URL prefix syntax: "|http://..." or "|https://..."
        if (rule.startsWith("|http://") || rule.startsWith("|https://")) {
            val withoutPipe = rule.substring(1)
            val afterScheme = withoutPipe.substringAfter("://")
            val host = afterScheme.substringBefore('/').trimEnd('^').lowercase(Locale.ROOT)
            val path = if (afterScheme.contains('/')) "/" + afterScheme.substringAfter('/') else ""
            if (path.isEmpty() || path == "/" || path == "/^") {
                if (isValidDomain(host)) {
                    if (isException) exceptionDomains[host] = rawRule to sourceName
                    else blockedDomains[host] = rawRule to sourceName
                }
            } else {
                val compiled = CompiledPathRule(
                    rawRule = rawRule,
                    sourceName = sourceName,
                    domainRestriction = host.takeIf { isValidDomain(it) },
                    regex = adblockPathToRegex(path),
                    isException = isException
                )
                if (isException) exceptionPathRules.add(compiled) else pathRules.add(compiled)
            }
            return
        }

        // Handle wildcard or path rules such as "*/ads.js", "*/pagead.js", "/analytics.js"
        if (rule.contains('*') || rule.contains('/') || rule.contains('^')) {
            val clean = rule.trimEnd('^')
            if (!clean.contains('/') && !clean.contains('*') && isValidDomain(clean.lowercase(Locale.ROOT))) {
                val d = clean.lowercase(Locale.ROOT)
                if (isException) exceptionDomains[d] = rawRule to sourceName
                else blockedDomains[d] = rawRule to sourceName
            } else {
                val compiled = CompiledPathRule(
                    rawRule = rawRule,
                    sourceName = sourceName,
                    domainRestriction = null,
                    regex = adblockPathToRegex(rule),
                    isException = isException
                )
                if (isException) exceptionPathRules.add(compiled) else pathRules.add(compiled)
            }
            return
        }

        // Handle plain domain line: "ads.example.com"
        val candidate = rule.lowercase(Locale.ROOT).trimEnd('.')
        if (isValidDomain(candidate) && !isLocalhostDomain(candidate)) {
            if (isException) {
                exceptionDomains[candidate] = rawRule to sourceName
            } else {
                blockedDomains[candidate] = rawRule to sourceName
            }
        }
    }

    /**
     * Lock-free, cached domain lookup.
     * Matches exact domain and any parent domain (subdomain matching for ||domain^ rules).
     */
    fun checkDomain(rawDomain: String): FilterDecision {
        val domain = rawDomain.trim().lowercase(Locale.ROOT).trimEnd('.')
        if (domain.isEmpty()) return ALLOWED_DECISION

        domainDecisionCache[domain]?.let { return it }

        val snap = snapshot

        // 1. Check domain allowlist (exceptions) from specific to parent domain
        var current = domain
        while (true) {
            val exc = snap.exceptionDomains[current]
            if (exc != null) {
                val decision = FilterDecision(
                    blocked = false,
                    matchedRule = exc.first,
                    sourceName = exc.second,
                    category = "ALLOWLIST"
                )
                cacheDomainDecision(domain, decision)
                return decision
            }
            val dot = current.indexOf('.')
            if (dot < 0) break
            current = current.substring(dot + 1)
        }

        // 2. Check domain blocklist from specific to parent domain
        current = domain
        while (true) {
            val blk = snap.blockedDomains[current]
            if (blk != null) {
                val decision = FilterDecision(
                    blocked = true,
                    matchedRule = blk.first,
                    sourceName = blk.second,
                    category = "DNS"
                )
                cacheDomainDecision(domain, decision)
                return decision
            }
            val dot = current.indexOf('.')
            if (dot < 0) break
            current = current.substring(dot + 1)
        }

        cacheDomainDecision(domain, ALLOWED_DECISION)
        return ALLOWED_DECISION
    }

    private fun cacheDomainDecision(domain: String, decision: FilterDecision) {
        if (domainDecisionCache.size >= MAX_DOMAIN_CACHE_SIZE) {
            domainDecisionCache.clear()
        }
        domainDecisionCache[domain] = decision
    }

    /**
     * Lock-free check for a full URL or Host + Path (e.g., host="vk.com", path="/rtrg" or path="/js/ads.js").
     */
    fun checkUrl(rawHost: String, rawPath: String): FilterDecision {
        val host = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
        val normalizedPath = if (rawPath.startsWith('/')) rawPath else "/$rawPath"

        // 1. Check domain decision first
        val domainDecision = checkDomain(host)
        if (domainDecision.matchedRule != null && !domainDecision.blocked) {
            return domainDecision
        }
        if (domainDecision.blocked) {
            return domainDecision
        }

        val snap = snapshot
        if (snap.exceptionPathRules.isEmpty() && snap.pathRules.isEmpty()) {
            return ALLOWED_DECISION
        }

        val fullTarget = "$host$normalizedPath"

        // 2. Check path exception rules
        for (rule in snap.exceptionPathRules) {
            if (rule.domainRestriction != null && !domainMatches(host, rule.domainRestriction)) {
                continue
            }
            if (rule.regex.containsMatchIn(normalizedPath) || rule.regex.containsMatchIn(fullTarget)) {
                return FilterDecision(
                    blocked = false,
                    matchedRule = rule.rawRule,
                    sourceName = rule.sourceName,
                    category = "ALLOWLIST_PATH"
                )
            }
        }

        // 3. Check path/wildcard block rules (e.g. ||vk.com/rtrg^, */ads.js)
        for (rule in snap.pathRules) {
            if (rule.domainRestriction != null && !domainMatches(host, rule.domainRestriction)) {
                continue
            }
            if (rule.regex.containsMatchIn(normalizedPath) || rule.regex.containsMatchIn(fullTarget)) {
                return FilterDecision(
                    blocked = true,
                    matchedRule = rule.rawRule,
                    sourceName = rule.sourceName,
                    category = "URL_PATH"
                )
            }
        }

        return ALLOWED_DECISION
    }

    /**
     * Convenience method for the UI Rule Tester.
     */
    fun testInput(input: String): FilterDecision {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ALLOWED_DECISION

        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")

        return if (withoutScheme.startsWith('/')) {
            checkUrl("example.com", withoutScheme)
        } else if (withoutScheme.contains('/')) {
            val host = withoutScheme.substringBefore('/')
            val path = "/" + withoutScheme.substringAfter('/')
            checkUrl(host, path)
        } else {
            checkDomain(withoutScheme)
        }
    }

    private fun domainMatches(host: String, targetDomain: String): Boolean {
        return host == targetDomain || host.endsWith(".$targetDomain")
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.length < 3 || !domain.contains('.')) return false
        return domain.all { ch ->
            ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'
        }
    }

    private fun isLocalhostDomain(domain: String): Boolean {
        return domain == "localhost" ||
            domain == "localhost.localdomain" ||
            domain == "local" ||
            domain == "broadcasthost" ||
            domain == "ip6-localhost" ||
            domain == "ip6-loopback"
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[/?&=:]+|$)")
                '.', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '|', '$' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun adblockPathToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[/?&=:]+|$)")
                '.', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '|', '$' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
