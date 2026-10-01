<#
.SYNOPSIS
    Builds both Desktop and Mobile target packages for xPrivacyGuard from a single source repository.
.DESCRIPTION
    Copies common, desktop-specific, and mobile-specific modules into build/desktop and build/mobile
    along with their respective manifests, rules.json, and icon assets.
#>

param(
    [string]$OutputDir = "build"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Building xPrivacyGuard (Unified Target)   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$Root = Resolve-Path "$PSScriptRoot\.."
$DesktopBuild = "$Root\$OutputDir\desktop"
$MobileBuild = "$Root\$OutputDir\mobile"

if (Test-Path $DesktopBuild) { Remove-Item -Recurse -Force $DesktopBuild }
if (Test-Path $MobileBuild) { Remove-Item -Recurse -Force $MobileBuild }

New-Item -ItemType Directory -Force -Path $DesktopBuild | Out-Null
New-Item -ItemType Directory -Force -Path $MobileBuild | Out-Null

# ----------------------------------------------------
# 1. Build Desktop Extension Package
# ----------------------------------------------------
Write-Host "[1/2] Building Desktop target..." -ForegroundColor Yellow
Copy-Item -Recurse -Force "$Root\src\desktop\*" $DesktopBuild
Copy-Item -Recurse -Force "$Root\src\common\*" $DesktopBuild
if (Test-Path "$Root\rules.json") {
    Copy-Item -Force "$Root\rules.json" "$DesktopBuild\rules.json"
}
if (Test-Path "$Root\icons") {
    New-Item -ItemType Directory -Force -Path "$DesktopBuild\icons" | Out-Null
    Copy-Item -Recurse -Force "$Root\icons\*" "$DesktopBuild\icons"
}
Copy-Item -Force "$Root\manifest.desktop.json" "$DesktopBuild\manifest.json"
Write-Host "-> Desktop build ready at $DesktopBuild" -ForegroundColor Green

# ----------------------------------------------------
# 2. Build Mobile Extension Package (Edge Canary Android)
# ----------------------------------------------------
Write-Host "[2/2] Building Mobile target..." -ForegroundColor Yellow
Copy-Item -Recurse -Force "$Root\src\mobile\*" $MobileBuild
Copy-Item -Recurse -Force "$Root\src\common\*" $MobileBuild
if (Test-Path "$Root\src\mobile\rules.json") {
    Copy-Item -Force "$Root\src\mobile\rules.json" "$MobileBuild\rules.json"
} elseif (Test-Path "$Root\rules.json") {
    Copy-Item -Force "$Root\rules.json" "$MobileBuild\rules.json"
}
if (Test-Path "$Root\icons") {
    New-Item -ItemType Directory -Force -Path "$MobileBuild\icons" | Out-Null
    Copy-Item -Recurse -Force "$Root\icons\*" "$MobileBuild\icons"
}
Copy-Item -Force "$Root\manifest.mobile.json" "$MobileBuild\manifest.json"
Write-Host "-> Mobile build ready at $MobileBuild" -ForegroundColor Green

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Build completed successfully!             " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
