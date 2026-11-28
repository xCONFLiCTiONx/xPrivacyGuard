# xPrivacyGuard

Unified privacy, Zero Trust VPN, ad/tracker filtering, and browser protection suite.

## Modules

| Directory | Component | Build / Deploy |
|---|---|---|
| `android-vpn/` | **xPrivacyGuard (Android)** — Cloudflare One MASQUE & WireGuard VPN, Zero Trust DNS, per-app split tunnel, and filter engine | `.\android-vpn\.build.ps1` |
| `android-vpn-lite/` | **xPrivacyGuard Lite (Android)** — Lightweight local DNS firewall & ad blocker | `.\android-vpn-lite\.build.ps1` |
| `windows-vpn/` | **xPrivacyGuard (Windows)** — Always-on Cloudflare One MASQUE VPN, Wintun + WFP kill switch, split-tunnel exclusions | `.\windows-vpn\.build.ps1` / `.\windows-vpn\.build-Installer.ps1` |
| `chrome-extension/` | **xPrivacyGuard (Chrome Extension)** — Fingerprint protection, GPC, cosmetic/network ad & tracker blocking | `.\chrome-extension\.build.ps1` |
| `privacy-filters/` | **Privacy Filters** — Curated filter lists hosted at `https://filters.xserver.local/<list>/filters.txt` | Synced to xServer |
| `assets/` | **Unified Brand Assets** — Canonical `icon.svg`, `icon-512.png`, and `icon.ico` shared across all platforms | — |

## Design System

All components share a single unified developer dark theme and icon:
- **Background**: `#121214` / Surface `#1A1A1E` / Elevated `#222227` / Border `#2C2C33`
- **Accent**: `#F38020` (highlight `#FAAD3F`)
- **Status Colors**: Connected / Success `#22C55E` (Green) · Connecting / Warning `#FAAD3F` (Orange/Yellow) · Disconnected / Error `#EF4444` (Red)
