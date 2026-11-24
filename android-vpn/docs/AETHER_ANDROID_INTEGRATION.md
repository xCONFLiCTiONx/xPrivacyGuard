# Aether Android integration assessment

Status: upstream source pinned; Android integration design verified; no native build or device test claimed yet.

## Pinned upstream

- Repository: https://github.com/CluvexStudio/Aether
- Revision: `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2`
- Location: `third_party/Aether` (Git submodule)
- Aether crate: `third_party/Aether/aether/Cargo.toml`
- Quiche source: `third_party/Aether/quiche`

The source is kept as an upstream submodule. Do not silently edit files inside it; any required upstream patch must be explicit and tracked.

## Verified FFI surface

Aether exposes a C ABI from `aether/src/ffi.rs`, including:

- `aether_identity_open` / `aether_identity_summary` / `aether_identity_free`
- `aether_verify_start`
- `aether_tunnel_start`
- `aether_job_poll` / `aether_job_cancel` / `aether_job_free`
- `aether_team_sign_in`, `aether_team_code_request`, `aether_team_code_resend`, `aether_team_code_submit`
- `aether_team_token_set` / `aether_team_token_clear`
- `aether_string_free`

The functions exchange JSON through C strings and return asynchronous job handles. The repository does not currently ship a public C header for this FFI, so xPrivacyGuard will need a small, checked-in header matching the exact exported signatures.

## Critical data-plane finding

Aether is not a raw-IP packet channel that can directly replace `WireGuardSession` in the current Android TUN loop.

Its `api::connect()` implementation binds the configured local SOCKS listener (`TunnelSpec.socks`) and then runs the MASQUE tunnel behind that listener. The Android app therefore needs a TUN-to-SOCKS adapter to translate eligible IP flows into SOCKS5 TCP/UDP sessions.

The existing `XPrivacyGuardService` reads raw IP packets from Android's TUN, runs `PacketInspector.inspectOutboundPacket()`, handles filtered/DNS packets, and then encrypts remaining packets with WireGuard. The safe integration point is after inspection: blocked packets and locally handled DNS must remain local; allowed TCP/UDP flows must be forwarded through the Aether SOCKS endpoint; return traffic must be reconstructed and written to the same TUN.

That adapter is a real TCP/IP networking component, not a simple socket-forwarding substitution. Do not bypass the packet inspector or claim feature parity until TCP, UDP, IPv4, IPv6, fragmentation, MTU, DNS, and teardown behavior are tested.

## Android socket protection requirement

Aether's `egress.rs` centralizes outgoing TCP and UDP socket creation and applies `AETHER_MARK` when configured. Its QUIC path also calls the same socket-marking helper. On Android, the mark operation requires privileges that an ordinary app does not have.

When Aether runs inside xPrivacyGuard's process, its Cloudflare-facing sockets must bypass xPrivacyGuard's own TUN or they can route back into the VPN and loop. Android provides `VpnService.protect(fd)` for this purpose, but the pinned upstream FFI does not expose a socket-protection callback.

Before enabling the Aether tunnel under the Android VPN service, we must add a tracked Android integration patch (or a maintained xPrivacyGuard fork) that invokes a registered native socket-protection callback before connecting/binding each outbound socket. Do not rely on `AETHER_MARK` as an unprivileged Android workaround.

## Implementation sequence

1. **Native build wiring committed:** `scripts/build-aether-android.ps1` builds the Aether library for Android ARM64 and x86-64 into `app/src/main/jniLibs`; `app/build.gradle.kts` configures CMake and those ABIs. The script explicitly builds `--lib`, not the upstream command-line executable.
2. **JNI bridge committed:** `app/src/main/cpp/aether_ffi.h` declares the pinned FFI signatures, `aether_jni.cpp` wraps the C ABI, and `AetherNative.kt` exposes identity, scan, verify, tunnel, job, and team-auth calls. All FFI replies remain JSON strings.
3. Add the Android `VpnService.protect(fd)` callback to Aether's outbound socket creation path, tracked as an explicit upstream integration patch.
4. Implement the TUN-to-SOCKS adapter after `PacketInspector`, preserving current DNS handling, filtering, logging, app selection, and firewall behavior.
5. Keep the existing WireGuard transport selectable until Aether MASQUE passes device-level connection, traffic, reconnect, sleep/wake, IPv4/IPv6, DNS, and leak tests.

The native build, JNI compilation, and device execution have **not** been run in this remote repository-editing session. The Kotlin facade now includes `awaitJob()` to poll asynchronous FFI jobs, unwrap the completed JSON result, surface native errors, cancel on timeout, and release job handles in `finally`. It does not imply that socket protection or packet forwarding is implemented.

