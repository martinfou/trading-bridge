# Start the Trading Bridge TUI (requires control plane on CONTROL_PLANE_URL).
#
# Usage:
#   .\scripts\run-tui.ps1
#   .\scripts\run-tui.ps1 --with-control-plane   # start control plane in background first
# Stop orphaned servers: .\scripts\stop-control-plane.ps1

$ErrorActionPreference = "Stop"

# Resolve the root directory
$ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
$Root = Resolve-Path (Join-Path $ScriptDir "..") | Select-Object -ExpandProperty Path
Set-Location $Root

# Resolve Maven command
$MvnCmd = "mvn"
if (Test-Path "$Root\mvnw.cmd") {
    $MvnCmd = "$Root\mvnw.cmd"
}

# Setup env vars
if (-not $env:TRADING_BRIDGE_ROOT) {
    $env:TRADING_BRIDGE_ROOT = $Root
}
if (-not $env:CONTROL_PLANE_PORT) {
    $env:CONTROL_PLANE_PORT = "8080"
}
if (-not $env:CONTROL_PLANE_URL) {
    $env:CONTROL_PLANE_URL = "http://localhost:$($env:CONTROL_PLANE_PORT)"
}

$WithCP = $false
foreach ($arg in $args) {
    if ($arg -eq "--with-control-plane" -or $arg -eq "-c") {
        $WithCP = $true
    } elseif ($arg -eq "-h" -or $arg -eq "--help") {
        Write-Host "Usage: .\scripts\run-tui.ps1 [--with-control-plane|-c]"
        exit 0
    } else {
        Write-Error "Unknown option: $arg"
        exit 1
    }
}

function Get-ControlPlaneHealthy {
    try {
        $resp = Invoke-WebRequest -Uri "$env:CONTROL_PLANE_URL/api/health" -UseBasicParsing -TimeoutSec 2
        return $resp.Content -like "*dataCatalog*"
    } catch {
        return $false
    }
}

$CP_Process = $null

try {
    if ($WithCP) {
        if (Get-ControlPlaneHealthy) {
            Write-Host "Control plane already up at $($env:CONTROL_PLANE_URL)"
        } else {
            Write-Host "Starting control plane (background)..."
            Write-Host "Building trading-runtime (and dependencies)..."
            & $MvnCmd -q -pl trading-runtime -am install -DskipTests
            
            # Start control plane process in background
            $CP_Process = Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$MvnCmd`" -q exec:java -pl trading-runtime -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain" -NoNewWindow -PassThru
            
            # Poll for health
            $success = $false
            for ($i = 1; $i -le 45; $i++) {
                if (Get-ControlPlaneHealthy) {
                    Write-Host "Control plane ready at $($env:CONTROL_PLANE_URL)"
                    $success = $true
                    break
                }
                if ($CP_Process.HasExited) {
                    Write-Error "Control plane exited unexpectedly."
                    exit 1
                }
                Start-Sleep -Seconds 1
            }
            if (-not $success) {
                Write-Error "Timed out waiting for control plane at $($env:CONTROL_PLANE_URL)"
                exit 1
            }
        }
    } elseif (-not (Get-ControlPlaneHealthy)) {
        Write-Warning "Control plane not reachable at $($env:CONTROL_PLANE_URL)"
        Write-Warning "  Start it in another terminal:"
        Write-Warning "    & `"$MvnCmd`" exec:java -pl trading-runtime -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain"
        Write-Warning "  Or re-run: .\scripts\run-tui.ps1 --with-control-plane"
        Write-Host ""
    }

    Write-Host "Trading Bridge TUI -> $($env:CONTROL_PLANE_URL)"
    & $MvnCmd exec:java -pl trading-tui -Dexec.mainClass=com.martinfou.trading.tui.TradingTuiMain

} finally {
    if ($CP_Process -and -not $CP_Process.HasExited) {
        Write-Host "Stopping background control plane..."
        Stop-Process -Id $CP_Process.Id -Force -ErrorAction SilentlyContinue
    }
    # Explicitly stop any leftover JVM processes just like the bash script does
    & "$Root\scripts\stop-control-plane.ps1" 2>$null
}
