# Builds the Tactic Arena simulator package into simulator\dist\ (Windows PowerShell).
#
#   powershell -ExecutionPolicy Bypass -File simulator\build.ps1 -Frontend <frontend-dir> [-SkipUi] [-MavenArgs "-o"]
param(
    [Parameter(Mandatory = $true)][string]$Frontend,
    [switch]$SkipUi,
    [string]$Maven = "mvn",
    [string]$MavenArgs = ""
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$front = (Resolve-Path $Frontend).Path
$dist = Join-Path $here "dist"

if (-not $SkipUi) {
    Write-Host "== Building the interface (ng build --configuration simulator) in $front"
    Push-Location $front
    try { & npx --no-install ng build --configuration simulator; if ($LASTEXITCODE -ne 0) { throw "ng build failed" } }
    finally { Pop-Location }
}
$ui = Join-Path $front "dist\simulator"
if (Test-Path (Join-Path $ui "browser\index.html")) { $ui = Join-Path $ui "browser" }
if (-not (Test-Path (Join-Path $ui "index.html"))) { throw "No $ui\index.html: the interface build is missing" }

Write-Host "== Packaging the backend (mvn package -DskipTests)"
Push-Location $root
try {
    $mvnArgs = @()
    if ($MavenArgs) { $mvnArgs += $MavenArgs.Split(" ") }
    $mvnArgs += @("-q", "package", "-DskipTests")
    & $Maven @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
}
finally { Pop-Location }
$jar = Get-ChildItem (Join-Path $root "target\*.jar") | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "No executable jar in $root\target" }

Write-Host "== Assembling $dist"
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $jar.FullName (Join-Path $dist "simulator.jar")
Copy-Item -Recurse $ui (Join-Path $dist "ui")
foreach ($f in @("run.sh", "run.ps1", "run.bat", "README.md")) { Copy-Item (Join-Path $here $f) $dist }
Write-Host "Done. Copy $dist anywhere and start it with run.bat (Windows) or run.sh (Linux/macOS)."
