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

# Sign xPrivacyGuard.exe if xCONFLiCTiONx code-signing cert is available
$signCert = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue |
    Where-Object { $_.Subject -match 'CN=xCONFLiCTiONx' } |
    Sort-Object NotAfter -Descending | Select-Object -First 1
$signTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
if ($signCert -and $signTool) {
    foreach ($targetExe in @((Join-Path $artifactsDir "xPrivacyGuard.exe"), (Join-Path $installDir "xPrivacyGuard.exe"))) {
        if (Test-Path $targetExe) {
            & $signTool.FullName sign /sha1 $signCert.Thumbprint /fd SHA256 /d "xPrivacyGuard" /du "https://xconflictionx.cc" /tr "http://timestamp.digicert.com" /td SHA256 $targetExe | Out-Null
            if ($LASTEXITCODE -ne 0) {
                & $signTool.FullName sign /sha1 $signCert.Thumbprint /fd SHA256 /d "xPrivacyGuard" /du "https://xconflictionx.cc" $targetExe | Out-Null
            }
        }
    }
    Write-Host "[OK] Signed xPrivacyGuard.exe with xCONFLiCTiONx ($($signCert.Thumbprint))" -ForegroundColor Green
}

Write-Host "[OK] Deployed xPrivacyGuard to $installDir" -ForegroundColor Green
