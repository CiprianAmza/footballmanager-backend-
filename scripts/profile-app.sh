#!/usr/bin/env bash
#
# Start the backend with profiling instrumentation attached.
#
#   ./scripts/profile-app.sh                      # profiling-ready, no recording yet
#   ./scripts/profile-app.sh --record             # record from the first millisecond
#   ./scripts/profile-app.sh --record --duration 120s
#   ./scripts/profile-app.sh --sqldebug           # add the SQL logging profile
#
# Anything after `--` is passed straight to Maven, e.g.
#   ./scripts/profile-app.sh -- -Dspring-boot.run.profiles=sqldebug,other
#
# Once it is up:
#   * VisualVM  — open /Applications/VisualVM.app, the process appears under Local
#   * JFR       — ./scripts/jfr.sh start | dump | stop
#
set -euo pipefail

cd "$(dirname "$0")/.."

# Pin the JDK the project actually targets. Without this Maven runs on whatever
# `mvn -v` reports (JDK 26 on this machine), which would leave VisualVM — itself
# running on 17 — unable to attach cleanly.
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "No JDK at $JAVA_HOME. Set JAVA_HOME_OVERRIDE to a valid JDK home." >&2
  exit 1
fi

RECORD=false
DURATION=""
MAVEN_ARGS=()
RECORDING_DIR="target/jfr"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --record)   RECORD=true; shift ;;
    --duration) DURATION="$2"; shift 2 ;;
    --sqldebug) MAVEN_ARGS+=("-Dspring-boot.run.profiles=sqldebug"); shift ;;
    --)         shift; MAVEN_ARGS+=("$@"); break ;;
    *)          MAVEN_ARGS+=("$1"); shift ;;
  esac
done

mkdir -p "$RECORDING_DIR"

# DebugNonSafepoints is the flag that makes JFR's method sampling trustworthy.
# Without it the JVM only records stacks at safepoints, which systematically
# blames the wrong lines inside hot loops — exactly the code a season
# simulation spends its time in.
JVM_ARGS=(
  -XX:+UnlockDiagnosticVMOptions
  -XX:+DebugNonSafepoints
  -XX:FlightRecorderOptions=stackdepth=256
)

if [[ "$RECORD" == true ]]; then
  RECORDING_FILE="$RECORDING_DIR/startup.jfr"
  START_OPTS="name=fm,settings=profile,filename=$RECORDING_FILE,dumponexit=true,maxsize=512m"
  [[ -n "$DURATION" ]] && START_OPTS="$START_OPTS,duration=$DURATION"
  JVM_ARGS+=("-XX:StartFlightRecording=$START_OPTS")
  echo "Recording to $RECORDING_FILE (settings=profile)"
else
  echo "Profiling flags on, recording idle. Start one with: ./scripts/jfr.sh start"
fi

echo "JDK: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo

exec mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="${JVM_ARGS[*]}" \
  "${MAVEN_ARGS[@]}"
