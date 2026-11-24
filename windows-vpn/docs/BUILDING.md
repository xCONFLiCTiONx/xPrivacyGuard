# Building xPrivacyGuard for Windows

## Requirements

- Windows 10/11 x64
- .NET 8 SDK (or newer, with Windows Desktop workload)
- Visual Studio Build Tools (Desktop development with C++ and Windows SDK)
- Rust stable MSVC toolchain (`x86_64-pc-windows-msvc`)
- NASM (`winget install NASM.NASM`) for BoringSSL assembly when rebuilding `aether.dll`

## Quick Build (WPF App + Bundled Native DLLs)

```powershell
dotnet build .\xPrivacyGuard.Windows.sln -c Release
```

When `native\bin\win-x64\aether.dll` and `native\bin\win-x64\wintun.dll` are present, they are copied automatically to the build output directory.

## Full Native + Package Build

From a Visual Studio Developer PowerShell:

```powershell
.\scripts\windows\build-test.ps1
```

This script:
1. Checks out the pinned Aether commit (`ef06971101ba3d4b30f0bb317a2bba81c9b55ca2`).
2. Applies `native\aether\patches\0001-windows-raw-ip-tunnel-ffi.patch`.
3. Builds `aether.dll` for `x86_64-pc-windows-msvc`.
4. Verifies all 20 required native exports (`aether_identity_*`, `aether_team_*`, `aether_verify_start`, `aether_scan_start`, `aether_raw_tunnel_*`) with `dumpbin.exe`.
5. Publishes `xPrivacyGuard.Windows.exe` alongside `aether.dll` and `wintun.dll` to `artifacts\win-x64\`.

Use `-SkipNativeRebuild` to republish the WPF app without recompiling `aether.dll`.
