$ErrorActionPreference = "Stop"

function Check-Command {
    param (
        [string]$Cmd,
        [string]$FriendlyName
    )
    $path = Get-Command $Cmd -ErrorAction SilentlyContinue
    if (-not $path) {
        Write-Host "========================================================================" -ForegroundColor Red
        Write-Host "ERROR: $FriendlyName ('$Cmd') is not installed or not in your PATH." -ForegroundColor Red
        Write-Host "Please install it before running this script." -ForegroundColor Red
        Write-Host "========================================================================" -ForegroundColor Red
        exit 1
    }
}

Write-Host "=== Checking Prerequisites ==="
Check-Command "java" "Java Runtime Environment"
Write-Host "Prerequisites OK."
Write-Host ""

$ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
$Root = Resolve-Path $ScriptDir | Select-Object -ExpandProperty Path
Set-Location $Root

$Port = 8080
$HealthUrl = "http://localhost:$Port/api/sq-bridge/status"

$ControlPlaneProcess = $null

function Get-ControlPlaneHealthy {
    try {
        $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 2
        return $true
    } catch {
        return $false
    }
}

function Cleanup {
    if ($ControlPlaneProcess -and -not $ControlPlaneProcess.HasExited) {
        Write-Host ""
        Write-Host "=== Shutting down background Control Plane ==="
        Stop-Process -Id $ControlPlaneProcess.Id -Force -ErrorAction SilentlyContinue
        Write-Host "Control Plane stopped."
    }
}

try {
    Write-Host "=== 1. Compiling Project ==="
    # Use mvnw.cmd on Windows, fallback to mvn
    $MvnCmd = "mvn"
    if (Test-Path "$Root\mvnw.cmd") {
        $MvnCmd = "$Root\mvnw.cmd"
    }
    
    # Run compiler
    & $MvnCmd compile
    
    $ControlPlaneAlreadyRunning = $false
    if (Get-ControlPlaneHealthy) {
        Write-Host "=== Control Plane is already running on port $Port ==="
        $ControlPlaneAlreadyRunning = $true
    } else {
        Write-Host "=== 2. Starting Control Plane in the background ==="
        $LogPath = Join-Path $Root "control-plane.log"
        
        # Start background process redirected to control-plane.log
        $ControlPlaneProcess = Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$MvnCmd`" exec:java -pl trading-runtime -Dexec.mainClass=`"com.martinfou.trading.runtime.ControlPlaneMain`" > `"$LogPath`" 2>&1" -NoNewWindow -PassThru
        
        Write-Host "Control Plane started in background."
        Write-Host "Waiting for Control Plane to become healthy on $HealthUrl..."
        
        $success = $false
        for ($count = 0; $count -lt 30; $count++) {
            Start-Sleep -Seconds 1
            if (Get-ControlPlaneHealthy) {
                $success = $true
                break
            }
            if ($ControlPlaneProcess.HasExited) {
                Write-Host "ERROR: Control Plane exited unexpectedly. Check control-plane.log for details." -ForegroundColor Red
                exit 1
            }
        }
        if (-not $success) {
            Write-Host "ERROR: Control Plane failed to start within 30 seconds." -ForegroundColor Red
            Write-Host "Check control-plane.log for details." -ForegroundColor Red
            exit 1
        }
        Write-Host "Control Plane is UP and healthy."
    }
    
    Write-Host ""
    Write-Host "=== 3. Starting Trading TUI Client ==="
    & $MvnCmd exec:java -pl trading-tui -Dexec.mainClass="com.martinfou.trading.tui.TradingTuiMain"
    
} finally {
    Cleanup
}
