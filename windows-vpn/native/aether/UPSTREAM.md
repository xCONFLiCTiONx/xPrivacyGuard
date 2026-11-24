# Native Aether integration status

## Verified upstream revision

- Repository: https://github.com/CluvexStudio/Aether
- Exact upstream commit: `ef06971101ba3d4b30f0bb317a2bba81c9b55ca2`
- Package version: `2.4.0`
- License: `AGPL-3.0-only` (this is a third-party component; the Windows app's GPL-3.0 license does not replace or relicense Aether).

The pinned source exports the identity provisioning and Cloudflare Zero Trust email-code APIs used by `AetherIdentity.cs`: `aether_identity_open`, `aether_team_code_request`, `aether_team_code_submit`, `aether_team_code_resend`, `aether_team_session_free`, `aether_team_token_set`, plus the common job and string-management functions.

## Important blocker: raw packet tunnel API is not upstream

The pinned upstream `aether/src/ffi.rs` does **not** export any of these:
- `aether_raw_tunnel_start`
- `aether_raw_tunnel_send`
- `aether_raw_tunnel_recv`
- `aether_raw_tunnel_is_alive`
- `aether_raw_tunnel_migrate`
- `aether_raw_tunnel_stop`

Those names are declared in our header and managed wrapper only as the target ABI for the Windows port. The Android reference patch was authored against a different FFI source blob and cannot be applied blindly. It also relies on Android-specific socket protection, which must be replaced with Windows socket-bypass behavior.

Consequently, building the unmodified upstream DLL will **not** make `AetherRawTunnel` callable. Do not package it as a VPN or expose a Connect button until the raw packet interface is ported and the WFP/Wintun datapath is complete.

## Windows build prerequisites

- Windows 10/11 x64
- Visual Studio 2022 Build Tools with the Desktop development with C++ workload and Windows SDK
- Rust stable MSVC toolchain (`x86_64-pc-windows-msvc`)
- .NET 8 SDK

The upstream source is AGPL-3.0-only. Preserve its license and notices in any distribution. Review the license obligations before distributing a combined binary.

## Implementation sequence

1. Port the raw-IP channel from the Android patch to this exact Aether commit; replace Android egress socket protection with a Windows-specific bypass mechanism.
2. Build Aether as `aether.dll` for x86_64-pc-windows-msvc and verify every raw-IP export.
3. Implement the Windows packet adapter and route assigned IPv4/IPv6 addresses through it.
4. Implement application identity enforcement and fail-closed policy in Windows Filtering Platform; a Wintun adapter alone does not provide per-process routing.
5. Verify TCP, UDP, DNS, IPv4, IPv6, LAN exclusions, transport socket bypass, disconnect behavior, and reboot/service lifecycle on a real Windows machine.

## Current status

The managed identity/authentication API binding is implemented. The raw-IP C# wrapper is an ABI target, not yet a callable integration. No Windows-native build has been run in this environment, and there is no safe installable VPN build yet.
