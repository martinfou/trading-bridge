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
$Root = Resolve-Path (Join-Path $ScriptDir "..") | Select-Object -ExpandProperty Path
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
$Port = if ($env:CONTROL_PLANE_PORT) { $env:CONTROL_PLANE_PORT } else { "8080" }
$ShadedJar = Join-Path $Root "trading-runtime\target\trading-runtime-1.0.0-SNAPSHOT-shaded.jar"

if (-not $env:NO_CONTROL_PLANE) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:$Port/api/strategies" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        if ($resp.StatusCode -eq 200) {
            Write-Host "Control Plane already running on port $Port — reusing existing server."
            $env:NO_CONTROL_PLANE = "true"
        }
    } catch {
        if (-not (Test-Path $ShadedJar)) {
            Write-Host "Backend JAR not found — building trading-runtime..."
            Push-Location $Root
            & .\mvnw.cmd package -pl trading-runtime -am -DskipTests
            Pop-Location
        }
    }
}

Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm run electron:dev" -NoNewWindow -Wait
