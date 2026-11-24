package com.xconflictionx.xprivacyguard.firewall

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

enum class SpecialBypassGroup(
    val canonicalPackage: String,
    val displayName: String,
    val companionSummary: String
) {
    GEMINI(
        canonicalPackage = "com.google.android.apps.bard",
        displayName = "Gemini",
        companionSummary = "Gemini + Google App + Play Services + AICore + Voice"
    ),
    PLAY_STORE(
        canonicalPackage = "com.android.vending",
        displayName = "Google Play Store",
        companionSummary = "Play Store + Download Manager + Play Services + Framework"
    ),
    GOOGLE(
        canonicalPackage = "com.google.android.googlequicksearchbox",
        displayName = "Google",
        companionSummary = "Google App + Play Services + Framework + Gemini + Assistant"
    )
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val hasLauncherIcon: Boolean,
    val specialGroup: SpecialBypassGroup? = null
)

object FirewallManager {

    const val PKG_GEMINI = "com.google.android.apps.bard"
    const val PKG_PLAY_STORE = "com.android.vending"
    const val PKG_GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val PKG_GMS = "com.google.android.gms"
    const val PKG_GSF = "com.google.android.gsf"
    const val PKG_XASSISTANT = "cc.xconflictionx.xassistant"

    /**
     * First-party companion apps that require split-DNS resolution for private domains
     * (e.g. assistant.home.example.com -> private LAN DNS server)
     * and WireGuard tunnel routing whenever the tunnel is active.
     */
    val ALWAYS_TUNNELED_PACKAGES: Set<String> = setOf(
        PKG_XASSISTANT
    )

    /**
     * Windows Phone Link / Link to Windows companion packages that require simultaneous
     * direct access to Home Wi-Fi, Cellular Data, and Bluetooth without VPN interception.
     */
    val PHONE_LINK_BYPASS_PACKAGES: Set<String> = setOf(
        "com.microsoft.appmanager",
        "com.microsoft.yourphone",
        "com.samsung.android.mdx",
        "com.samsung.android.mdx.kit",
        "com.samsung.android.mdx.quickboard"
    )

    /**
     * Complete companion package set for Google Gemini.
     * On Android, the Gemini app delegates AI chat, voice/Live audio, OAuth, Cronet QUIC,
     * and Play Integrity attestation to the Google App, Google Play Services, AICore,
     * Android System Intelligence, Speech Services, and WebView.
     */
    val GEMINI_COMPANION_PACKAGES: Set<String> = setOf(
        PKG_GEMINI,
        PKG_GOOGLE_APP,
        PKG_GMS,
        PKG_GSF,
        "com.google.android.gsf.login",
        "com.google.android.aicore",
        "com.google.android.apps.AICore",
        "com.google.android.as",
        "com.google.android.as.oss",
        "com.google.android.tts",
        "com.google.android.apps.assistant",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.ext.services",
        "com.google.android.ims",
        "com.android.credentialmanager",
        "com.google.android.CredentialSelector",
        "com.google.android.backuptransport",
        "com.google.android.configupdater"
    )

    /**
     * Complete companion package set for Google Play Store.
     * Play Store delegates APK downloads/updates to Android Download Manager
     * (com.android.providers.downloads) and delegates auth, checkin, billing,
     * and Play Integrity checks to Google Play Services & Google Services Framework.
     */
    val PLAY_STORE_COMPANION_PACKAGES: Set<String> = setOf(
        PKG_PLAY_STORE,
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        PKG_GMS,
        PKG_GSF,
        "com.google.android.gsf.login",
        "com.google.android.feedback",
        "com.google.android.partnersetup",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.ext.services",
        "com.google.android.backuptransport",
        "com.google.android.configupdater",
        "com.android.credentialmanager",
        "com.google.android.CredentialSelector"
    )

    /**
     * Complete companion package set for the Google App & Google Core Services.
     */
    val GOOGLE_COMPANION_PACKAGES: Set<String> = setOf(
        PKG_GOOGLE_APP,
        PKG_GMS,
        PKG_GSF,
        "com.google.android.gsf.login",
        PKG_GEMINI,
        "com.google.android.aicore",
        "com.google.android.apps.AICore",
        "com.google.android.as",
        "com.google.android.as.oss",
        "com.google.android.tts",
        "com.google.android.apps.assistant",
        "com.google.android.apps.searchlite",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.ext.services",
        "com.google.android.ims",
        "com.google.android.ondevicepersonalization.services",
        "com.android.credentialmanager",
        "com.google.android.CredentialSelector",
        "com.google.android.backuptransport",
        "com.google.android.configupdater"
    )

