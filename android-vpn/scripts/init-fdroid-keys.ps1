Clear-Host
#Requires -Version 5.1
$ErrorActionPreference = "Stop"

# Generates the two persistent signing keystores needed for publishing
# xPrivacyGuard (android-vpn) to your self-hosted F-Droid repository:
#   1. xprivacyguard-release.jks  -> signs the Android Release APK
#   2. fdroid-repo-keystore.jks   -> signs your F-Droid repository index
#
# Keystores are written to android-vpn/secrets/ (gitignored).
# NEVER commit *.jks or *.b64.txt files. Back them up safely!

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SecretsDir = Join-Path $ProjectRoot "secrets"
New-Item -ItemType Directory -Path $SecretsDir -Force | Out-Null

# Locate keytool
$Keytool = $null
$keytoolCmd = Get-Command keytool -ErrorAction SilentlyContinue
if ($keytoolCmd) {
    $Keytool = $keytoolCmd.Source
} else {
    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
    )
    if ($env:JAVA_HOME) {
        $candidates = @((Join-Path $env:JAVA_HOME "bin\keytool.exe")) + $candidates
    }
    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $Keytool = $c
            break
        }
    }
}

if (-not $Keytool) {
    throw "keytool.exe not found. Install Android Studio or JDK 17 and re-run."
}

function New-RandomPassword {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', '').Substring(0, 24)
}

function New-SigningKeystore {
    param(
        [string]$Path,
        [string]$Alias,
        [string]$DName,
        [string]$Password
    )

    if (Test-Path $Path) {
        Write-Host "==> Keystore already exists (keeping existing): $Path" -ForegroundColor Yellow
        return
    }

    Write-Host "==> Generating keystore: $Path (alias: $Alias)" -ForegroundColor Cyan
    & $Keytool -genkeypair `
        -v `
        -keystore $Path `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -storepass $Password `
        -keypass $Password `
        -dname $DName | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed generating $Path"
    }
}

$AppKeystore = Join-Path $SecretsDir "xprivacyguard-release.jks"
$AppAlias = "xprivacyguard"
$AppPass = New-RandomPassword

$RepoKeystore = Join-Path $SecretsDir "fdroid-repo-keystore.jks"
$RepoAlias = "fdroidrepo"
$RepoPass = New-RandomPassword

New-SigningKeystore -Path $AppKeystore -Alias $AppAlias -DName "CN=xPrivacyGuard, O=xCONFLiCTiONx, C=US" -Password $AppPass
New-SigningKeystore -Path $RepoKeystore -Alias $RepoAlias -DName "CN=xCONFLiCTiONx F-Droid Repo, O=xCONFLiCTiONx, C=US" -Password $RepoPass

$AppB64Path = Join-Path $SecretsDir "XPRIVACYGUARD_KEYSTORE_BASE64.txt"
$RepoB64Path = Join-Path $SecretsDir "FDROID_REPO_KEYSTORE_BASE64.txt"
$SummaryPath = Join-Path $SecretsDir "github-secrets-summary.txt"

[Convert]::ToBase64String([IO.File]::ReadAllBytes($AppKeystore)) | Set-Content -Path $AppB64Path -NoNewline
[Convert]::ToBase64String([IO.File]::ReadAllBytes($RepoKeystore)) | Set-Content -Path $RepoB64Path -NoNewline

# Extract SHA-256 fingerprint of the F-Droid repo keystore (if newly generated)
$RepoFingerprint = "(run keytool -list -v on existing keystore)"
$keytoolOut = & $Keytool -list -v -keystore $RepoKeystore -alias $RepoAlias -storepass $RepoPass 2>$null
if ($LASTEXITCODE -eq 0) {
    $shaLine = $keytoolOut | Select-String -Pattern "SHA256:\s*([0-9A-Fa-f:]+)" | Select-Object -First 1
    if ($shaLine) {
        $RepoFingerprint = ($shaLine.Matches[0].Groups[1].Value -replace ':', '').ToUpperInvariant()
    }
}

$summary = @"
================================================================================
GitHub Actions Secrets for xPrivacyGuard (android-vpn) F-Droid Publishing
Add these in: GitHub Repo -> Settings -> Secrets and variables -> Actions
================================================================================

1. XPRIVACYGUARD_KEYSTORE_BASE64   = (contents of $AppB64Path)
2. XPRIVACYGUARD_KEYSTORE_PASSWORD = $AppPass
3. XPRIVACYGUARD_KEY_ALIAS         = $AppAlias
4. XPRIVACYGUARD_KEY_PASSWORD      = $AppPass

5. FDROID_REPO_KEYSTORE_BASE64     = (contents of $RepoB64Path)
6. FDROID_REPO_KEYSTORE_PASSWORD   = $RepoPass
7. FDROID_REPO_KEY_ALIAS           = $RepoAlias
8. FDROID_REPO_KEY_PASSWORD        = $RepoPass

F-Droid Repo SHA-256 Fingerprint:
$RepoFingerprint

IMPORTANT:
- Back up $AppKeystore and $RepoKeystore to a password manager / encrypted drive.
- If you lose xprivacyguard-release.jks, existing users cannot upgrade without uninstalling.
- If you lose fdroid-repo-keystore.jks, users must re-add your F-Droid repo.
================================================================================
"@

$summary | Set-Content -Path $SummaryPath
Write-Host $summary -ForegroundColor Green
Write-Host "Saved summary to: $SummaryPath" -ForegroundColor Cyan
