# Start the Trading Bridge control plane (HTTP :8080).
#
# Usage: .\scripts\run-control-plane.ps1

$ErrorActionPreference = "Stop"

# Resolve the root directory of the repository
$ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
$Root = Resolve-Path (Join-Path $ScriptDir "..") | Select-Object -ExpandProperty Path
Set-Location $Root

# Set environment variables if not already set
if (-not $env:TRADING_BRIDGE_ROOT) {
    $env:TRADING_BRIDGE_ROOT = $Root
}
if (-not $env:CONTROL_PLANE_PORT) {
    $env:CONTROL_PLANE_PORT = "8080"
}

# Resolve Maven command
$MvnCmd = "mvn"
if (Test-Path "$Root\mvnw.cmd") {
    $MvnCmd = "$Root\mvnw.cmd"
}

Write-Host "Building trading-runtime (and dependencies)..."
& $MvnCmd -q -pl trading-runtime -am install -DskipTests

Write-Host "Control plane -> http://localhost:$($env:CONTROL_PLANE_PORT)"
Write-Host "Stop: Ctrl+C in this terminal, or: .\scripts\stop-control-plane.ps1"

& $MvnCmd exec:java -pl trading-runtime "-Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain"
