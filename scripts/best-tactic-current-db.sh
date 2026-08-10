#!/usr/bin/env bash
# Export the currently running backend, import that exact world into the test's
# private H2 database, then run BestTacticSearchIT without touching the live DB.
set -euo pipefail

cd "$(dirname "$0")/.."

TEAM_NAME="${1:-}"
SAMPLES="${BEST_TACTIC_SAMPLES:-60}"
FORMATIONS="${BEST_TACTIC_FORMATIONS:-}"
THREADS="${BEST_TACTIC_THREADS:-8}"

if [[ -z "$TEAM_NAME" || $# -ne 1 ]]; then
  echo "Usage: $0 \"<team name>\"" >&2
  echo "Example: $0 \"Sherlock FC\"" >&2
  echo "Optional: BEST_TACTIC_SAMPLES=60 BEST_TACTIC_FORMATIONS=442,4231" >&2
  exit 2
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/best-tactic.XXXXXX")"
SNAPSHOT_FILE="$WORK_DIR/current-h2.sql"
MAVEN_LOG="$WORK_DIR/maven.log"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

echo "Export datele curente și caut tactica pentru $TEAM_NAME ..."

JDK_HOME="${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
JAVA="$JDK_HOME/bin/java"
JAVAC="$JDK_HOME/bin/javac"
if [[ ! -x "$JAVA" || ! -x "$JAVAC" ]]; then
  echo "Nu găsesc JDK-ul. Setează JAVA_HOME_OVERRIDE către un JDK valid." >&2
  exit 1
fi

PID="$($JDK_HOME/bin/jcmd -l | awk '/com\.footballmanagergamesimulator\.Main/{print $1; exit}')"
if [[ -z "$PID" ]]; then
  echo "Backend-ul nu rulează." >&2
  exit 1
fi

HELPER_SRC="scripts/support/best-tactic-snapshot"
HELPER_CLASSES="$WORK_DIR/helper-classes"
mkdir -p "$HELPER_CLASSES" target/classes/com/footballmanagergamesimulator/tools
"$JAVAC" --release 17 --add-modules jdk.attach -d "$HELPER_CLASSES" "$HELPER_SRC"/*.java
# The target JVM's system class loader already watches target/classes. Only the
# tiny MBean must be visible there; the attach client stays in the temp folder.
cp "$HELPER_CLASSES"/com/footballmanagergamesimulator/tools/LocalH2Snapshot*.class \
  target/classes/com/footballmanagergamesimulator/tools/
"$JAVA" --add-modules jdk.attach -cp "$HELPER_CLASSES" \
  com.footballmanagergamesimulator.tools.SnapshotViaJmx "$PID" "$SNAPSHOT_FILE" >/dev/null
test -s "$SNAPSHOT_FILE" || { echo "Snapshot-ul H2 nu a fost creat." >&2; exit 1; }

MAVEN_ARGS=(
  -o
  test-compile
  failsafe:integration-test
  failsafe:verify
  -Dit.test=BestTacticSearchIT
  "-Dbest.tactic.team=$TEAM_NAME"
  "-Dbest.tactic.samples=$SAMPLES"
  "-Dbest.tactic.threads=$THREADS"
  "-Dbest.tactic.snapshot=$SNAPSHOT_FILE"
  "-Dbootstrap.snapshot-path=$SNAPSHOT_FILE"
  -Dspring.jpa.hibernate.ddl-auto=update
  -Dchairman.enabled=false
)
if [[ -n "$FORMATIONS" ]]; then
  MAVEN_ARGS+=("-Dbest.tactic.formations=$FORMATIONS")
fi

if ! mvn "${MAVEN_ARGS[@]}" 2>&1 | tee "$MAVEN_LOG"; then
  exit 1
fi

echo
echo "================ REZULTAT ================"
awk '/=== BEST TACTIC PER CLUB ===/{lines=13; next} lines>0 {print; lines--}' "$MAVEN_LOG"
