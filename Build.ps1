<#
.SYNOPSIS
    Builds and packages xPrivacyGuard into a signed .crx package.

.DESCRIPTION
    1. Prepares extension assets in build folders.
    2. Uses Microsoft Edge or Google Chrome command-line packing (--pack-extension).
    3. Uses the secure private key stored at:
       C:\Users\Michael\.xconflictionx\xPrivacyGuard\xPrivacyGuard-Mobile.pem
    4. Leaves the generated CRX inside the build directory.
#>

$ErrorActionPreference = "Stop"

$Root = Resolve-Path "$PSScriptRoot"

$DesktopBuild = Join-Path $Root "build\desktop"
$MobileBuild  = Join-Path $Root "build\mobile"

$SecureDir = "C:\Users\Michael\.xconflictionx\xPrivacyGuard"

if (-not (Test-Path $SecureDir)) {
    New-Item -ItemType Directory -Force -Path $SecureDir | Out-Null
}

$PrivateKeyPath = Join-Path $SecureDir "xPrivacyGuard-Mobile.pem"


Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Building & Packaging xPrivacyGuard      " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan


# ============================
# Clean Build Folders
# ============================

if (Test-Path $DesktopBuild) {
    Remove-Item -Recurse -Force $DesktopBuild
}

if (Test-Path $MobileBuild) {
    Remove-Item -Recurse -Force $MobileBuild
}


New-Item -ItemType Directory -Force -Path $DesktopBuild | Out-Null
New-Item -ItemType Directory -Force -Path $MobileBuild | Out-Null


# ============================
# Desktop Build
# ============================

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


# ============================
# Mobile Build
# ============================

Copy-Item -Recurse -Force "$Root\src\mobile\*" $MobileBuild
Copy-Item -Recurse -Force "$Root\src\common\*" $MobileBuild

if (Test-Path "$Root\src\mobile\rules.json") {
    Copy-Item -Force "$Root\src\mobile\rules.json" "$MobileBuild\rules.json"
}
elseif (Test-Path "$Root\rules.json") {
    Copy-Item -Force "$Root\rules.json" "$MobileBuild\rules.json"
}

if (Test-Path "$Root\icons") {
    New-Item -ItemType Directory -Force -Path "$MobileBuild\icons" | Out-Null
    Copy-Item -Recurse -Force "$Root\icons\*" "$MobileBuild\icons"
}

Copy-Item -Force "$Root\manifest.mobile.json" "$MobileBuild\manifest.json"


# ============================
# Find Browser
# ============================

$BrowserPaths = @(
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
)

$BrowserExe = $null

foreach ($path in $BrowserPaths) {
    if (Test-Path $path) {
        $BrowserExe = $path
        break
    }
}


if (-not $BrowserExe) {
    Write-Error "Could not find Microsoft Edge or Google Chrome."
    exit 1
}


Write-Host "Using browser: $BrowserExe" -ForegroundColor Cyan
Write-Host "Packing extension from: $MobileBuild" -ForegroundColor Cyan


# ============================
# Pack CRX
# ============================

$argsList = @(
    "--pack-extension=$MobileBuild"
)


if (Test-Path $PrivateKeyPath) {

    $argsList += "--pack-extension-key=$PrivateKeyPath"

    Write-Host "Using private key: $PrivateKeyPath" -ForegroundColor Cyan

}


Write-Host "Starting CRX packaging..." -ForegroundColor Cyan


Start-Process `
    -FilePath $BrowserExe `
    -ArgumentList $argsList `
    -Wait


# Give Edge a moment to flush files
Start-Sleep -Seconds 2



# ============================
# Verify Output
# ============================

$GeneratedCrx = "$MobileBuild.crx"
$GeneratedPem = "$MobileBuild.pem"


if (-not (Test-Path $GeneratedCrx)) {

    Write-Error "Failed to generate CRX file."
    Write-Host "Expected location:"
    Write-Host $GeneratedCrx
    exit 1

}



# Save private key on first build

if (-not (Test-Path $PrivateKeyPath) -and (Test-Path $GeneratedPem)) {

    Move-Item -Force $GeneratedPem $PrivateKeyPath

    Write-Host "Saved private key securely:" -ForegroundColor Green
    Write-Host $PrivateKeyPath -ForegroundColor Green

}
elseif (Test-Path $GeneratedPem) {

    Remove-Item -Force $GeneratedPem

}



Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host " CRX Build Successful!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green

Write-Host ""
Write-Host "CRX Output:"
Write-Host $GeneratedCrx -ForegroundColor Cyan

Write-Host ""
Write-Host "Build completed."