# ============================================================================
# Data Download Manager — PowerShell version
# ============================================================================

$ErrorActionPreference = "Stop"

# Resolve directories
$ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
$Root = Resolve-Path (Join-Path $ScriptDir "..") | Select-Object -ExpandProperty Path
Set-Location $Root

$DataDir = Join-Path $Root "data\historical\dukascopy"
$BarsDir = Join-Path $Root "data\historical\bars"
$StartYear = 2006
$EndYear = (Get-Date).Year
$Pairs = @("eurusd", "gbpusd", "gbpjpy", "usdcad", "usdjpy", "audusd", "nzdusd", "usdchf")

# Rate limiting
$DelayBetweenPairs = 3
$DelayBetweenYears = 10
$DelayBetweenMonths = 5

# Ensure dirs exist
if (-not (Test-Path $DataDir)) { New-Item -ItemType Directory -Path $DataDir -Force | Out-Null }
if (-not (Test-Path $BarsDir)) { New-Item -ItemType Directory -Path $BarsDir -Force | Out-Null }

# ─── Mapping helpers ──────────────────────────────────────────────────────

function Get-PairSymbol {
    param ([string]$pair)
    switch ($pair.ToLower()) {
        "eurusd" { "EUR_USD" }
        "gbpusd" { "GBP_USD" }
        "usdcad" { "USD_CAD" }
        "usdjpy" { "USD_JPY" }
        "audusd" { "AUD_USD" }
        "nzdusd" { "NZD_USD" }
        "usdchf" { "USD_CHF" }
        "gbpjpy" { "GBP_JPY" }
        default { $pair.ToUpper() }
    }
}

function Get-JavaClassPath {
    $cp = "trading-data\target\classes;trading-core\target\classes"
    if (Test-Path "desktop-resources\jar\control-plane.jar") {
        $cp = "desktop-resources\jar\control-plane.jar"
    } elseif (Test-Path "trading-runtime\target\trading-runtime-1.0.0-SNAPSHOT-shaded.jar") {
        $cp = "trading-runtime\target\trading-runtime-1.0.0-SNAPSHOT-shaded.jar"
    }
    return $cp
}

# ─── Download helpers ─────────────────────────────────────────────────────

function Download-One {
    param (
        [string]$Pair,
        [string]$Tf,
        [string]$From,
        [string]$To
    )
    $ExpectedFile = Join-Path $DataDir "${Pair}-${Tf}-bid-${From}-${To}.csv"
    if ((Test-Path $ExpectedFile) -and (Get-Item $ExpectedFile).Length -gt 0) {
        Write-Host "  ${Pair}: already exists" -ForegroundColor Green
        return $true
    }
    Write-Host "  ${Pair}: downloading..." -ForegroundColor Cyan
    $LogFile = Join-Path $env:TEMP "dukascopy_dl.log"
    
    # Run npx dukascopy-node using cmd.exe
    $proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c npx --yes dukascopy-node -i $Pair -from $From -to $To -t $Tf -f csv -dir `"$DataDir`" -s" -RedirectStandardOutput $LogFile -RedirectStandardError $LogFile -NoNewWindow -PassThru -Wait
    
    if ((Test-Path $ExpectedFile) -and (Get-Item $ExpectedFile).Length -gt 0) {
        $size = (Get-Item $ExpectedFile).Length
        $sizeStr = [math]::round($size / 1KB, 1)
        Write-Host "  ${Pair}: succeeded (${sizeStr} KB)" -ForegroundColor Green
        return $true
    } else {
        $err = ""
        if (Test-Path $LogFile) {
            $err = Get-Content $LogFile -Tail 3 -ErrorAction SilentlyContinue | Out-String
            $err = $err.Trim()
        }
        Write-Host "  ${Pair}: failed. $err" -ForegroundColor Red
        return $false
    }
}

function Convert-ToBarStore {
    param (
        [string]$Pair,
        [string]$Tf,
        [string]$From,
        [string]$To,
        [int]$Year
    )
    $CsvFile = Join-Path $DataDir "${Pair}-${Tf}-bid-${From}-${To}.csv"
    $Sym = Get-PairSymbol $Pair
    if (-not (Test-Path $CsvFile)) { return }
    $cp = Get-JavaClassPath
    $TfUpper = $Tf.ToUpper()
    Write-Host "  Converting ${CsvFile} to barstore..."
    & java -cp $cp com.martinfou.trading.data.BarStore --convert-file $CsvFile $BarsDir $Sym "${TfUpper}_${Year}"
}

