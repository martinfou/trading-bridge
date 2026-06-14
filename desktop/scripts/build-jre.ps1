# Build a minimal JRE via jlink for the Trading Bridge control plane.
#
# Usage:
#   .\desktop\scripts\build-jre.ps1 <fat-jar-path> <output-dir>
#
# Example:
#   .\desktop\scripts\build-jre.ps1 ..\trading-runtime\target\*-shaded.jar .\desktop-resources

$ErrorActionPreference = "Stop"

if ($args.Length -lt 2) {
    Write-Error "Usage: .\desktop\scripts\build-jre.ps1 <fat-jar-path> <output-dir>"
    exit 1
}

$FatJarPattern = $args[0]
$OutputDir = $args[1]

# Resolve wildcard paths for fat jar
$FatJarFiles = Get-ChildItem -Path $FatJarPattern
if (-not $FatJarFiles) {
    Write-Error "FAT JAR not found at: $FatJarPattern"
    exit 1
}
$FatJar = $FatJarFiles[0].FullName

# Resolve output dir absolute path
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}
$OutputDir = Resolve-Path $OutputDir | Select-Object -ExpandProperty Path

# Detect Java Home
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\jlink.exe")) {
    # Try finding java in PATH
    $JavaPath = Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if ($JavaPath) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $JavaPath)
    }
}

if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\jlink.exe")) {
    Write-Error "ERROR: jlink not found. Set JAVA_HOME or add JDK 21+ to PATH."
    exit 1
}

$JLink = "$JavaHome\bin\jlink.exe"
$JDeps = "$JavaHome\bin\jdeps.exe"

Write-Host "==> Detecting module dependencies from $FatJar ..."
# Capture output of jdeps
$Modules = & $JDeps --ignore-missing-deps --print-module-deps $FatJar 2>$null
if ($Modules -is [array]) {
    $Modules = $Modules -join ","
}
$Modules = $Modules.Trim()
Write-Host "    Modules: $Modules"

$JreDir = Join-Path $OutputDir "jre"
if (Test-Path $JreDir) {
    Write-Host "Removing existing JRE directory at $JreDir ..."
    Remove-Item -Recurse -Force $JreDir
}

Write-Host "==> Building minimal JRE at $JreDir ..."
& $JLink `
  --add-modules $Modules `
  --output $JreDir `
  --no-header-files `
  --no-man-pages `
  --strip-debug `
  --compress=2 `
  --vm=server

Write-Host "==> Verifying JRE ..."
$JavaExe = Join-Path $JreDir "bin\java.exe"
if (Test-Path $JavaExe) {
    & $JavaExe --version
} else {
    Write-Error "JRE verification failed: java.exe not found at $JavaExe"
    exit 1
}

Write-Host "==> Done. JRE ready at $JreDir"
