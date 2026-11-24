param(
    [switch]$NoInstall
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [string]$File,

        [string[]]$Arguments = @()
    )

    & $File @Arguments

    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $File"
    }
}

# 1. Build release APK.
$Gradlew = Join-Path $ProjectRoot 'gradlew.bat'
if (-not (Test-Path $Gradlew)) {
    $Gradlew = Join-Path $ProjectRoot 'gradlew'
}
if (-not (Test-Path $Gradlew)) {
    throw 'Gradle wrapper not found in project root.'
}

Invoke-Checked $Gradlew @('--stop')
Invoke-Checked $Gradlew @('--no-daemon', '--max-workers=1', 'assembleRelease')

# 2. Locate the newest release APK.
$Apk = Get-ChildItem (Join-Path $ProjectRoot 'app\build\outputs\apk') `
    -Filter '*.apk' -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '[\\/]release[\\/]' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $Apk) {
    throw 'No release APK found under app\build\outputs\apk.'
}

Write-Host "APK: $($Apk.FullName)" -ForegroundColor Cyan

# 3. Determine the package ID from the APK manifest.
$Package = $null

$Sdk = $env:ANDROID_SDK_ROOT
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }

if ($Sdk) {
    $BuildTools = Join-Path $Sdk 'build-tools'
    $Aapt = Get-ChildItem $BuildTools -Filter 'aapt.exe' -File -Recurse `
        -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1

    if ($Aapt) {
        $Badging = & $Aapt.FullName dump badging $Apk.FullName 2>$null
        $Line = $Badging |
            Where-Object { $_ -match "^package: name='" } |
            Select-Object -First 1

        if ($Line -match "^package: name='([^']+)'") {
            $Package = $Matches[1]
        }
    }
}

# Fallback to Android Gradle configuration if aapt is unavailable.
if (-not $Package) {
    $GradleFiles = @(
        Get-ChildItem $ProjectRoot -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @('build.gradle', 'build.gradle.kts') }

        Get-ChildItem (Join-Path $ProjectRoot 'app') -File `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @('build.gradle', 'build.gradle.kts') }
    )

    foreach ($File in $GradleFiles) {
        $Content = Get-Content $File.FullName -Raw

        $Match = [regex]::Match(
            $Content,
            '(?m)^\s*applicationId\s*(?:=\s*)?["'']([^"'']+)["'']'
        )

        if ($Match.Success) {
            $Package = $Match.Groups[1].Value
            break
        }
    }
}

if (-not $Package) {
    throw 'Could not determine package ID. Ensure Android SDK Build Tools (aapt.exe) are installed.'
}

Write-Host "Package: $Package" -ForegroundColor Cyan

if ($NoInstall) {
    Write-Host 'Build completed successfully (-NoInstall specified; skipping ADB install).' -ForegroundColor Green
    exit 0
}

# 4. Locate adb and check for an attached authorized device.
$AdbPath = $null
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $AdbPath = $adbCmd.Source
} else {
    $adbSdk = if ($Sdk) { $Sdk } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $sdkAdb = Join-Path $adbSdk 'platform-tools\adb.exe'
    if (Test-Path $sdkAdb) {
        $AdbPath = $sdkAdb
    }
}

if (-not $AdbPath) {
    Write-Host 'adb.exe not found. Build succeeded; skipping install and launch.' -ForegroundColor Yellow
    exit 0
}

$DevicesOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Could not query ADB devices. Build succeeded; skipping install and launch.' -ForegroundColor Yellow
    exit 0
}
$UnauthorizedDevices = @($DevicesOutput | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+unauthorized\s*$' })
$ConnectedDevices = @($DevicesOutput | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+device\s*$' })

if ($ConnectedDevices.Count -eq 0) {
    if ($UnauthorizedDevices.Count -gt 0) {
        Write-Host 'Device connected but unauthorized. Build succeeded; skipping install and launch.' -ForegroundColor Yellow
    } else {
        Write-Host 'No Android device attached over ADB. Build succeeded; skipping install and launch.' -ForegroundColor Yellow
    }
    exit 0
}

# 5. Install the APK.
Invoke-Checked $AdbPath @('install', '-r', $Apk.FullName)

# 6. Resolve and launch this package's actual launcher activity.
$ActivityOutput = & $AdbPath shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.LAUNCHER `
    $Package 2>$null

$Activity = $ActivityOutput |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^[^/]+/.+$' } |
    Select-Object -Last 1

if (-not $Activity) {
    throw "No launcher activity found for package $Package. APK installed successfully, but it could not be launched."
}

Invoke-Checked $AdbPath @('shell', 'am', 'start', '-n', $Activity)

Write-Host 'Build, install, and launch completed successfully.' `
    -ForegroundColor Green
