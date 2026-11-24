param(
    [string[]]$Targets = @("arm64-v8a", "x86_64"),
    [int]$ApiLevel = 26
)

Clear-Host
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$AllowedBranches = @("main", "feature/cloudflare-one-masque")
$CurrentBranch = (git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read the current Git branch."
}
if ($AllowedBranches -notcontains $CurrentBranch) {
    throw "Refusing to build Aether on branch '$CurrentBranch'. Allowed: $($AllowedBranches -join ', ')."
}

$AetherPath = Join-Path $RepoRoot "third_party/Aether"
$Manifest = Join-Path $AetherPath "aether/Cargo.toml"
$OutputDir = Join-Path $RepoRoot "app/src/main/jniLibs"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Git is required."
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "Rust Cargo is required."
}
if (-not (Get-Command rustup -ErrorAction SilentlyContinue)) {
    throw "rustup is required to verify Android Rust targets."
}
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    throw "cargo-ndk is required. Install it with: cargo install cargo-ndk"
}

git submodule update --init --recursive -- third_party/Aether
if ($LASTEXITCODE -ne 0) {
    throw "Unable to initialize the Aether submodule."
}
if (-not (Test-Path $Manifest)) {
    throw "Aether Cargo manifest not found: $Manifest"
}

$PatchDir = Join-Path $RepoRoot "patches/aether"
$AetherPatches = @(Get-ChildItem -Path $PatchDir -Filter "*.patch" | Sort-Object Name)
if ($AetherPatches.Count -eq 0) {
    throw "Required Android patches are missing in: $PatchDir"
}
Push-Location $AetherPath
try {
    foreach ($Patch in $AetherPatches) {
        git apply --reverse --check $Patch.FullName 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Aether patch already applied: $($Patch.Name)"
        } else {
            git apply --check $Patch.FullName
            if ($LASTEXITCODE -ne 0) {
                throw "Aether patch does not apply cleanly to the pinned Aether revision: $($Patch.Name)"
            }
            git apply $Patch.FullName
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to apply Aether patch: $($Patch.Name)"
            }
            Write-Host "Applied Aether patch: $($Patch.Name)"
        }
    }
} finally {
    Pop-Location
}

$TargetMap = @{
    "arm64-v8a" = "aarch64-linux-android"
    "x86_64" = "x86_64-linux-android"
    "armeabi-v7a" = "armv7-linux-androideabi"
}
foreach ($Abi in $Targets) {
    if (-not $TargetMap.ContainsKey($Abi)) {
        throw "Unsupported Android ABI '$Abi'. Supported: $($TargetMap.Keys -join ', ')"
    }
}

$InstalledTargets = @(rustup target list --installed)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to query installed Rust targets."
}
foreach ($Abi in $Targets) {
    $RustTarget = $TargetMap[$Abi]
    if ($InstalledTargets -notcontains $RustTarget) {
        throw "Rust target '$RustTarget' is missing. Install it with: rustup target add $RustTarget"
    }
}

if (-not $env:ANDROID_NDK_HOME -and -not $env:ANDROID_NDK_ROOT -and -not $env:NDK_HOME -and -not $env:ANDROID_HOME) {
    throw "Set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT / NDK_HOME / ANDROID_HOME) to an installed Android NDK."
}

# Normalize NDK compiler and toolchain paths with forward slashes and .exe on Windows
# so boring-sys's CMake invocation does not fail on unescaped backslashes.
$ResolvedNdk = $env:ANDROID_NDK_HOME
if (-not $ResolvedNdk) { $ResolvedNdk = $env:ANDROID_NDK_ROOT }
if (-not $ResolvedNdk) { $ResolvedNdk = $env:NDK_HOME }
if ($ResolvedNdk -and (Test-Path $ResolvedNdk)) {
    $NdkForward = $ResolvedNdk.Replace('\', '/')
    $NdkToolchainBin = "$NdkForward/toolchains/llvm/prebuilt/windows-x86_64/bin"
    $ToolchainFile = "$NdkForward/build/cmake/android.toolchain.cmake"
    if (Test-Path "$NdkToolchainBin/clang.exe") {
        foreach ($RustTarget in $TargetMap.Values) {
            [System.Environment]::SetEnvironmentVariable("CC_$RustTarget", "$NdkToolchainBin/clang.exe", "Process")
            [System.Environment]::SetEnvironmentVariable("CXX_$RustTarget", "$NdkToolchainBin/clang++.exe", "Process")
        }
        $env:ANDROID_NDK_HOME = $NdkForward
    }
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$CargoNdkArgs = @()
foreach ($Abi in $Targets) {
    $CargoNdkArgs += @("-t", $Abi)
}
$CargoNdkArgs += @(
    "--platform", "$ApiLevel",
    "-o", $OutputDir,
    "build",
    "--lib",
    "--release"
)

Write-Host "Building Aether for Android: $($Targets -join ', ')" -ForegroundColor Cyan
Write-Host "Pinned source: $((git -C $AetherPath rev-parse HEAD).Trim())"
Write-Host "Output: $OutputDir"

Push-Location (Join-Path $AetherPath "aether")
try {
    & cargo ndk @CargoNdkArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Aether Android build failed."
    }
} finally {
    Pop-Location
}

foreach ($Abi in $Targets) {
    $Library = Join-Path (Join-Path $OutputDir $Abi) "libaether.so"
    if (-not (Test-Path $Library)) {
        throw "Build completed but expected library was not produced: $Library"
    }
}

Write-Host "Aether Android libraries built successfully." -ForegroundColor Green
