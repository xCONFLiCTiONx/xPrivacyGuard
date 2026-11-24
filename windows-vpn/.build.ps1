[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$RuntimeIdentifier = "win-x64",
    [switch]$RebuildNative
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot
$installDir = Join-Path $env:LOCALAPPDATA "xPrograms\xPrivacyGuard"
$artifactsDir = Join-Path $repoRoot "artifacts\$RuntimeIdentifier"
$iconSrc = Join-Path $repoRoot "src\xPrivacyGuard\Assets\xprivacyguard.ico"

Write-Host "==> Building xPrivacyGuard for Windows ($Configuration / $RuntimeIdentifier)..." -ForegroundColor Cyan

# Stop any running instance cleanly before deploying
$proc = Get-Process -Name "xPrivacyGuard","xPrivacyVPN" -ErrorAction SilentlyContinue
if ($proc) {
    $proc | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
}
# Clean stale xPrivacyGuard.* artifacts if present
if (Test-Path $installDir) {
    Get-ChildItem $installDir -Filter "xPrivacyGuard.*" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
}

$buildArgs = @(
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $repoRoot "scripts\windows\build-test.ps1"),
    "-Configuration", $Configuration,
    "-RuntimeIdentifier", $RuntimeIdentifier
)
if (-not $RebuildNative) {
    $buildArgs += "-SkipNativeRebuild"
}

& powershell @buildArgs
if ($LASTEXITCODE -ne 0) {
    throw "Build/publish failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Path $installDir -Force | Out-Null
Get-ChildItem $artifactsDir | ForEach-Object {
    Copy-Item $_.FullName -Destination $installDir -Recurse -Force
}
if (Test-Path $iconSrc) {
    Copy-Item $iconSrc -Destination (Join-Path $installDir "icon.ico") -Force
}

Write-Host "[OK] Deployed xPrivacyGuard to $installDir" -ForegroundColor Green
