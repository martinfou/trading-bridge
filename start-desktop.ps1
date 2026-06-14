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
Check-Command "npm" "Node.js Package Manager (npm)"
Write-Host "Prerequisites OK."
Write-Host ""

$ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
$Root = Resolve-Path $ScriptDir | Select-Object -ExpandProperty Path
Set-Location $Root

Write-Host "=== 1. Setting up Java Environment ==="
if (-not $env:JAVA_HOME) {
    # Try to find Java Home
    $JavaPath = Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if ($JavaPath) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $JavaPath)
        $env:JAVA_HOME = $JavaHome
        Write-Host "Set JAVA_HOME=$JavaHome"
    } else {
        Write-Warning "JAVA_HOME is not set. The desktop app will try to use the system default 'java'."
    }
} else {
    Write-Host "Using existing JAVA_HOME=$($env:JAVA_HOME)"
}

Write-Host "=== 2. Setting up Frontend ==="
$DesktopDir = Join-Path $Root "desktop"
Set-Location $DesktopDir

$NodeModules = Join-Path $DesktopDir "node_modules"
if (-not (Test-Path $NodeModules)) {
    Write-Host "node_modules not found in desktop directory, installing dependencies..."
    # npm is a cmd file on Windows, run with cmd.exe /c
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm install" -NoNewWindow -Wait
}

Write-Host "=== 3. Starting Desktop Application ==="
# Set environment variable NO_CONTROL_PLANE to true to skip launching the Java backend from desktop client (dev mode)
$env:NO_CONTROL_PLANE = "true"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm run electron:dev" -NoNewWindow -Wait
