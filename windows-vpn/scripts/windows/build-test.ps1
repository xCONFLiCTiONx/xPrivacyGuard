[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$RuntimeIdentifier = "win-x64",
    [string]$AetherCommit = "ef06971101ba3d4b30f0bb317a2bba81c9b55ca2",
    [switch]$SkipNativeRebuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$nativeRoot = Join-Path $PSScriptRoot "_deps"
$aetherRoot = Join-Path $nativeRoot "Aether"
$patchFile = Join-Path $repoRoot "native\aether\patches\0001-windows-raw-ip-tunnel-ffi.patch"
$nativeBinDir = Join-Path $repoRoot "native\bin\$RuntimeIdentifier"
$publishRoot = Join-Path $repoRoot "artifacts\$RuntimeIdentifier"
$project = Join-Path $repoRoot "src\xPrivacyGuard\xPrivacyGuard.csproj"

$env:PATH = "$env:ProgramFiles\NASM;$env:LOCALAPPDATA\bin\NASM;$env:USERPROFILE\.cargo\bin;$env:PATH"
if (-not $env:LIBCLANG_PATH) {
    $clangCandidates = @(
        "$env:ProgramFiles\LLVM\bin",
        "$env:ProgramFiles\Microsoft Visual Studio\18\Community\VC\Tools\Llvm\x64\bin"
    )
    $ndkDir = "$env:LOCALAPPDATA\Android\Sdk\ndk"
    if (Test-Path $ndkDir) {
        $clangCandidates += Get-ChildItem $ndkDir -Directory | ForEach-Object { Join-Path $_.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin" }
    }
    $foundClang = $clangCandidates | Where-Object { Test-Path (Join-Path $_ "libclang.dll") } | Select-Object -First 1
    if ($foundClang) { $env:LIBCLANG_PATH = $foundClang }
}

foreach ($tool in @("git", "dotnet")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' is missing."
    }
}

New-Item -ItemType Directory -Force -Path $nativeRoot, $nativeBinDir, $publishRoot | Out-Null

$aetherDll = Join-Path $nativeBinDir "aether.dll"
if (-not $SkipNativeRebuild -or -not (Test-Path $aetherDll)) {
    if (-not (Get-Command "cargo" -ErrorAction SilentlyContinue)) {
        throw "Required tool 'cargo' is missing. Install Rust MSVC toolchain before building aether.dll."
    }
    $rustTarget = "x86_64-pc-windows-msvc"
    $installedTargets = & rustup target list --installed 2>$null
    if ($LASTEXITCODE -ne 0 -or $installedTargets -notcontains $rustTarget) {
        throw "Rust target $rustTarget is not installed. Run: rustup target add $rustTarget"
    }

    if (-not (Test-Path (Join-Path $aetherRoot ".git"))) {
        & git clone https://github.com/CluvexStudio/Aether.git $aetherRoot
        if ($LASTEXITCODE -ne 0) { throw "Could not clone Aether." }
    }
    & git -C $aetherRoot fetch --depth 1 origin $AetherCommit
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch pinned Aether commit." }
    & git -C $aetherRoot checkout --force --detach $AetherCommit
    if ($LASTEXITCODE -ne 0) { throw "Could not check out pinned Aether commit." }

    if (Test-Path $patchFile) {
        & git -C $aetherRoot apply --whitespace=nowarn $patchFile
        if ($LASTEXITCODE -ne 0) { throw "Failed to apply Windows raw-IP FFI patch: $patchFile" }
    }

    $cargoManifest = Join-Path $aetherRoot "aether\Cargo.toml"
    & cargo build --release --target $rustTarget --manifest-path $cargoManifest
    if ($LASTEXITCODE -ne 0) { throw "Aether native build failed." }

    $builtDll = Join-Path $aetherRoot "aether\target\$rustTarget\release\aether.dll"
    if (-not (Test-Path $builtDll)) { throw "Aether build did not produce $builtDll" }
    Copy-Item $builtDll $aetherDll -Force
}

$dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
if ($dumpbin) {
    $exports = & $dumpbin.Source /nologo /exports $aetherDll
    $required = @(
        "aether_version", "aether_string_free", "aether_job_poll", "aether_job_cancel",
        "aether_job_free", "aether_identity_open", "aether_identity_free",
        "aether_team_code_request", "aether_team_code_submit", "aether_team_code_resend",
        "aether_team_session_free", "aether_team_token_set",
        "aether_verify_start", "aether_scan_start",
        "aether_raw_tunnel_start", "aether_raw_tunnel_send", "aether_raw_tunnel_recv",
        "aether_raw_tunnel_is_alive", "aether_raw_tunnel_migrate", "aether_raw_tunnel_stop"
    )
    foreach ($symbol in $required) {
        if (($exports -join "`n") -notmatch "(?m)\b$([regex]::Escape($symbol))\b") {
            throw "Aether DLL is missing required export: $symbol"
        }
    }
    Write-Host "Verified all 20 required Aether native exports in $aetherDll"
}

& dotnet publish $project -c $Configuration -r $RuntimeIdentifier --self-contained false -p:UseAppHost=true -o $publishRoot
if ($LASTEXITCODE -ne 0) { throw "WPF application publish failed." }

Copy-Item $aetherDll (Join-Path $publishRoot "aether.dll") -Force
$wintunDll = Join-Path $nativeBinDir "wintun.dll"
if (Test-Path $wintunDll) {
    Copy-Item $wintunDll (Join-Path $publishRoot "wintun.dll") -Force
}

Write-Host "Published verified xPrivacyGuard Windows package to $publishRoot"
