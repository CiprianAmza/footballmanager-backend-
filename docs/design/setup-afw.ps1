<#
.SYNOPSIS
  Clones (or updates) Argus Football World (backend + frontend) and runs it locally.

.DESCRIPTION
  1. Checks the prerequisites (git, git-lfs, Docker, Maven, Node, a JDK 17+).
  2. Clones or updates both repositories under -Root.
  3. Starts PostgreSQL with docker compose and waits until it is healthy.
  4. Builds the backend (tests skipped on the first run) and starts it on port 8086.
  5. Installs the frontend dependencies and starts it on port 4200.
  Logs and PIDs go to <Root>\logs and <Root>\run.

  Works in Windows PowerShell 5.1 and PowerShell 7.

.EXAMPLE
  .\setup-afw.ps1                 # clone/update, build, start everything
  .\setup-afw.ps1 -WithSave       # also download the 411 MB bundled save (Git LFS)
  .\setup-afw.ps1 -SkipBuild      # start without rebuilding the backend
  .\setup-afw.ps1 -Stop           # stop backend, frontend and PostgreSQL
  .\setup-afw.ps1 -NoFastSim      # run on PostgreSQL directly (the old mode)

  By default (decided 2026-11-12) the backend runs with the fast-sim profile: the game lives in an
  in-memory H2 database and is saved to PostgreSQL (database footballmanager_checkpoint) every
  5 matchdays, at each season transition and with "Save checkpoint now" on the Home page.
  On start it loads the latest save; with no save it generates a new world (about 7 minutes).
#>
param(
    [string]$Root = "C:\Users\cipriana\Projects\argus",
    [string]$JavaHome = "",
    [switch]$WithSave,
    [switch]$SkipBuild,
    [switch]$Stop,
    [switch]$NoFastSim
)

$ErrorActionPreference = "Stop"

$BackendRepo  = "https://github.com/CiprianAmza/footballmanager-backend-.git"
$FrontendRepo = "https://github.com/CiprianAmza/footballmanager-frontend-test.git"
$BackendDir   = Join-Path $Root "footballmanager-backend-"
$FrontendDir  = Join-Path $Root "footballmanager-frontend-test"
$LogDir       = Join-Path $Root "logs"
$RunDir       = Join-Path $Root "run"
$BackendPort  = 8086
$FrontendPort = 4200

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    !!  $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "    XX  $msg" -ForegroundColor Red; exit 1 }

function Test-Port([int]$port) {
    # Both IPv4 and IPv6: the Angular dev server on Node 17+ listens on ::1 only.
    foreach ($address in "127.0.0.1", "::1") {
        $ip = [System.Net.IPAddress]::Parse($address)
        $client = New-Object System.Net.Sockets.TcpClient($ip.AddressFamily)
        try { $client.Connect($ip, $port); return $true } catch { } finally { $client.Close() }
    }
    return $false
}

function Wait-Port([int]$port, [int]$timeoutSec, [string]$name, [string]$log) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { Ok "$name is listening on port $port"; return }
        Start-Sleep -Seconds 3
    }
    Warn "$name did not open port $port within $timeoutSec s. Last log lines ($log):"
    if (Test-Path $log) { Get-Content $log -Tail 40 | ForEach-Object { Write-Host "      $_" } }
    Fail "$name failed to start"
}

function Stop-FromPidFile([string]$name) {
    $pidFile = Join-Path $RunDir "$name.pid"
    if (Test-Path $pidFile) {
        $procId = Get-Content $pidFile
        & taskkill.exe /T /F /PID $procId 2>$null | Out-Null
        Remove-Item $pidFile -Force
        Ok "stopped $name (PID $procId)"
    } else {
        Warn "no PID file for $name"
    }
}

function Start-Logged([string]$name, [string]$workDir, [string]$command) {
    $log = Join-Path $LogDir "$name.log"
    "---- $(Get-Date -Format s) starting: $command" | Out-File -FilePath $log -Append -Encoding utf8
    # /s + outer quotes: cmd.exe keeps the inner quoted paths intact.
    $proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/s /c `"$command 1>> `"$log`" 2>&1`"" `
        -WorkingDirectory $workDir -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath (Join-Path $RunDir "$name.pid") -Encoding ascii
    Ok "$name started (PID $($proc.Id)), log: $log"
    return $log
}

