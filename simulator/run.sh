#!/usr/bin/env bash
# Starts the Tactic Arena simulator (Linux, macOS, Git Bash on Windows).
#
#   ./run.sh [results-repository-url]
#
# Needs Java 17+ and git (with push rights on the results repository); Edge or Chrome
# for the automatic screenshots is optional.
# Environment (all optional):
#   PORT=8390        port of the simulator (page: http://localhost:$PORT/tactic-arena)
#   SIM_HOME=$PWD    work folder: results/ (the clone), data/ (database), work/, simulator.log
#   JAVA=java        Java 17+ command
#   JAVA_OPTS=-Xmx2g options of the simulator JVM
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_URL="${1:-https://github.com/CiprianAmza/footballmanager-tactic-arena-results.git}"
SIM_HOME="${SIM_HOME:-$PWD}"
PORT="${PORT:-8390}"
JAVA="${JAVA:-java}"
mkdir -p "$SIM_HOME"
SIM_HOME="$(cd "$SIM_HOME" && pwd)"
RESULTS="$SIM_HOME/results"
DATA="$SIM_HOME/data"
WORK="$SIM_HOME/work"

# Windows paths for Java when running under Git Bash.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
"$JAVA" -version >/dev/null 2>&1 || { echo "Java 17+ is required ($JAVA not found)" >&2; exit 1; }

if [ -d "$RESULTS/.git" ]; then
  echo "== Updating the results repository in $RESULTS"
  git -C "$RESULTS" pull --rebase --autostash || echo "warning: git pull failed; continuing with the local copy"
else
  echo "== Cloning $REPO_URL into $RESULTS"
  git clone "$REPO_URL" "$RESULTS"
fi
mkdir -p "$RESULTS/tests" "$DATA" "$WORK"

if [ ! -f "$DATA/fm.mv.db" ]; then
  if [ -f "$RESULTS/baseline/world.zip" ]; then
    echo "== Starting from the base world of the repository"
    if command -v unzip >/dev/null 2>&1; then
      unzip -o -q "$RESULTS/baseline/world.zip" -d "$DATA"
    else
      python3 -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$RESULTS/baseline/world.zip" "$DATA"
    fi
  else
    echo "== No base world in the repository yet."
    echo "   This first start generates a game world for the simulator (about 8 minutes),"
    echo "   then the base world is generated once (about 5 minutes, from the page or the first test)"
    echo "   and pushed to the repository, so the next computers start in about a minute."
  fi
fi

echo "== Starting the simulator on port $PORT (log: $SIM_HOME/simulator.log)"
echo "   Open http://localhost:$PORT/tactic-arena once the log says 'Started Main'. Stop with Ctrl+C."
"$JAVA" ${JAVA_OPTS:--Xmx2g} -Dfile.encoding=UTF-8 -jar "$(native "$HERE/simulator.jar")" \
  --server.port="$PORT" \
  --spring.datasource.url="jdbc:h2:file:$(native "$DATA")/fm;AUTO_SERVER=TRUE" \
  --spring.datasource.username=sa --spring.datasource.password= \
  --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.jpa.database-platform=org.hibernate.dialect.H2Dialect \
  --spring.flyway.locations=classpath:db/migration/h2 \
  --facelab.enabled=false --match2d.mode=off \
  --cors.allowed-origins="http://localhost:$PORT" \
  --tactic-arena.ui-dir="$(native "$HERE/ui")" \
  --tactic-arena.export-dir="$(native "$RESULTS/tests")" \
  --tactic-arena.baseline-dir="$(native "$RESULTS/baseline")" \
  --tactic-arena.work-dir="$(native "$WORK")" \
  --tactic-arena.git-push=true \
  2>&1 | tee -a "$SIM_HOME/simulator.log"
