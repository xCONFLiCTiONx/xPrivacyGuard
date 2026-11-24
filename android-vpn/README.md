# xPrivacyGuard

**xPrivacyGuard is an Android-first privacy and Cloudflare One client that brings authenticated Cloudflare Zero Trust connectivity, native MASQUE tunnelling, and on-device traffic filtering together inside one Android `VpnService`.**

It is not a wrapper around the official Cloudflare One app, and it does not require WireGuard to carry the Cloudflare One connection. xPrivacyGuard integrates the open-source **Aether** networking core directly and connects it to Android's native VPN packet interface.

The project combines three things that are usually separate:

- **Authenticated Cloudflare connectivity** — Cloudflare Zero Trust device enrollment and MASQUE CONNECT-IP.
- **A real Android VPN and packet pipeline** — Android `VpnService`, per-app routing, packet inspection, and transport lifecycle management.
- **Built-in privacy controls** — DNS filtering, remote and local filter lists, TLS SNI / HTTP host-path inspection, and activity telemetry.

> **Maintainer field test:** Successful Cloudflare Zero Trust authentication/device enrollment and a persistent Cloudflare MASQUE connection have been verified on a physical Android phone. Compatibility across other devices, Android versions, and network environments continues to be tested.

---

## Why this project is different

Most Android VPN clients focus on carrying packets through a tunnel. xPrivacyGuard also owns the packet-processing and privacy layer around that tunnel.

Cloudflare One authentication, device identity, and MASQUE transport are integrated into the app rather than delegated to a separate WireGuard profile or the official Cloudflare client. The same Android VPN service remains in control of app routing, DNS interception, filtering, packet inspection, and connection state.

The Cloudflare transport is **MASQUE CONNECT-IP**, carried over HTTP/3 (QUIC) by default, with an HTTP/2 option. WireGuard remains available as a separate selectable transport; it is not a prerequisite for Cloudflare MASQUE.

## Features

### Cloudflare One / Zero Trust

- Cloudflare Zero Trust team enrollment and managed-device identity.
- Cloudflare device identity and MASQUE certificate persistence across app restarts.
- Cloudflare MASQUE CONNECT-IP raw-IP tunnel using the Aether Rust core.
- HTTP/3 over QUIC, with configurable HTTP/2 transport.
- Cloudflare gateway selection with prioritized endpoint checks and fallback scanning.
- Android `VpnService.protect(fd)` integration for Aether's outbound sockets, preventing the tunnel's own sockets from looping back into its TUN interface.
- Fail-closed socket protection and tunnel lifecycle handling.
- Persistent enrolled identity metadata so a temporary reconnect or endpoint failure does not unnecessarily discard enrollment.
- Network migration support exposed by the native transport.
- No root required.

### Android VPN and routing

- Native Android `VpnService` TUN interface.
- Per-app routing / firewall selection.
- Separate transport boundary for Cloudflare MASQUE and WireGuard.
- Tunnel lifecycle states distinguish authentication, enrollment, connection, reconnection, and failure.
- WireGuard remains available as an independent transport option.

### xPrivacy Guard filtering

- Multiple remote `filters.txt` sources and local filter-file imports.
- Adblock Plus / uBlock / AdGuard-style domain rules, exceptions, domain-and-path rules, wildcard rules, and hosts-file entries.
- DNS interception and filtering before queries are forwarded.
- TLS ClientHello SNI inspection and HTTP Host/request-path inspection.
- Real-time activity telemetry for allowed and blocked traffic.
- Filtering and packet inspection remain in the Android VPN pipeline regardless of the selected tunnel transport.

### DNS and privacy

- System DNS fallback when custom DNS configuration is not supplied.
- Cloudflare Zero Trust DNS Location configuration, including supported IPv4, IPv6, DoT, and DoH endpoints.
- DNS requests are inspected against active filter lists before upstream resolution.
- Private DNS / private-domain routing options are available in the DNS configuration.

### Android reliability

- Foreground VPN service and connection-state reporting.
- Persisted settings and Cloudflare enrollment metadata.
- Adaptive recovery behavior rather than an unconditional permanent partial wakelock.
- Release builds use R8 code shrinking, obfuscation, and resource optimization.

---

## Architecture

The Android app owns the VPN interface and privacy pipeline. Aether supplies the Cloudflare networking core; a small JNI bridge exposes the native functions to Kotlin.

```text
app/src/main/
├── java/com/xconflictionx/xprivacyguard/
│   ├── config/                 # WireGuard and Cloudflare One configuration
│   ├── dns/                    # DNS configuration and resolution
│   ├── filter/                 # Filter-list parsing, storage, and matching
│   ├── packet/                 # DNS, TLS SNI, HTTP inspection and packet handling
│   ├── ui/                     # Compose UI and application state
│   ├── vpn/                    # Android VpnService and VPN state management
│   └── wireguard/              # WireGuard transport implementation
└── cpp/
    ├── aether_ffi.h            # Pinned Aether C ABI declarations
    ├── aether_jni.cpp          # JNI bridge and Android socket protection callback
    └── CMakeLists.txt

third_party/Aether/             # Upstream Aether Git submodule (pinned revision)
patches/aether/                  # Explicit, reproducible Android integration patches
scripts/build-aether-android.ps1 # Native Aether Android build
```

### Aether integration