    private val GOOGLE_AD_AND_TRACKER_DOMAINS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "ads.google.com",
        "2mdn.net",
        "app-measurement.com",
        "crashlytics.com"
    )

    private val GEMINI_EXEMPT_DOMAINS = listOf(
        "gemini.google.com",
        "bard.google.com",
        "aistudio.google.com",
        "makersuite.google.com",
        "deepmind.com",
        "deepmind.google",
        "generativelanguage.googleapis.com",
        "proactivebackend-pa.googleapis.com",
        "alkalimakersuite-pa.clients6.google.com",
        "scone-pa.clients6.google.com",
        "waa-pa.clients6.google.com",
        "geller-pa.googleapis.com",
        "googleapis.com",
        "clients.google.com",
        "clients6.google.com",
        "gstatic.com",
        "googleusercontent.com",
        "accounts.google.com",
        "google.com",
        "1e100.net",
        "pki.goog"
    )

    private val PLAY_STORE_EXEMPT_DOMAINS = listOf(
        "play.google.com",
        "android.clients.google.com",
        "play-fe.googleapis.com",
        "play-lh.googleusercontent.com",
        "playintegrity.googleapis.com",
        "device-provisioning.googleapis.com",
        "checkin.gstatic.com",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "xn--ngstr-lra8j.com",
        "googleapis.com",
        "clients.google.com",
        "clients1.google.com",
        "clients2.google.com",
        "clients3.google.com",
        "clients4.google.com",
        "clients5.google.com",
        "clients6.google.com",
        "gstatic.com",
        "googleusercontent.com",
        "ggpht.com",
        "accounts.google.com",
        "1e100.net",
        "pki.goog"
    )

    private val GOOGLE_EXEMPT_DOMAINS = listOf(
        "google.com",
        "googleapis.com",
        "clients.google.com",
        "clients1.google.com",
        "clients2.google.com",
        "clients3.google.com",
        "clients4.google.com",
        "clients5.google.com",
        "clients6.google.com",
        "gstatic.com",
        "googleusercontent.com",
        "ggpht.com",
        "1e100.net",
        "pki.goog",
        "gvt1.com",
        "gvt2.com",
        "gemini.google.com",
        "bard.google.com"
    )

    fun classifySpecialGroup(packageName: String, appName: String = ""): SpecialBypassGroup? {
        val pkgLower = packageName.trim().lowercase(Locale.ROOT)
        val nameCompact = appName.trim().lowercase(Locale.ROOT).replace(" ", "")

        return when {
            pkgLower == PKG_GEMINI ||
                pkgLower == "com.google.android.aicore" ||
                pkgLower == "com.google.android.apps.aicore" ||
                pkgLower.contains("gemini") ||
                pkgLower.endsWith(".bard") ||
                nameCompact == "gemini" ||
                nameCompact == "googlegemini" -> SpecialBypassGroup.GEMINI

            pkgLower == PKG_PLAY_STORE ||
                nameCompact == "googleplaystore" ||
                nameCompact == "playstore" -> SpecialBypassGroup.PLAY_STORE

            pkgLower == PKG_GOOGLE_APP ||
                pkgLower == PKG_GMS ||
                pkgLower == PKG_GSF ||
                pkgLower == "com.google.android.apps.searchlite" ||
                pkgLower == "com.google.android.apps.assistant" ||
                nameCompact == "google" ||
                nameCompact == "googleapp" ||
                nameCompact == "googleplayservices" ||
                nameCompact == "googleservicesframework" -> SpecialBypassGroup.GOOGLE

            else -> null
        }
    }

    fun isGeminiSelected(selectedPackages: Set<String>, installedApps: Collection<InstalledAppInfo> = emptyList()): Boolean {
        if (selectedPackages.contains(PKG_GEMINI)) return true
        if (selectedPackages.any { classifySpecialGroup(it) == SpecialBypassGroup.GEMINI }) return true
        return installedApps.any {
            it.specialGroup == SpecialBypassGroup.GEMINI && selectedPackages.contains(it.packageName)
        }
    }

    fun isPlayStoreSelected(selectedPackages: Set<String>, installedApps: Collection<InstalledAppInfo> = emptyList()): Boolean {
        if (selectedPackages.contains(PKG_PLAY_STORE)) return true
        if (selectedPackages.any { classifySpecialGroup(it) == SpecialBypassGroup.PLAY_STORE }) return true
        return installedApps.any {
            it.specialGroup == SpecialBypassGroup.PLAY_STORE && selectedPackages.contains(it.packageName)
        }
    }

    fun isGoogleSelected(selectedPackages: Set<String>, installedApps: Collection<InstalledAppInfo> = emptyList()): Boolean {
        if (selectedPackages.contains(PKG_GOOGLE_APP) ||
            selectedPackages.contains(PKG_GMS) ||
            selectedPackages.contains(PKG_GSF)
        ) {
            return true
        }
        if (selectedPackages.any { classifySpecialGroup(it) == SpecialBypassGroup.GOOGLE }) return true
        return installedApps.any {
            it.specialGroup == SpecialBypassGroup.GOOGLE && selectedPackages.contains(it.packageName)
        }
    }

    /**
     * Expands the user-selected tunnel package set into the full effective set of packages
     * that must be added to VpnService.Builder.addAllowedApplication().
     *
     * Selecting Gemini, Play Store, or Google automatically includes required companion
     * IPC services (Google Play Services, Download Manager, Google App, AICore, Speech
     * Services, WebView, etc.) and shared-UID packages in the VPN allowlist.
     */
    fun expandBypassPackages(
        selectedPackages: Set<String>,
        installedApps: Collection<InstalledAppInfo> = emptyList(),
        sharedUidLookup: ((Int) -> Array<String>?)? = null
    ): Set<String> {
        if (selectedPackages.isEmpty()) return emptySet()

        val result = LinkedHashSet<String>(selectedPackages)

        val geminiActive = isGeminiSelected(selectedPackages, installedApps)
        val playStoreActive = isPlayStoreSelected(selectedPackages, installedApps)
        val googleActive = isGoogleSelected(selectedPackages, installedApps)

        if (geminiActive) {
            result.addAll(GEMINI_COMPANION_PACKAGES)
            installedApps.filter { it.specialGroup == SpecialBypassGroup.GEMINI }
                .forEach { result.add(it.packageName) }
        }

        if (playStoreActive) {
            result.addAll(PLAY_STORE_COMPANION_PACKAGES)
            installedApps.filter { it.specialGroup == SpecialBypassGroup.PLAY_STORE }
                .forEach { result.add(it.packageName) }
        }

        if (googleActive) {
            result.addAll(GOOGLE_COMPANION_PACKAGES)
            installedApps.filter { it.specialGroup == SpecialBypassGroup.GOOGLE }
                .forEach { result.add(it.packageName) }
        }

        if (installedApps.isNotEmpty()) {
            val activeUids = HashSet<Int>()
            for (app in installedApps) {
                if (result.contains(app.packageName) && app.uid > 0) {
                    activeUids.add(app.uid)
                }
            }
            if (activeUids.isNotEmpty()) {
                for (app in installedApps) {
                    if (activeUids.contains(app.uid)) {
                        result.add(app.packageName)
                    }
                }
                if (sharedUidLookup != null) {
                    for (uid in activeUids) {
                        runCatching {
                            sharedUidLookup(uid)?.forEach { result.add(it) }
                        }
                    }
                }
            }
        }

        return result
    }

    /**
     * Legacy compatibility helper for callers that explicitly request Google-service
     * filter exemptions. The VPN service does not pass selected tunnel apps here, so
     * selected apps continue to receive the full filter policy.
     */
    fun isDomainExemptedByBypass(domain: String, selectedPackages: Set<String>): Boolean {
        if (selectedPackages.isEmpty()) return false
        val host = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) return false

        // Never exempt known ad or analytics domains
        if (GOOGLE_AD_AND_TRACKER_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return false
        }

        if (isGeminiSelected(selectedPackages)) {
            if (GEMINI_EXEMPT_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                return true
            }
        }

        if (isPlayStoreSelected(selectedPackages)) {
            if (PLAY_STORE_EXEMPT_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                return true
            }
        }

        if (isGoogleSelected(selectedPackages)) {
            if (GOOGLE_EXEMPT_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                return true
            }
        }

        return false
    }

    fun matchesSearchQuery(app: InstalledAppInfo, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true

        val qCompact = q.replace(" ", "")
        val nameLower = app.appName.lowercase(Locale.ROOT)
        val nameCompact = nameLower.replace(" ", "")
        val pkgLower = app.packageName.lowercase(Locale.ROOT)

        if (nameLower.contains(q) || nameCompact.contains(qCompact) || pkgLower.contains(q)) {
            return true
        }

        if ((qCompact == "gemini" || qCompact == "bard") && app.specialGroup == SpecialBypassGroup.GEMINI) {
            return true
        }
        if ((qCompact == "playstore" || qCompact == "googleplay" || qCompact == "vending") &&
            app.specialGroup == SpecialBypassGroup.PLAY_STORE
        ) {
            return true
        }
        if (qCompact == "google" && app.specialGroup == SpecialBypassGroup.GOOGLE) {
            return true
        }

        return false
    }

    @Suppress("DEPRECATION")
    fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager

        val launcherPackages = HashSet<String>()
        runCatching {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(
                mainIntent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
            )
            for (ri in resolveInfos) {
                ri.activityInfo?.packageName?.let { launcherPackages.add(it) }
            }
        }

        val rawApps = runCatching {
            pm.getInstalledApplications(
                PackageManager.GET_META_DATA or
                    PackageManager.MATCH_DIRECT_BOOT_AWARE or
                    PackageManager.MATCH_DIRECT_BOOT_UNAWARE
            )
        }.getOrElse {
            runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        }

        val mapped = ArrayList<InstalledAppInfo>(rawApps.size)
        val seenPackages = HashSet<String>(rawApps.size)

        for (appInfo in rawApps) {
            val pkg = appInfo.packageName ?: continue
            if (!seenPackages.add(pkg)) continue

            val label = runCatching {
                pm.getApplicationLabel(appInfo).toString().trim()
            }.getOrDefault(pkg).ifEmpty { pkg }

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val special = classifySpecialGroup(pkg, label)
            val hasLauncher = launcherPackages.contains(pkg)

            mapped.add(
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    uid = appInfo.uid,
                    isSystemApp = isSystem,
                    hasLauncherIcon = hasLauncher,
                    specialGroup = special
                )
            )
        }

        // Sort user apps first, then alphabetical by display name.
        return mapped.sortedWith(
            compareBy<InstalledAppInfo> { it.isSystemApp }
                .thenBy { it.appName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
        )
    }

    fun isGooglePackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.startsWith("com.google.") ||
            lower == "com.android.vending" ||
            lower == "com.android.chrome" ||
            GEMINI_COMPANION_PACKAGES.contains(pkg) ||
            PLAY_STORE_COMPANION_PACKAGES.contains(pkg) ||
            GOOGLE_COMPANION_PACKAGES.contains(pkg)
    }

    fun isMicrosoftPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.startsWith("com.microsoft.") ||
            lower.startsWith("com.skype.") ||
            lower.startsWith("com.linkedin.") ||
            lower.startsWith("com.ms.")
    }

    fun isSystemOrCarrierPackage(pkg: String, isSystemApp: Boolean): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return isSystemApp ||
            lower == "com.android.settings" ||
            lower.startsWith("com.android.settings.") ||
            lower.startsWith("android") ||
            lower.startsWith("com.android.systemui") ||
            lower.startsWith("com.samsung.") ||
            lower.startsWith("com.sec.") ||
            lower.startsWith("com.qualcomm.")
    }

    fun isUserApp(app: InstalledAppInfo): Boolean {
        return !isSystemOrCarrierPackage(app.packageName, app.isSystemApp)
    }

    fun isPersonalApp(pkg: String, isSystemApp: Boolean): Boolean {
        return !isGooglePackage(pkg) && !isMicrosoftPackage(pkg) && !isSystemOrCarrierPackage(pkg, isSystemApp)
    }

    /**
     * Returns only personal, non-system apps for the VPN allowlist preset.
     * Google, Microsoft, carrier, and system apps remain on the phone's normal network.
     */
    fun resolvePersonalOnlyBypassPackages(apps: List<InstalledAppInfo>): Set<String> =
        apps.asSequence()
            .filter { it.packageName != "com.xconflictionx.xprivacyguard" && isPersonalApp(it.packageName, it.isSystemApp) }
            .map { it.packageName }
            .toSet()

    /**
     * Always include xPrivacyGuard so it can fetch remote filter lists through xServer,
     * plus any installed first-party xServer companion apps (such as xAssistant).
     * Every other package must be explicitly selected and currently installed.
     */
    fun buildAllowedPackages(
        selectedPackages: Set<String>,
        ownPackage: String,
        installedPackages: Set<String>
    ): Set<String> = (selectedPackages + ALWAYS_TUNNELED_PACKAGES + ownPackage)
        .filter {
            !PHONE_LINK_BYPASS_PACKAGES.contains(it) &&
                (it == ownPackage || installedPackages.contains(it))
        }
        .toSortedSet()
}
