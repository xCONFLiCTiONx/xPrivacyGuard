# xPrivacyGuard for Windows

A native Windows companion to [xPrivacyGuard](https://github.com/xCONFLiCTiONx/xPrivacyGuard) for Android.

## Features

- **Cloudflare One / Zero Trust (MASQUE via Aether)**:
  - Email one-time-code sign-in (`Request Code` → `Verify & Enroll`) and Service Token authentication.
  - Persistent enrolled device identity and MASQUE certificate storage.
  - Native raw-IP MASQUE CONNECT-IP tunnel over HTTP/3 (QUIC) with HTTP/2 TCP fallback and ECH option.
  - Fast parallel Cloudflare anycast gateway probing with automatic port-scan fallback.
- **Windows Packet Datapath (Wintun + WFP)**:
  - Layer-3 Wintun virtual adapter with automatic IPv4/IPv6 addressing, MTU 1280, DNS configuration, MASQUE peer host-route pinning, and RFC1918 LAN bypass.
  - Windows Filtering Platform (WFP) dynamic session enforcing transport-socket bypass (for `xPrivacyGuard.Windows.exe`), include-based per-application routing, optional full-traffic mode (preserving saved app selections), and fail-closed kill switch.
- **xPrivacy Guard Packet Inspection & Filtering**:
  - Remote filter list URLs (with GitHub/GitLab raw normalization), local `.txt` imports, and custom user rules.
  - Adblock Plus / uBlock / AdGuard / hosts-file domain, exception, wildcard, and URL-path rule engine with interactive Rule Tester.
  - Live outbound DNS (UDP/53) filtering with `0.0.0.0` or `NXDOMAIN` replies, TLS ClientHello SNI inspection (TCP/443), and HTTP Host/Path inspection (TCP/80).
  - Real-time Traffic Activity log and blocked/allowed/throughput counters.
- **Zero Trust & Split/Private DNS**:
  - Cloudflare Gateway DNS Location (IPv4, DoH, DoT) and Split/Private DNS (e.g., `internal.example.com` → local DNS server).
- **xServer / xSecurity Integration & Desktop UX**:
  - Windows device posture reporting (Defender, Windows Firewall, Secure Boot, UAC, Admin status) and sync to xSecurity.
  - Cyber Dark WPF interface, system-tray operation with close-to-tray, startup-at-login (`--background`), persistent window geometry, and JSON settings backup export/import.

## Building

See [docs/BUILDING.md](docs/BUILDING.md) for full build instructions.

## Licensing

The project is licensed under GPL-3.0. Third-party components retain their own licenses and notices (Aether is AGPL-3.0-only; Wintun is GPL-2.0 / prebuilt signed binary). See [LICENSE](LICENSE).
