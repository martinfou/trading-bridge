# Stop any Trading Bridge control plane (Maven exec:java or JVM main class).
#
# Usage:
#   .\scripts\stop-control-plane.ps1
#   .\scripts\stop-control-plane.ps1 --check-port
#   $env:CONTROL_PLANE_PORT=9090; .\scripts\stop-control-plane.ps1 --check-port

$ErrorActionPreference = "Stop"

$Port = if ($env:CONTROL_PLANE_PORT) { [int]$env:CONTROL_PLANE_PORT } else { 8080 }
$CheckPort = $false

foreach ($arg in $args) {
    if ($arg -eq "--check-port") {
        $CheckPort = $true
    } elseif ($arg -eq "-h" -or $arg -eq "--help") {
        Write-Host "Usage: .\scripts\stop-control-plane.ps1 [--check-port]"
        exit 0
    } else {
        Write-Error "Unknown option: $arg"
        exit 1
    }
}

$Stopped = 0

function Stop-Pattern {
    param (
        [string]$Pattern
    )
    # Get processes matching CommandLine pattern
    # We filter out PowerShell itself to avoid matching if the script contains the pattern in its own invocation arguments.
    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { 
        $_.CommandLine -like "*$Pattern*" -and $_.Name -ne "powershell.exe" -and $_.Name -ne "pwsh.exe"
    }
    
    if ($processes) {
        Write-Host "Stopping (Stop-Process): $Pattern"
        foreach ($proc in $processes) {
            try {
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
                $global:Stopped = 1
            } catch {
                # ignore errors if process already exited
            }
        }
    }
}

Stop-Pattern "com.martinfou.trading.runtime.ControlPlaneMain"
Stop-Pattern "exec:java -pl trading-runtime.*ControlPlaneMain"

Start-Sleep -Seconds 1

if ($Stopped -eq 0) {
    Write-Host "No control plane process found."
} else {
    Write-Host "Control plane stopped."
}

if ($CheckPort) {
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($connections) {
        Write-Host "Port $Port is still in use:" -ForegroundColor Red
        $connections | Format-Table LocalAddress, LocalPort, State, OwningProcess
        exit 1
    }
    Write-Host "Port $Port is free."
}