# ---------------------------------------------------------------- stop mode
if ($Stop) {
    Step "Stopping Argus Football World"
    Stop-FromPidFile "frontend"
    Stop-FromPidFile "backend"
    if (Test-Path $BackendDir) {
        Push-Location $BackendDir; docker compose stop postgres | Out-Null; Pop-Location
        Ok "PostgreSQL stopped (data kept in the Docker volume postgres-data)"
    }
    exit 0
}

New-Item -ItemType Directory -Force -Path $Root, $LogDir, $RunDir | Out-Null

# ---------------------------------------------------------------- prerequisites
Step "Checking prerequisites"
foreach ($tool in "git", "docker", "mvn", "node", "npm") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "$tool is not on PATH" }
    Ok "$tool found"
}
& git lfs version *> $null
if ($LASTEXITCODE -ne 0) { Fail "git-lfs is not installed (needed for the bundled save)" }
Ok "git-lfs found"

& docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail "Docker is not running. Start Docker Desktop and run the script again." }
Ok "Docker is running"

# JDK 17+: explicit -JavaHome, then AFW_JAVA_HOME, then known locations, then JAVA_HOME.
$jdkCandidates = @($JavaHome, $env:AFW_JAVA_HOME,
    "C:\Users\cipriana\.jdks\ms-21.0.12.1", "C:\dev\Java21\jdk-21.0.2", $env:JAVA_HOME) |
    Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) }
