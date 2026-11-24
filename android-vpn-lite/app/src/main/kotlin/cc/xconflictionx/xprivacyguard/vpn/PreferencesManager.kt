package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context

class PreferencesManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var isVpnEnabled: Boolean
        get() = preferences.getBoolean(KEY_VPN_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_VPN_ENABLED, value).apply()

    var isFirstLaunch: Boolean
        get() = preferences.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = preferences.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var secureDnsEnabled: Boolean
        get() = preferences.getBoolean(KEY_SECURE_DNS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_SECURE_DNS_ENABLED, value).apply()

    var dnsProtocol: String
        get() = DEFAULT_DNS_PROTOCOL
        set(value) = preferences.edit().putString(KEY_DNS_PROTOCOL, DEFAULT_DNS_PROTOCOL).apply()

    var dnsEndpoint: String
        get() = preferences.getString(KEY_DNS_ENDPOINT, DEFAULT_DNS_ENDPOINT) ?: DEFAULT_DNS_ENDPOINT
        set(value) = preferences.edit().putString(KEY_DNS_ENDPOINT, value).apply()

    var dnsIpv4: String
        get() = preferences.getString(KEY_DNS_IPV4, DEFAULT_DNS_IPV4) ?: DEFAULT_DNS_IPV4
        set(value) = preferences.edit().putString(KEY_DNS_IPV4, value).apply()

    var dnsIpv6: String
        get() = preferences.getString(KEY_DNS_IPV6, DEFAULT_DNS_IPV6) ?: DEFAULT_DNS_IPV6
        set(value) = preferences.edit().putString(KEY_DNS_IPV6, value).apply()

    var adBlockingEnabled: Boolean
        get() = preferences.getBoolean(KEY_AD_BLOCKING_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_AD_BLOCKING_ENABLED, value).apply()

    var trackerBlockingEnabled: Boolean
        get() = preferences.getBoolean(KEY_TRACKER_BLOCKING_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_TRACKER_BLOCKING_ENABLED, value).apply()

    var malwareProtectionEnabled: Boolean
        get() = preferences.getBoolean(KEY_MALWARE_PROTECTION_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_MALWARE_PROTECTION_ENABLED, value).apply()

    var killSwitchEnabled: Boolean
        get() = preferences.getBoolean(KEY_KILL_SWITCH_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_KILL_SWITCH_ENABLED, value).apply()

    var customFiltersEnabled: Boolean
        get() = preferences.getBoolean(KEY_CUSTOM_FILTERS_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_CUSTOM_FILTERS_ENABLED, value).apply()

    var customFiltersAutoUpdate: Boolean
        get() = preferences.getBoolean(KEY_CUSTOM_FILTERS_AUTO_UPDATE, true)
        set(value) = preferences.edit().putBoolean(KEY_CUSTOM_FILTERS_AUTO_UPDATE, value).apply()

    var customFiltersLastUpdated: Long
        get() = preferences.getLong(KEY_CUSTOM_FILTERS_LAST_UPDATED, 0L)
        set(value) = preferences.edit().putLong(KEY_CUSTOM_FILTERS_LAST_UPDATED, value).apply()

    fun getCustomFilterUrls(): Set<String> = getStringSet(KEY_CUSTOM_FILTER_URLS)

    fun setCustomFilterUrls(urls: Set<String>) = setStringSet(KEY_CUSTOM_FILTER_URLS, urls)

    fun getCustomBlocklist(): Set<String> = getStringSet(KEY_CUSTOM_BLOCKLIST)

    fun setCustomBlocklist(domains: Set<String>) = setStringSet(KEY_CUSTOM_BLOCKLIST, domains)

    fun getCustomAllowlist(): Set<String> = getStringSet(KEY_CUSTOM_ALLOWLIST)

    fun setCustomAllowlist(domains: Set<String>) = setStringSet(KEY_CUSTOM_ALLOWLIST, domains)

    fun getExcludedPackages(): Set<String> {
        if (preferences.contains(KEY_EXCLUDED_PACKAGES)) {
            return getStringSet(KEY_EXCLUDED_PACKAGES)
        }
        return emptySet()
    }

    fun setExcludedPackages(packages: Set<String>) = setStringSet(KEY_EXCLUDED_PACKAGES, packages)

    fun getBlockedPackages(): Set<String> = getExcludedPackages()

    fun setBlockedPackages(packages: Set<String>) = setExcludedPackages(packages)

    private fun getStringSet(key: String): Set<String> =
        preferences.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun setStringSet(key: String, values: Set<String>) {
        preferences.edit().putStringSet(key, values.toSet()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "privacy_firewall_preferences"
        const val KEY_VPN_ENABLED = "vpn_enabled"
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_SECURE_DNS_ENABLED = "secure_dns_enabled"
        const val KEY_DNS_PROTOCOL = "dns_protocol"
        const val KEY_DNS_ENDPOINT = "dns_endpoint"
        const val KEY_DNS_IPV4 = "dns_ipv4"
        const val KEY_DNS_IPV6 = "dns_ipv6"
        const val KEY_AD_BLOCKING_ENABLED = "ad_blocking_enabled"
        const val KEY_TRACKER_BLOCKING_ENABLED = "tracker_blocking_enabled"
        const val KEY_MALWARE_PROTECTION_ENABLED = "malware_protection_enabled"
        const val KEY_KILL_SWITCH_ENABLED = "kill_switch_enabled"
        const val KEY_CUSTOM_FILTERS_ENABLED = "custom_filters_enabled"
        const val KEY_CUSTOM_FILTERS_AUTO_UPDATE = "custom_filters_auto_update"
        const val KEY_CUSTOM_FILTERS_LAST_UPDATED = "custom_filters_last_updated"
        const val KEY_CUSTOM_FILTER_URLS = "custom_filter_urls"
        const val KEY_CUSTOM_BLOCKLIST = "custom_blocklist"
        const val KEY_CUSTOM_ALLOWLIST = "custom_allowlist"
        const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        const val DEFAULT_DNS_PROTOCOL = "DoT"
        const val DEFAULT_DNS_ENDPOINT = "security.cloudflare-dns.com"
        const val DEFAULT_DNS_IPV4 = "1.1.1.2, 1.0.0.2"
        const val DEFAULT_DNS_IPV6 = "2606:4700:4700::1112, 2606:4700:4700::1002"
    }
}
