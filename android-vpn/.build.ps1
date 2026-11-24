#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$BuildVariant = "Release"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"

# 1. Configure JAVA_HOME and ANDROID_HOME if not already set
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jbrCandidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    foreach ($candidate in $jbrCandidates) {
        if (Test-Path (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $sdkCandidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        "C:\Android\sdk"
    )
    foreach ($candidate in $sdkCandidates) {
        if (Test-Path $candidate) {
            $env:ANDROID_HOME = $candidate
            break
        }
    }
}

Write-Host "==> Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
if ($env:ANDROID_HOME) {
    Write-Host "==> Using ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Cyan
}

# 2. Always build the optimized Release variant. Gradle uses the configured
# release keystore when supplied; otherwise the app's build config uses the
# local Android debug keystore for a locally installable Release APK.
Write-Host "==> Building Release APK..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    throw "Gradle $BuildVariant build failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path $ApkPath)) {
    throw "Built $BuildVariant APK not found at: $ApkPath"
}

$apkSizeMb = [Math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "==> $BuildVariant APK built successfully ($apkSizeMb MB): $ApkPath" -ForegroundColor Green

# 3. Locate adb.exe
$AdbPath = $null
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $AdbPath = $adbCmd.Source
} else {
    $Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $sdkAdb = Join-Path $Sdk "platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $AdbPath = $sdkAdb
    }
}

if (-not $AdbPath) {
    Write-Host "==> adb.exe not found in PATH or Android SDK. Build succeeded; skipping install and launch." -ForegroundColor Yellow
    exit 0
}

# 4. Verify connected Android device
Write-Host "==> Checking connected Android device(s)..." -ForegroundColor Cyan
$devicesOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Could not query ADB devices. Build succeeded; skipping install and launch." -ForegroundColor Yellow
    exit 0
}
$unauthorizedDevices = $devicesOutput | Select-Object -Skip 1 | Where-Object { $_ -match "\S+\s+unauthorized\s*$" }
$connectedDevices = $devicesOutput | Select-Object -Skip 1 | Where-Object { $_ -match "\S+\s+device\s*$" }

if (-not $connectedDevices) {
    if ($unauthorizedDevices) {
        Write-Host "==> Device connected but unauthorized. Approve USB debugging to install. Build succeeded; skipping install and launch." -ForegroundColor Yellow
    } else {
        Write-Host "==> No Android device attached over ADB. Build succeeded; skipping install and launch." -ForegroundColor Yellow
    }
    exit 0
}

# 5. Dynamically determine Package Name from APK Manifest or Gradle files
Write-Host "==> Resolving package name dynamically..." -ForegroundColor Cyan
$PackageName = $null
$Sdk = $env:ANDROID_SDK_ROOT
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

if (Test-Path $Sdk) {
    $BuildTools = Join-Path $Sdk 'build-tools'
    $Aapt = Get-ChildItem $BuildTools -Filter 'aapt.exe' -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1

    if ($Aapt) {
        $Badging = & $Aapt.FullName dump badging $ApkPath 2>$null
        $Line = $Badging | Where-Object { $_ -match "^package: name='" } | Select-Object -First 1
        if ($Line -match "^package: name='([^']+)'") {
            $PackageName = $Matches[1]
        }
    }
}

# Fallback: Read applicationId from Gradle files if aapt wasn't found
if (-not $PackageName) {
    $GradleFiles = @(
        Get-ChildItem $PSScriptRoot -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -in @('build.gradle', 'build.gradle.kts') }
        Get-ChildItem (Join-Path $PSScriptRoot 'app') -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -in @('build.gradle', 'build.gradle.kts') }
    )
    foreach ($File in $GradleFiles) {
        $Content = Get-Content $File.FullName -Raw
        $Match = [regex]::Match($Content, '(?m)^\s*applicationId\s*(?:=\s*)?["\x27]([^"\x27]+)["\x27]')
        if ($Match.Success) {
            $PackageName = $Match.Groups[1].Value
            break
        }
    }
}

if (-not $PackageName) {
    throw "Could not determine package ID automatically. Check your Android SDK or build files."
}
Write-Host "==> Package Name: $PackageName" -ForegroundColor Green

# 6. Install without uninstalling or deleting existing app data
Write-Host "==> Installing $BuildVariant APK onto phone..." -ForegroundColor Cyan
$installOutput = & $AdbPath install -r $ApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    $installText = ($installOutput | Out-String)
    Write-Host $installText -ForegroundColor Red
    if ($installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        throw "The installed app uses a different signing key. This script will not uninstall it or erase app data. Use the original private signing key or back up app data before changing signatures."
    }
    throw "adb install failed with exit code $LASTEXITCODE"
} else {
    Write-Host ($installOutput | Out-String).Trim() -ForegroundColor Gray
}

# 7. Dynamically resolve and launch the app's launcher activity via ADB
Write-Host "==> Resolving launcher activity..." -ForegroundColor Cyan
$ActivityOutput = & $AdbPath shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.LAUNCHER `
    $PackageName 2>$null

if ($LASTEXITCODE -ne 0) {
    throw "Could not resolve launcher activity through adb."
}

$Activity = $ActivityOutput |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^[^/]+/.+$' } |
    Select-Object -Last 1

if (-not $Activity) {
    throw "No launcher activity found for $PackageName. The APK was installed, but could not be launched."
}

Write-Host "==> Launching activity ($Activity)..." -ForegroundColor Cyan
& $AdbPath shell am start -n $Activity
if ($LASTEXITCODE -ne 0) {
    throw "Failed to launch activity via adb"
}

Write-Host "==> Done! App ($BuildVariant) is installed and running on your phone." -ForegroundColor Green