<#
.SYNOPSIS
    Builds the standalone Inno Setup 6 installer (xPrivacyGuard-Setup.exe) for xPrivacyGuard.

.DESCRIPTION
    Packages %LOCALAPPDATA%\xPrograms\xPrivacyGuard (including aether.dll and wintun.dll) into
    a standalone Windows installer in the project folder (xPrivacyGuard-Setup.exe), complete
    with Start Menu/Desktop shortcuts, elevated logon startup task option, registry entries,
    and unins000.exe uninstaller.

.PARAMETER RebuildApp
    Runs .\.build.ps1 first to compile and deploy the latest Release binaries to xPrograms before packaging.

.PARAMETER Install
    Silently runs the generated installer after compilation to apply registry entries and update unins000.exe.

.PARAMETER OutputDir
    Directory where xPrivacyGuard-Setup.exe will be placed (defaults to the project folder).
#>
[CmdletBinding()]
param(
    [switch]$RebuildApp,
    [switch]$Install,
    [string]$OutputDir = "",
    [string]$IsccPath  = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot      = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = $repoRoot
}
$folderName    = "xPrivacyGuard"
$appName       = "xPrivacyGuard"
$installerBase = "xPrivacyGuard-Setup"
$exeName       = "xPrivacyGuard.exe"
$version       = "2.0.0"
$appId         = "9A6E4D12-5C38-4F9B-A72E-3B8C1F4D2026"
$description   = "Always-On Cloudflare One MASQUE VPN for Windows"
$startupArgs   = "--background"
$defStartup    = $true
$sourceDir     = Join-Path $repoRoot "artifacts\win-x64"
$installDir    = Join-Path $env:LOCALAPPDATA "xPrograms\$folderName"
$repoIcon      = Join-Path $repoRoot "src\xPrivacyGuard\Assets\xprivacyguard.ico"

if (-not (Test-Path $IsccPath)) {
    throw "Inno Setup 6 compiler (ISCC.exe) not found at: $IsccPath"
}

if ($RebuildApp -or -not (Test-Path (Join-Path $sourceDir $exeName))) {
    $buildTestScript = Join-Path $repoRoot "scripts\windows\build-test.ps1"
    Write-Host "==> Publishing Release binaries for $appName..." -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File $buildTestScript -Configuration Release -RuntimeIdentifier win-x64 -SkipNativeRebuild
    if ($LASTEXITCODE -ne 0) { throw "Release publish failed." }
}

if (-not (Test-Path $sourceDir)) {
    throw "Deployed application folder not found: $sourceDir. Run .\.build.ps1 first."
}

$destIcon = Join-Path $sourceDir "icon.ico"
if (Test-Path $repoIcon) {
    Copy-Item -Path $repoIcon -Destination $destIcon -Force
}