function Download-Year {
    param (
        [int]$Year,
        [string]$Tf
    )
    $FromDate = "${Year}-01-01"
    $ToDate = "${Year}-12-31"
    $Today = Get-Date -Format "yyyy-MM-dd"
    if ($Year -eq (Get-Date).Year) {
        $ToDate = $Today
    }

    Write-Host ""
    Write-Host "Downloading $Tf for year $Year ($FromDate -> $ToDate)" -ForegroundColor Cyan
    Write-Host "----------------------------------------------------"

    $PairsToDl = if ($Pair) { @($Pair) } else { $Pairs }
    
    $i = 0
    foreach ($p in $PairsToDl) {
        if ($Tf -eq "m1") {
            $CurrentYear = (Get-Date).Year
            $EndMonth = 12
            if ($Year -eq $CurrentYear) {
                $EndMonth = (Get-Date).Month
            }
            $First = $true
            $YearlyCsv = Join-Path $DataDir "${p}-${Tf}-bid-${FromDate}-${ToDate}.csv"
            if (Test-Path $YearlyCsv) { Remove-Item $YearlyCsv -Force }
            
            for ($m = 1; $m -le $EndMonth; $m++) {
                $m_from = "{0:D4}-{1:D2}-01" -f $Year, $m
                $m_to = if ($m -eq 12) { "{0:D4}-01-01" -f ($Year + 1) } else { "{0:D4}-{1:D2}-01" -f $Year, ($m + 1) }
                if ($Year -eq $CurrentYear -and $m -eq $EndMonth) {
                    $m_to = $Today
                }
                Write-Host "  ${p}: Ingesting Month ${m}/${EndMonth}..."
                $ok = Download-One $p $Tf $m_from $m_to
                
                $MonthlyCsv = Join-Path $DataDir "${p}-${Tf}-bid-${m_from}-${m_to}.csv"
                if (Test-Path $MonthlyCsv) {
                    if ($First) {
                        Copy-Item $MonthlyCsv $YearlyCsv
                        $First = $false
                    } else {
                        Get-Content $MonthlyCsv | Select-Object -Skip 1 | Add-Content $YearlyCsv
                    }
                    Remove-Item $MonthlyCsv -Force
                }
                Start-Sleep -Seconds $DelayBetweenMonths
            }
            Convert-ToBarStore $p $Tf $FromDate $ToDate $Year
        } else {
            $ok = Download-One $p $Tf $FromDate $ToDate
            if ($ok) {
                Convert-ToBarStore $p $Tf $FromDate $ToDate $Year
            }
        }
        
        $i++
        if ($i -lt $PairsToDl.Count) {
            Start-Sleep -Seconds $DelayBetweenPairs
        }
    }
}

# ─── Status ───────────────────────────────────────────────────────────────

function Show-Status {
    Write-Host "Data Status - $($Timeframe.ToUpper())" -ForegroundColor Cyan
    Write-Host "======================================="
    
    $LocalCount = 0
    $Total = $Pairs.Count
    $TotalYears = $EndYear - $StartYear + 1
    
    for ($y = $StartYear; $y -le $EndYear; $y++) {
        $dl = 0
        $MissingPairs = @()
        foreach ($p in $Pairs) {
            $files = Get-ChildItem -Path $DataDir -Filter "${p}-${Timeframe}*${y}*" -ErrorAction SilentlyContinue
            if ($files) {
                $dl++
            } else {
                $MissingPairs += $p
            }
        }
        $pct = [int]($dl * 100 / $Total)
        $MissingLabel = ""
        if ($MissingPairs.Count -gt 0 -and $MissingPairs.Count -le 3) {
            $MissingLabel = " - missing: " + ($MissingPairs -join ",")
        }
        if ($pct -eq 100) {
            Write-Host "  [OK] ${y}: ${dl}/${Total} (${pct}%)" -ForegroundColor Green
            $LocalCount++
        } elseif ($dl -gt 0) {
            Write-Host "  [WARN] ${y}: ${dl}/${Total} (${pct}%)${MissingLabel}" -ForegroundColor Yellow
        } else {
            Write-Host "  [FAIL] ${y}: 0/${Total}${MissingLabel}" -ForegroundColor Red
        }
    }
    
    Write-Host "======================================="
    Write-Host "  ${LocalCount}/${TotalYears} years complete" -ForegroundColor Green
    exit 0
}