xPrivacyGuard uses **[Aether](https://github.com/CluvexStudio/Aether)** as a Git submodule, pinned to a specific upstream revision. Aether is a Rust-based networking core that supports MASQUE and WireGuard and is also used by the cross-platform [Oblivion](https://github.com/bepass-org/oblivion) WARP client.

xPrivacyGuard does **not** embed the Oblivion Android application. It integrates Aether directly into its own Android VPN service and UI.

Two explicit Android integration patches are maintained in this repository:

1. **Android socket protection:** exposes a native callback that calls Android's `VpnService.protect(fd)` for Aether-created outbound sockets. It fails closed if protection is missing or rejected.
2. **Raw-IP MASQUE FFI:** exposes Aether's MASQUE CONNECT-IP packet channels through a small C ABI so Android's TUN packet pipeline can exchange complete IP packets directly, without routing through a desktop SOCKS5 proxy adapter.

The build script applies these patches to the checked-out Aether submodule before compiling it. The upstream Aether repository is not silently rewritten; the integration changes are visible and reviewable in `patches/aether/`.

---

## Requirements

- Android device or emulator running Android 8.0+ (API 26+).
- Android SDK and Android NDK.
- JDK 17.
- Rust stable toolchain and the Android Rust targets being built.
- `cargo-ndk` for building Aether's native Android libraries.
- PowerShell 5.1+ for the supplied Windows build scripts.

The current native build targets are `arm64-v8a` and `x86_64`.

## Build, install, and run

Clone the repository with its Aether submodule:

```powershell
git clone --recurse-submodules https://github.com/xCONFLiCTiONx/xPrivacyGuard.git
cd xPrivacyGuard
```

If the repository was cloned without submodules:

```powershell
git submodule update --init --recursive
```

### 1. Build Aether's Android native libraries

Install the Rust Android targets and `cargo-ndk` if they are not already installed:

```powershell
rustup target add aarch64-linux-android
rustup target add x86_64-linux-android
cargo install cargo-ndk
```

Set `ANDROID_NDK_HOME` (or another supported Android NDK environment variable), then run:

```powershell
.\scripts\build-aether-android.ps1
```

This builds the pinned Aether library for the selected Android ABIs and places the resulting libraries under `app/src/main/jniLibs`. It also applies the tracked Android integration patches to the local submodule checkout.

### 2. Build and install the Release APK

Connect an Android device with USB debugging enabled, then run:

```powershell
.\.build.ps1
```

The script builds the optimized Release APK, installs it with `adb install -r`, and launches the app when a device is available. It does not uninstall the existing app or erase its data if the signing key differs.

To build without a connected device, run the Gradle Release task directly:

```powershell
.\gradlew.bat :app:assembleRelease
```

### 3. Run unit tests

```powershell
.\gradlew.bat testDebugUnitTest
```

---

## Usage

1. Open the **Tunnel** configuration and select **Cloudflare One (MASQUE)** as the active transport.
2. Configure the Cloudflare Zero Trust team and complete the supported authentication/enrollment flow.
3. Allow Android to create the VPN connection when prompted.
4. Connect from the dashboard. The app opens or restores its enrolled Aether device identity, verifies/selects a reachable Cloudflare MASQUE endpoint, and establishes the CONNECT-IP tunnel.
5. Configure per-app routing, DNS, and filter lists as needed.

WireGuard remains selectable for users who want to import and use a WireGuard profile instead.

---

## Security and privacy

- No default Cloudflare account, device identity, or private WireGuard key is bundled.
- Cloudflare enrollment material and user configuration are stored in the app's private storage.
- Aether's outbound sockets must be protected with Android's `VpnService.protect(fd)` before network operations begin; failure to protect a socket aborts the operation.
- Tunnel authentication/enrollment is distinct from tunnel connectivity. The app should not report a connected tunnel until the authenticated transport is established.
- Do not commit access tokens, service-token secrets, private keys, signing credentials, or personal enrollment data.
- xPrivacyGuard's packet inspection and filtering remain in the Android VPN service rather than being delegated to an external proxy.

---

## Upstream projects and acknowledgements

This project builds on the work of the maintainers of these upstream projects:

- **[Aether](https://github.com/CluvexStudio/Aether)** — Rust networking core providing MASQUE, WireGuard, and Cloudflare Zero Trust enrollment functionality. Aether is included as a pinned Git submodule.
- **[Oblivion](https://github.com/bepass-org/oblivion)** — cross-platform unofficial Cloudflare WARP client that also integrates Aether. xPrivacyGuard integrates Aether directly; it does not embed the Oblivion app.
- **[Cloudflare quiche](https://github.com/cloudflare/quiche)** — QUIC and HTTP/3 implementation used by Aether.
- **[BoringTun](https://github.com/cloudflare/boringtun)** and other dependencies — see their upstream notices and the corresponding dependency manifests.

All upstream license files, copyright notices, and third-party terms must remain intact. The Aether submodule retains its upstream **GNU AGPL v3** license; xPrivacyGuard's own project license does not replace the license of that separately maintained component.

---

## Project status

Cloudflare Zero Trust enrollment and a persistent MASQUE connection have been successfully exercised by the maintainer on a physical Android phone. This is an important working milestone, but it is not a claim of universal compatibility or a completed independent security audit.

Testing still needs to cover a wider range of Android devices and versions, Wi-Fi and cellular transitions, sleep/wake, IPv4 and IPv6 behavior, DNS leak resistance, packet loss, reconnect behavior, and sustained throughput under load.

Bug reports, reproducible test results, and thoughtful contributions are welcome.

## License

xPrivacyGuard is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

Third-party components retain their own licenses and notices. In particular, the Aether submodule is licensed under the **GNU Affero General Public License v3.0**. See the upstream [Aether LICENSE](https://github.com/CluvexStudio/Aether/blob/main/LICENSE) and the relevant third-party license files before redistributing builds.
