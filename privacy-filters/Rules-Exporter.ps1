$jsonPath = "rules.json"
$txtPath = "filters.txt"
$outputPath = "rules-updated.json"

Write-Host "[*] Checking files..." -ForegroundColor Yellow
if (-not (Test-Path $jsonPath)) { Write-Error "Missing $jsonPath"; exit }
if (-not (Test-Path $txtPath)) { Write-Error "Missing $txtPath"; exit }

Write-Host "[*] Reading $jsonPath..." -ForegroundColor Yellow
$rules = Get-Content $jsonPath -Raw | ConvertFrom-Json

Write-Host "[*] Reading lines from $txtPath..." -ForegroundColor Yellow
$txtLines = Get-Content $txtPath
Write-Host "[+] Total lines loaded: $($txtLines.Count)" -ForegroundColor Cyan

Write-Host "[*] Locating Rule ID 3..." -ForegroundColor Yellow
$targetRule = $rules | Where-Object { $_.id -eq 3 }
if (-not $targetRule) {
    Write-Error "Could not find Rule ID 3 in $jsonPath!"
    exit
}

$existingList = New-Object System.Collections.Generic.List[string]
foreach ($d in $targetRule.condition.requestDomains) { 
    [void]$existingList.Add($d) 
}
Write-Host "[+] Found Rule #3 with $($existingList.Count) existing domains." -ForegroundColor Cyan

Write-Host "[*] Starting line-by-line parsing and live insertion..." -ForegroundColor Yellow
$addedCount = 0
$skippedCount = $imagesProcessed = 0

foreach ($line in $txtLines) {
    $clean = $line.Trim()
    
    if ([string]::IsNullOrWhiteSpace($clean)) {
        continue
    }
    
    if ($clean.StartsWith('!')) {
        Write-Host " [-] Skipping comment: $clean" -ForegroundColor DarkGray
        continue
    }
    
    # Strip adblock syntax
    $clean = $clean -replace '^\*\|+', '' -replace '^\\\|+', '' -replace '^\|+', '' -replace '\^.*$', '' -replace '/.*$', ''
    
    if ($clean -match '^[a-z0-9.-]+\.[a-z]{2,}$') {
        if (-not $existingList.Contains($clean)) {
            [void]$existingList.Add($clean)
            $addedCount++
            Write-Host " [+] ADDED NEW DOMAIN: $clean" -ForegroundColor Green
        } else {
            $skippedCount++
            Write-Host " [=] Duplicate (already exists): $clean" -ForegroundColor DarkYellow
        }
    } else {
        Write-Host " [!] Invalid/Unmatched format skipped: $line" -ForegroundColor Red
    }
}

$targetRule.condition.requestDomains = $existingList.ToArray()

Write-Host "[*] Saving updated JSON to $outputPath..." -ForegroundColor Yellow
$rules | ConvertTo-Json -Depth 10 | Out-File -FilePath $outputPath -Encoding utf8

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " FINISHED!" -ForegroundColor Green
Write-Host " Added: $addedCount new domains" -ForegroundColor Green
Write-Host " Skipped/Duplicates: $skippedCount" -ForegroundColor Yellow
Write-Host " Total domains now in Rule #3: $($existingList.Count)" -ForegroundColor Cyan
Write-Host " Saved to: $outputPath" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan