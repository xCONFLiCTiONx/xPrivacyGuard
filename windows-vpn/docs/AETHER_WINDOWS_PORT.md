# Aether integration plan for Windows

## Reuse from Android

The Android client already carries a platform-neutral raw-IP MASQUE FFI extension in `patches/aether/0002-android-raw-ip-tunnel-ffi.patch`. Its C ABI exposes:

- `aether_raw_tunnel_start`: enroll/open the identity and establish MASQUE CONNECT-IP.
- `aether_raw_tunnel_send`: submit an IP packet to the MASQUE tunnel.
- `aether_raw_tunnel_recv`: retrieve an IP packet from the tunnel.
- `aether_raw_tunnel_is_alive`: detect tunnel liveness.
- `aether_raw_tunnel_migrate`: request transport migration.
- `aether_raw_tunnel_stop`: stop the tunnel and release its task guard.

The Android JNI facade and `AetherMasqueTransport` provide lifecycle examples. The Cloudflare team sign-in, identity enrollment, certificate, endpoint selection, and MASQUE setup logic should be reused where the upstream FFI supports it.

## Revision compatibility warning

The Android raw-IP patch is not to be applied blindly. Its diff header records an expected `aether/src/ffi.rs` base beginning with `dac5666`, while the inspected Aether revision `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2` has a different FFI blob (`d88e6d278491c442fb3c00429fa91f2d95cccf4c`). The Windows port must rebase the raw tunnel extension against a verified Aether commit and compile it; do not assume the historical Android patch applies cleanly.

## Windows-specific requirements

Android's `VpnService.protect(fd)` callback is not portable. Windows must provide an explicit transport-socket bypass so Aether's own MASQUE connection cannot be captured and fed back into its own tunnel. That bypass must be narrow (service identity / owned sockets), observable, and installed before the tunnel opens sockets.

The Windows adapter must bridge the raw-IP FFI to the selected Windows packet datapath. Aether's raw FFI is only the MASQUE packet carrier; it does not perform Windows process identification, WFP policy, virtual-interface management, or fail-closed enforcement.

## Acceptance tests for the native bridge

- Identity open, Cloudflare Zero Trust authentication, device enrollment, and MASQUE certificate availability.
- MASQUE CONNECT-IP startup with the negotiated addresses and MTU.
- Bidirectional IPv4 and IPv6 packet delivery.
- UDP and TCP application traffic through the Windows packet path.
- Correct transport-socket bypass without allowing ordinary selected applications to bypass the tunnel.
- Liveness failure detection, reconnect, and migration behavior.
- Clean stop and release of all Rust task/identity handles.
