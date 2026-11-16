# Starts the Tactic Arena simulator on Windows.
#
#   run.bat [results-repository-url]      (or: powershell -ExecutionPolicy Bypass -File run.ps1 [-RepoUrl <url>])
#
# Needs Java 17+ and git (with push rights on the results repository); Edge or Chrome
# for the automatic screenshots is optional.
param(
    [string]$RepoUrl = "https://github.com/CiprianAmza/footballmanager-tactic-arena-results.git",
    [int]$Port = 8390,
    [string]$SimHome = (Get-Location).Path,
    [string]$Java = "java",
    [string]$JavaOpts = "-Xmx2g"
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
New-Item -ItemType Directory -Force $SimHome | Out-Null
$SimHome = (Resolve-Path $SimHome).Path
$results = Join-Path $SimHome "results"
$data = Join-Path $SimHome "data"
$work = Join-Path $SimHome "work"
function Fwd([string]$p) { return $p.Replace('\', '/') }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "git is required" }
if (-not (Get-Command $Java -ErrorAction SilentlyContinue)) { throw "Java 17+ is required ($Java not found)" }

if (Test-Path (Join-Path $results ".git")) {
    Write-Host "== Updating the results repository in $results"
    & git -C $results pull --rebase --autostash
    if ($LASTEXITCODE -ne 0) { Write-Warning "git pull failed; continuing with the local copy" }
} else {
    Write-Host "== Cloning $RepoUrl into $results"
    & git clone $RepoUrl $results
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}
foreach ($d in @((Join-Path $results "tests"), $data, $work)) { New-Item -ItemType Directory -Force $d | Out-Null }

if (-not (Test-Path (Join-Path $data "fm.mv.db"))) {
    $world = Join-Path $results "baseline\world.zip"
    if (Test-Path $world) {
        Write-Host "== Starting from the base world of the repository"
        Expand-Archive -Path $world -DestinationPath $data -Force
    } else {
        Write-Host "== No base world in the repository yet."
        Write-Host "   This first start generates a game world for the simulator (about 8 minutes),"
        Write-Host "   then the base world is generated once (about 5 minutes, from the page or the first test)"
        Write-Host "   and pushed to the repository, so the next computers start in about a minute."
    }
}

$log = Join-Path $SimHome "simulator.log"
Write-Host "== Starting the simulator on port $Port (log: $log)"
Write-Host "   Open http://localhost:$Port/tactic-arena once the log says 'Started Main'. Stop with Ctrl+C."
$javaArgs = @()
$javaArgs += $JavaOpts.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
$javaArgs += @(
    "-Dfile.encoding=UTF-8", "-jar", (Join-Path $here "simulator.jar"),
    "--server.port=$Port",
    "--spring.datasource.url=jdbc:h2:file:$(Fwd $data)/fm;AUTO_SERVER=TRUE",
    "--spring.datasource.username=sa", "--spring.datasource.password=",
    "--spring.datasource.driver-class-name=org.h2.Driver",
    "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "--spring.flyway.locations=classpath:db/migration/h2",
    "--facelab.enabled=false", "--match2d.mode=off",
    "--cors.allowed-origins=http://localhost:$Port",
    "--tactic-arena.ui-dir=$(Fwd (Join-Path $here 'ui'))",
    "--tactic-arena.export-dir=$(Fwd (Join-Path $results 'tests'))",
    "--tactic-arena.baseline-dir=$(Fwd (Join-Path $results 'baseline'))",
    "--tactic-arena.work-dir=$(Fwd $work)",
    "--tactic-arena.git-push=true"
)
& $Java @javaArgs 2>&1 | Tee-Object -FilePath $log -Append
