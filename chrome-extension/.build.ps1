$ErrorActionPreference = 'Stop'

$SourceDir = $PSScriptRoot
$TargetDir = Join-Path $env:LOCALAPPDATA 'xExtensions\xPrivacyGuard'

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

# Mirror extension files to LocalAppData\xExtensions\xPrivacyGuard
$RobocopyArgs = @(
    $SourceDir,
    $TargetDir,
    '/MIR',
    '/XD', 'tests', '.git',
    '/XF', '.build.ps1', '*.test.js',
    '/NFL', '/NDL', '/NJH', '/NJS', '/NP'
)

& robocopy @RobocopyArgs | Out-Null
# Robocopy exit codes 0-7 indicate success (with varying levels of changes copied).
if ($LASTEXITCODE -ge 8) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

Write-Host "Copied chrome-extension to $TargetDir" -ForegroundColor Green

