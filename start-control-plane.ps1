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

Write-Host "=== Starting Control Plane ==="
# Delegate to internal script
& "$Root\scripts\run-control-plane.ps1"