$setupIconLine = if (Test-Path $destIcon) { "SetupIconFile=`"$destIcon`"" } else { "" }
$startupTaskFlags = if ($defStartup) { "" } else { "; Flags: unchecked" }

$tempBuildDir = Join-Path $env:TEMP "xPrograms-InstallerBuild\$folderName"
$stageOutDir  = Join-Path $tempBuildDir "out"
New-Item -ItemType Directory -Path $stageOutDir -Force | Out-Null
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$issLines = @(
    "; Generated Inno Setup 6 Script for $appName",
    "#define AppName        `"$appName`"",
    "#define AppFolderName  `"$folderName`"",
    "#define AppVersion     `"$version`"",
    "#define AppPublisher   `"xCONFLiCTiONx`"",
    "#define AppURL         `"https://xconflictionx.cc`"",
    "#define AppContact     `"info@xconflictionx.cc`"",
    "#define AppExeName     `"$exeName`"",
    "#define AppId          `"$appId`"",
    "#define AppDescription `"$description`"",
    "#define SourcePath     `"$sourceDir`"",
    "#define OutputPath     `"$stageOutDir`"",
    "",
    "[Setup]",
    "AppId={{{#AppId}}",
    "AppName={#AppName}",
    "AppVersion={#AppVersion}",
    "AppVerName={#AppName} {#AppVersion}",
    "AppPublisher={#AppPublisher}",
    "AppPublisherURL={#AppURL}",
    "AppSupportURL={#AppURL}",
    "AppUpdatesURL={#AppURL}",
    "AppContact={#AppContact}",
    "AppComments={#AppDescription}",
    "DefaultDirName={localappdata}\xPrograms\{#AppFolderName}",
    "DefaultGroupName=xPrograms",
    "DisableProgramGroupPage=yes",
    "PrivilegesRequired=admin",
    "ArchitecturesAllowed=x64compatible",
    "ArchitecturesInstallIn64BitMode=x64compatible",
    "WizardStyle=modern",
    $setupIconLine,
    "UninstallDisplayIcon={app}\{#AppExeName}",
    "UninstallDisplayName={#AppName}",
    "OutputDir={#OutputPath}",
    "OutputBaseFilename=$installerBase",
    "Compression=lzma2/fast",
    "SolidCompression=no",
    "CloseApplications=force",
    "RestartApplications=no",
    "VersionInfoVersion={#AppVersion}",
    "VersionInfoDescription={#AppDescription}",
    "",
    "[Languages]",
    "Name: `"english`"; MessagesFile: `"compiler:Default.isl`"",
    "",
    "[Tasks]",
    "Name: `"desktopicon`"; Description: `"{cm:CreateDesktopIcon}`"; GroupDescription: `"{cm:AdditionalIcons}`"; Flags: unchecked",
    "",
    "[Files]",
    "Source: `"{#SourcePath}\*`"; DestDir: `"{app}`"; Excludes: `"unins*.*`"; Flags: ignoreversion recursesubdirs createallsubdirs",
    "",
    "[Icons]",
    "Name: `"{userprograms}\{#AppName}`"; Filename: `"{app}\{#AppExeName}`"; WorkingDir: `"{app}`"; Comment: `"{#AppDescription}`"",
    "Name: `"{userdesktop}\{#AppName}`"; Filename: `"{app}\{#AppExeName}`"; WorkingDir: `"{app}`"; Comment: `"{#AppDescription}`"; Tasks: desktopicon",
    "",
    "[Registry]",
    "Root: HKCU; Subkey: `"Software\xPrograms\{#AppFolderName}`"; ValueType: string; ValueName: `"DisplayName`"; ValueData: `"{#AppName}`"; Flags: uninsdeletekey",
    "Root: HKCU; Subkey: `"Software\xPrograms\{#AppFolderName}`"; ValueType: string; ValueName: `"InstallDir`"; ValueData: `"{app}`"; Flags: uninsdeletekey",
    "Root: HKCU; Subkey: `"Software\xPrograms\{#AppFolderName}`"; ValueType: string; ValueName: `"ExecutablePath`"; ValueData: `"{app}\{#AppExeName}`"; Flags: uninsdeletekey",
    "Root: HKCU; Subkey: `"Software\xPrograms\{#AppFolderName}`"; ValueType: string; ValueName: `"Version`"; ValueData: `"{#AppVersion}`"; Flags: uninsdeletekey",
    "Root: HKCU; Subkey: `"Software\Microsoft\Windows\CurrentVersion\App Paths\{#AppExeName}`"; ValueType: string; ValueName: `"`"; ValueData: `"{app}\{#AppExeName}`"; Flags: uninsdeletekey",
    "Root: HKCU; Subkey: `"Software\Microsoft\Windows\CurrentVersion\App Paths\{#AppExeName}`"; ValueType: string; ValueName: `"Path`"; ValueData: `"{app}`"; Flags: uninsdeletekey",
    "",
    "[Run]",
    "Filename: `"{app}\{#AppExeName}`"; Description: `"{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}`"; Flags: nowait postinstall skipifsilent shellexec",
    "",
    "[UninstallDelete]",
    "Type: filesandordirs; Name: `"{app}`""
)

$issFile = Join-Path $tempBuildDir "$folderName.iss"
Set-Content -Path $issFile -Value ($issLines -join "`r`n") -Encoding UTF8

Write-Host "==> Compiling $installerBase.exe..." -ForegroundColor Cyan
$compiled = $false
$stagedExe = Join-Path $stageOutDir "$installerBase.exe"
for ($attempt = 1; $attempt -le 4; $attempt++) {
    if (Test-Path $stagedExe) { Remove-Item $stagedExe -Force -ErrorAction SilentlyContinue }
    & $IsccPath /Q $issFile
    if ($LASTEXITCODE -eq 0 -and (Test-Path $stagedExe)) {
        $compiled = $true
        break
    }
    Write-Host "    [Retry $attempt] Waiting briefly for file handle release..." -ForegroundColor Yellow
    Start-Sleep -Seconds 2
}
if (-not $compiled) {
    throw "ISCC failed to compile $issFile"
}

$finalExe = Join-Path $OutputDir "$installerBase.exe"
Copy-Item -Path $stagedExe -Destination $finalExe -Force

# Sign installer with xCONFLiCTiONx code-signing certificate if available
$signCert = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue |
    Where-Object { $_.Subject -match 'CN=xCONFLiCTiONx' } |
    Sort-Object NotAfter -Descending | Select-Object -First 1
$signTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1
if ($signCert -and $signTool) {
    Write-Host "==> Signing $finalExe with xCONFLiCTiONx ($($signCert.Thumbprint))..." -ForegroundColor Cyan
    & $signTool.FullName sign /sha1 $signCert.Thumbprint /fd SHA256 /d "$appName Setup" /du "https://xconflictionx.cc" /tr "http://timestamp.digicert.com" /td SHA256 $finalExe
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    Timestamp server unavailable; signing without timestamp..." -ForegroundColor Yellow
        & $signTool.FullName sign /sha1 $signCert.Thumbprint /fd SHA256 /d "$appName Setup" /du "https://xconflictionx.cc" $finalExe
        if ($LASTEXITCODE -ne 0) { throw "signtool failed with exit code $LASTEXITCODE" }
    }
    Write-Host "[OK] Signed installer: $finalExe" -ForegroundColor Green
}

Write-Host "[OK] Installer created at: $finalExe" -ForegroundColor Green

if ($Install) {
    Write-Host "==> Running silent install for $appName..." -ForegroundColor Yellow
    $procName = [System.IO.Path]::GetFileNameWithoutExtension($exeName)
    $wasRunning = Get-Process -Name $procName -ErrorAction SilentlyContinue
    if ($wasRunning) {
        $wasRunning | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 400
    }
    $p = Start-Process -FilePath $finalExe -ArgumentList "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-" -Wait -PassThru
    if ($p.ExitCode -ne 0) {
        throw "Silent install failed with exit code $($p.ExitCode)"
    }
    Write-Host "[OK] Installed & registered $appName in $installDir" -ForegroundColor Green
}
