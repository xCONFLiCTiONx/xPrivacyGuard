# xPrivacyGuard

> **Comprehensive Privacy, Network Filtering, & Anti-Fingerprinting Suite**

**xPrivacyGuard** is an end-to-end multi-platform privacy ecosystem that protects your network traffic and web browsing across mobile and desktop devices. It combines a native **Android Privacy Firewall & Local VPN App**,  and an advanced **Desktop Browser Extension**.

---

## 📱 1. Android Privacy Firewall & Encrypted DNS App

Located in [`app/`](file:///E:/xPrivacyGuard/app), **xPrivacyGuard for Android** is a high-performance local privacy firewall and encrypted DNS proxy app built using Kotlin, Jetpack Compose (Material 3), and Android's native `VpnService` loopback interface. It operates 100% on-device for privacy without routing your internet traffic through external VPN servers.

### 🌟 Key Features

- **Local Privacy Loopback Engine (`VpnService`)**: Intercepts and filters IPv4 / IPv6 DNS traffic (UDP/TCP Port 53) locally on-device. All standard data traffic remains direct and untouched.
- **Embedded DNS Proxy Engine (DoT)**: Proxies DNS queries securely over **DNS-over-TLS (DoT, Port 853)** with zero-leak protection and instant `NXDOMAIN` spoofing for blocked domains. Ships with pre-configured high-security DoT gateways including Cloudflare Security (`security.cloudflare-dns.com`), AdGuard DNS, Quad9, Mullvad, and Control D.
- **Remote Filter List & Blocklist Engine**:
  - Subscribes to and downloads remote AdBlock / Hosts lists (AdGuard, StevenBlack, OISD, etc.) with automatic background updates, caching, and debounced auto-apply.
  - Supports custom remote filter list URLs, domain syntax, and wildcard rule matching.
  - Custom user blocklists and allowlists.
- **Per-App Firewall & Split Tunneling**: Toggle internet access per installed application, or exclude specific apps from the VPN tunnel.
- **Real-Time Analytics & Diagnostics**:
  - Live query logging with domain resolution status (Allowed vs. Blocked).
  - Built-in DNS Leak Tester (`LeakTester`).
  - Active `VpnWatchdog` for automatic connection healing and stalled tunnel detection.
  - Integrated filtering self-test against the running proxy engine.
- **Automations & System Integration**: Android boot receiver (`RECEIVE_BOOT_COMPLETED`), network change listeners, and background foreground service notifications.

### 🛠️ Building & Running (Android App)

1. Open `E:/xPrivacyGuard` (or the project root) in **Android Studio**.
2. Ensure you have Android SDK 34 (Android 14) installed.
3. Build & run the project via Android Studio, or execute:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install `app-debug.apk` onto your device or emulator and grant the VPN permission on first launch.

---