## Current test status

- Git submodule pointer: committed and pinned.
- Aether native Android build: script and Gradle/CMake wiring committed; not run.
- Kotlin/JNI bridge: source committed, including asynchronous job lifecycle helper; not compiled or device-tested.
- Socket protection callback: not implemented.
- TUN-to-SOCKS adapter: not implemented.
- End-to-end Cloudflare test: not run.

This status is intentional: source inclusion is complete, but the app must not report an Aether/Cloudflare connection before the native tunnel and packet path are actually working.

## Android socket-protection implementation contract

The pinned Aether source file aether/src/egress.rs confirms that egress::apply(SockRef) is called before the TCP connect and UDP bind helpers. Its current Android path only applies the optional Linux socket mark (SO_MARK); that is not a substitute for VpnService.protect(fd) in an unprivileged app.

The Android patch must add a process-wide, thread-safe C callback registration point to the Aether FFI. The callback contract is:

- Input: the already-created socket's raw file descriptor, before connect or bind.
- Return: success only when Android VpnService.protect(fd) returns true.
- Failure: return an I/O error and abort that socket operation. Never silently continue with an unprotected socket.
- Lifetime: register the callback before starting scan/verify/tunnel jobs; unregister only after all Aether jobs and sockets have stopped.
- Threading: JNI must attach Rust worker threads to the JVM as needed, hold a global reference to the live service, and release it during teardown. Do not retain a local JNI reference or call through a destroyed service.
- Coverage: audit every MASQUE/QUIC, TCP, UDP, DNS, and endpoint-probe socket creation path. The egress helpers cover their callers, but a raw socket created elsewhere must be routed through the same protection hook.

The Kotlin side must pass the active XPrivacyGuardService instance to a JNI registration method. The JNI callback calls the public VpnService.protect(int) method and propagates its boolean result to Rust. This is a required upstream integration patch; it is not implemented by the current JNI facade.

## Job cancellation correction

AetherNative.awaitJob() now cancels the native job on every unsuccessful exit path, including coroutine cancellation, timeout, malformed poll responses, and native polling errors, before releasing the job handle. A completed job is only freed. This prevents a canceled Kotlin caller from leaving a Rust worker running in the background.

## Implemented Android socket-protection bridge

The feature branch now contains an upstream-source patch at patches/aether/0001-android-vpnservice-socket-protection.patch. The Android build script applies it to the pinned Aether submodule before compiling. The patch adds a fail-closed socket callback to Aether egress and exports aether_set_android_socket_protector through the C ABI.

The JNI bridge registers a global reference to the active VpnService, attaches Aether worker threads to the JVM when necessary, invokes VpnService.protect(fd), and returns failure to Rust if protection fails or throws. Kotlin exposes registerSocketProtector(service) and unregisterSocketProtector(). Registration must occur before any Aether scan, verification, or tunnel operation; unregister only after Aether jobs and sockets have stopped.

## Direct Raw-IP MASQUE Tunnel FFI (Zero-SOCKS Architecture)

Inspecting `third_party/Aether/aether/src/quic.rs` and `modes/masque.rs` confirmed that Aether's MASQUE core (`quic::run` for HTTP/3 QUIC and `masque_h2::run` for HTTP/2 fallback) already operates directly on raw IPv4/IPv6 packets through `quic::Channels` (`outbound_tx` / `inbound_rx` / `ctrl_tx`). Aether only attaches `smoltcp` + SOCKS5 on top for desktop CLI proxy users.

Instead of building a double-encapsulated `TUN -> userspace TCP/IP -> SOCKS5 -> smoltcp -> IP -> MASQUE` translator, xPrivacyGuard tracks a second minimal upstream patch:

- `patches/aether/0001-android-vpnservice-socket-protection.patch`: Fail-closed Android `VpnService.protect(fd)` callback in `egress::apply()` plus `aether_set_android_socket_protector`.
- `patches/aether/0002-android-raw-ip-tunnel-ffi.patch`: Direct raw-IP MASQUE CONNECT-IP C ABI (`aether_raw_tunnel_start`, `aether_raw_tunnel_send`, `aether_raw_tunnel_recv`, `aether_raw_tunnel_is_alive`, `aether_raw_tunnel_migrate`, `aether_raw_tunnel_stop`).

This gives Android native kernel handling for IPv4, IPv6, TCP, UDP, ICMP, QUIC, MTU, and fragmentation while keeping `PacketInspector`, ad blocking, Zero Trust DNS, per-app firewall, traffic counters, network migration, and fail-closed reconnect behavior identical to WireGuard. WireGuard remains selectable alongside Cloudflare One MASQUE in the Tunnel tab.