$selectedJdk = $null
foreach ($jdk in $jdkCandidates) {
    $versionLine = (& cmd.exe /c "`"$jdk\bin\java.exe`" -version 2>&1" | Select-String 'version "').Line
    if ($versionLine -match 'version "(\d+)') {
        if ([int]$Matches[1] -ge 17) { $selectedJdk = $jdk; break }
    }
}
if (-not $selectedJdk) { Fail "No JDK 17+ found. Pass -JavaHome <path to a JDK 17 or 21>." }
$env:JAVA_HOME = $selectedJdk
$env:Path = "$selectedJdk\bin;$env:Path"
Ok "using JDK at $selectedJdk"

$nodeMajor = [int]((& node -v).TrimStart("v").Split(".")[0])
if ($nodeMajor -ne 20) {
    Warn "Node $nodeMajor detected; the project documents Node 20 (Angular 15). If 'npm start' fails, install Node 20 and run again."
} else {
    Ok "Node 20"
}

# ---------------------------------------------------------------- clone / update
Step "Cloning or updating the repositories"
if (-not $WithSave) {
    $env:GIT_LFS_SKIP_SMUDGE = "1"
    Warn "skipping the 411 MB bundled save (use -WithSave to download it)"
}
foreach ($pair in @(@($BackendRepo, $BackendDir), @($FrontendRepo, $FrontendDir))) {
    $url = $pair[0]; $dir = $pair[1]
    if (Test-Path (Join-Path $dir ".git")) {
        $dirty = & git -C $dir status --porcelain
        if ($dirty) {
            Warn "$dir has local changes; not pulling (commit or stash them first)"
        } else {
            & git -C $dir pull --ff-only
            if ($LASTEXITCODE -ne 0) { Fail "git pull failed in $dir" }
            Ok "updated $dir"
        }
    } else {
        & git clone $url $dir
        if ($LASTEXITCODE -ne 0) { Fail "git clone failed for $url" }
        Ok "cloned $dir"
    }
}
if ($WithSave) {
    & git -C $BackendDir lfs pull
    if ($LASTEXITCODE -ne 0) { Fail "git lfs pull failed" }
    Ok "bundled save downloaded"
}
Remove-Item Env:\GIT_LFS_SKIP_SMUDGE -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- PostgreSQL
Step "Starting PostgreSQL (docker compose)"
if (Test-Port 5432) {
    $ownContainer = $null
    Push-Location $BackendDir; $ownContainer = & docker compose ps -q postgres; Pop-Location
    if (-not $ownContainer) { Warn "port 5432 is already used by something else; the backend may connect to the wrong database" }
}
Push-Location $BackendDir
& docker compose up -d postgres
if ($LASTEXITCODE -ne 0) { Pop-Location; Fail "docker compose up failed" }
$containerId = (& docker compose ps -q postgres).Trim()
Pop-Location
$deadline = (Get-Date).AddSeconds(90)
$health = ""
while ((Get-Date) -lt $deadline) {
    $health = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
    if ($health -eq "healthy") { break }
    Start-Sleep -Seconds 3
}
if ($health -ne "healthy") { Fail "PostgreSQL is not healthy (status: $health)" }
Ok "PostgreSQL is healthy"

# ---------------------------------------------------------------- backend
Step "Backend"
if (Test-Port $BackendPort) { Fail "port $BackendPort is already in use (run .\setup-afw.ps1 -Stop first?)" }
$jar = Join-Path $BackendDir "target\football-manager-simulator-0.0.1-SNAPSHOT.jar"
if (-not $SkipBuild -or -not (Test-Path $jar)) {
    Write-Host "    building (mvn -DskipTests clean package), this takes a few minutes..."
    Push-Location $BackendDir
    # Through cmd.exe: Windows PowerShell 5.1 turns redirected stderr lines (for example
    # "Picked up JAVA_TOOL_OPTIONS") into terminating errors when ErrorActionPreference is Stop.
    & cmd.exe /c "mvn -B -DskipTests clean package 1> `"$LogDir\backend-build.log`" 2>&1"
    $buildExit = $LASTEXITCODE
    Pop-Location
    if ($buildExit -ne 0) {
        Get-Content (Join-Path $LogDir "backend-build.log") -Tail 40 | ForEach-Object { Write-Host "      $_" }
        Fail "backend build failed (full log: $LogDir\backend-build.log)"
    }
    Ok "backend built"
}
if ($NoFastSim) {
    $backendLog = Start-Logged "backend" $BackendDir "`"$selectedJdk\bin\java.exe`" -jar `"$jar`""
    Wait-Port $BackendPort 240 "backend" $backendLog
} else {
    # Saves go to their own PostgreSQL database; create it once.
    Push-Location $BackendDir
    $exists = (& docker compose exec -T postgres psql -U footballmanager -d footballmanager -tAc "SELECT 1 FROM pg_database WHERE datname='footballmanager_checkpoint'")
    if (-not "$exists".Trim()) {
        & docker compose exec -T postgres psql -U footballmanager -d footballmanager -c "CREATE DATABASE footballmanager_checkpoint;" | Out-Null
        Ok "created the save database footballmanager_checkpoint"
    }
    Pop-Location
    $env:CHECKPOINT_DB_URL = "jdbc:postgresql://localhost:5432/footballmanager_checkpoint"
    $backendLog = Start-Logged "backend" $BackendDir "`"$selectedJdk\bin\java.exe`" -jar `"$jar`" --spring.profiles.active=fast-sim"
    # A first start without a save generates the world in memory (about 7 minutes).
    Wait-Port $BackendPort 720 "backend" $backendLog
}

# ---------------------------------------------------------------- frontend
Step "Frontend"
if (Test-Port $FrontendPort) { Fail "port $FrontendPort is already in use (run .\setup-afw.ps1 -Stop first?)" }
if (-not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Host "    installing dependencies (npm ci), this takes a few minutes..."
    Push-Location $FrontendDir
    & cmd.exe /c "npm ci 1> `"$LogDir\frontend-install.log`" 2>&1"
    $installExit = $LASTEXITCODE
    Pop-Location
    if ($installExit -ne 0) {
        Get-Content (Join-Path $LogDir "frontend-install.log") -Tail 40 | ForEach-Object { Write-Host "      $_" }
        Fail "npm ci failed (full log: $LogDir\frontend-install.log)"
    }
    Ok "frontend dependencies installed"
}
$frontendLog = Start-Logged "frontend" $FrontendDir "npm start"
Wait-Port $FrontendPort 300 "frontend" $frontendLog

# ---------------------------------------------------------------- done
Step "Argus Football World is running"
Write-Host "    Frontend: http://localhost:$FrontendPort"
Write-Host "    Backend:  http://localhost:$BackendPort"
Write-Host "    Logs:     $LogDir"
Write-Host "    Stop:     .\setup-afw.ps1 -Stop -Root `"$Root`""
if ($WithSave) {
    Write-Host ""
    Write-Host "    To load the bundled save (season 15, Sherlock FC), after logging in:"
    Write-Host "    curl.exe -X POST -H `"Content-Type: application/json`" --data-binary `"@$BackendDir\saves\ciprian-sherlock-season-15-day-341.json`" http://localhost:$BackendPort/game/import"
}