# ─── Action Dispatcher ────────────────────────────────────────────────────

$Action = "auto"
$Pair = $null
$Year = $null
$Range = $null
$Timeframe = "h1"

for ($i = 0; $i -lt $args.Length; $i++) {
    switch ($args[$i]) {
        "--list" { $Action = "list" }
        "--status" { $Action = "list" }
        "--year" { $Action = "year"; $Year = [int]$args[++$i] }
        "--range" { $Action = "range"; $Range = $args[++$i] }
        "--gentle" { $Action = "gentle" }
        "--monthly" { $Action = "monthly"; $Year = [int]$args[++$i] }
        "--all" { $Action = "all" }
        "--sync" { $Action = "sync" }
        "--tf" { $Timeframe = $args[++$i] }
        "--pair" { $Pair = $args[++$i] }
        "-h" { $Action = "help" }
        "--help" { $Action = "help" }
        default { Write-Error "Unknown option: $($args[$i])"; exit 1 }
    }
}

function Find-NextMissingPairYear {
    for ($y = $EndYear; $y -ge $StartYear; $y--) {
        foreach ($p in $Pairs) {
            $files = Get-ChildItem -Path $DataDir -Filter "${p}-${Timeframe}*${y}*" -ErrorAction SilentlyContinue
            if (-not $files) {
                return @($y, $p)
            }
        }
    }
    return $null
}

if ($Action -eq "help") {
    Write-Host "Data Download Manager - PowerShell version"
    Write-Host "Usage:"
    Write-Host "  .\scripts\download-data.ps1 --gentle"
    Write-Host "  .\scripts\download-data.ps1 --year YYYY"
    Write-Host "  .\scripts\download-data.ps1 --sync"
    Write-Host "  .\scripts\download-data.ps1 --list"
    exit 0
}

if ($Action -eq "list") {
    Show-Status
}

if ($Action -eq "auto" -or $Action -eq "gentle") {
    if (-not $Year) {
        $missing = Find-NextMissingPairYear
        if ($missing) {
            $Year = $missing[0]
            $Pair = $missing[1]
        }
    }
    if (-not $Year) {
        Write-Host "All pairs complete for all years!" -ForegroundColor Green
        exit 0
    }
    $Sym = Get-PairSymbol $Pair
    Write-Host "Next missing: ${Sym} (${Pair}) ${Timeframe} ${Year}" -ForegroundColor Yellow
    Download-Year $Year $Timeframe
} elseif ($Action -eq "year") {
    Download-Year $Year $Timeframe
} elseif ($Action -eq "range") {
    $parts = $Range -split "-"
    $start = [int]$parts[0]
    $end = [int]$parts[1]
    for ($y = $start; $y -le $end; $y++) {
        Download-Year $y $Timeframe
        if ($y -lt $end) {
            Start-Sleep -Seconds $DelayBetweenYears
        }
    }
} elseif ($Action -eq "all") {
    for ($y = $EndYear; $y -ge $StartYear; $y--) {
        Download-Year $y $Timeframe
        Start-Sleep -Seconds $DelayBetweenYears
    }
} elseif ($Action -eq "sync") {
    Download-Year $EndYear $Timeframe
    Start-Sleep -Seconds $DelayBetweenYears
    for ($y = $EndYear - 1; $y -ge $StartYear; $y--) {
        $missing = $false
        foreach ($p in $Pairs) {
            $files = Get-ChildItem -Path $DataDir -Filter "${p}-${Timeframe}*${y}*" -ErrorAction SilentlyContinue
            if (-not $files) { $missing = $true; break }
        }
        if ($missing) {
            Download-Year $y $Timeframe
            Start-Sleep -Seconds $DelayBetweenYears
        }
    }
}
