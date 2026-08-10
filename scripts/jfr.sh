#!/usr/bin/env bash
#
# Java Flight Recorder control for a running backend. JFR costs ~1-2% and is
# built into the JVM, so it can be turned on against a process that is already
# misbehaving — no restart, no agent.
#
# Typical loop:
#   ./scripts/jfr.sh start          # then trigger the slow request
#   ./scripts/jfr.sh dump           # writes target/jfr/<timestamp>.jfr
#   ./scripts/jfr.sh stop
#   ./scripts/jfr.sh hot target/jfr/<timestamp>.jfr
#
# Commands:
#   pid                  show the backend PID
#   status               list recordings on the running JVM
#   start [name]         begin recording (settings=profile)
#   dump [name]          write the recording so far to a file, keep recording
#   stop [name]          dump and stop
#   summary <file>       event counts — the fastest "what kind of problem is this"
#   hot <file>           hottest project methods, from the CPU samples
#   hotall <file>        hottest methods overall (JDK/Hibernate included)
#   open <file>          open in VisualVM
#
set -euo pipefail

cd "$(dirname "$0")/.."

# Newest JDK first. The tools have to be at least as new as the JVM they are
# pointed at: jcmd attaches to an older JVM happily, but `jfr print` cannot read
# a recording produced by a newer one. The backend runs on 17 via
# scripts/profile-app.sh and on whatever the IDE run configuration says
# otherwise (JDK 26 by default here), so pick the newest and cover both.
detect_jdk() {
  if [[ -n "${JAVA_HOME_OVERRIDE:-}" ]]; then
    echo "$JAVA_HOME_OVERRIDE"
    return
  fi
  local candidate
  for candidate in /opt/homebrew/opt/openjdk \
                   /opt/homebrew/opt/openjdk@26 \
                   /opt/homebrew/opt/openjdk@17; do
    if [[ -x "$candidate/libexec/openjdk.jdk/Contents/Home/bin/jcmd" ]]; then
      echo "$candidate/libexec/openjdk.jdk/Contents/Home"
      return
    fi
  done
  echo "No JDK found. Set JAVA_HOME_OVERRIDE to a JDK home." >&2
  exit 1
}

JAVA_HOME="$(detect_jdk)"
JCMD="$JAVA_HOME/bin/jcmd"
JFR_TOOL="$JAVA_HOME/bin/jfr"
RECORDING_DIR="target/jfr"
DEFAULT_NAME="fm"
MAIN_CLASS="com.footballmanagergamesimulator.Main"

find_pid() {
  # spring-boot:run forks the app into its own JVM, so match the main class
  # rather than the Maven process that launched it.
  local pid
  pid="$("$JCMD" -l 2>/dev/null | grep -F "$MAIN_CLASS" | head -1 | cut -d' ' -f1 || true)"
  if [[ -z "$pid" ]]; then
    echo "Backend not running (no JVM with $MAIN_CLASS)." >&2
    echo "Start it with: ./scripts/profile-app.sh" >&2
    exit 1
  fi
  echo "$pid"
}

timestamped_file() {
  mkdir -p "$RECORDING_DIR"
  echo "$RECORDING_DIR/$(date +%Y%m%d-%H%M%S).jfr"
}

require_file() {
  [[ -n "${1:-}" && -f "$1" ]] || { echo "Usage: $0 $2 <recording.jfr>" >&2; exit 1; }
}

COMMAND="${1:-status}"
shift || true
NAME="${1:-$DEFAULT_NAME}"

case "$COMMAND" in
  pid)
    find_pid
    ;;

  status)
    "$JCMD" "$(find_pid)" JFR.check
    ;;

  start)
    PID="$(find_pid)"
    # A request passes through dozens of Tomcat/Spring/Hibernate frames before
    # it reaches our code, so the recording's default depth of 64 can cut the
    # project frames off the bottom of deep stacks. Raising it only works while
    # no recording is running, hence best-effort.
    # (The other half of this is on the reading side: `jfr print` shows only 5
    # frames unless told otherwise — see --stack-depth in `hot` below.)
    "$JCMD" "$PID" JFR.configure stackdepth=256 >/dev/null 2>&1 \
      || echo "(could not raise stack depth — a recording is probably already running)"
    # settings=profile is the detailed preset (~2% overhead). Swap to
    # settings=default (~1%) for a recording meant to run for hours.
    "$JCMD" "$PID" JFR.start \
      name="$NAME" settings=profile maxsize=512m maxage=1h
    echo
    echo "Recording. Trigger the slow work now, then: $0 dump"
    ;;

  dump)
    FILE="$(timestamped_file)"
    "$JCMD" "$(find_pid)" JFR.dump name="$NAME" filename="$PWD/$FILE"
    echo
    echo "Wrote $FILE"
    echo "  $0 summary $FILE"
    echo "  $0 hot $FILE"
    ;;

  stop)
    FILE="$(timestamped_file)"
    "$JCMD" "$(find_pid)" JFR.stop name="$NAME" filename="$PWD/$FILE"
    echo
    echo "Wrote $FILE"
    ;;

  summary)
    require_file "${1:-}" summary
    "$JFR_TOOL" summary "$1"
    ;;

  hot)
    require_file "${1:-}" hot
    # For each CPU sample, the topmost frame that belongs to this project —
    # i.e. "which of my methods was executing", with JDK and framework frames
    # below it collapsed away. Crude next to a flame graph, but it answers
    # where the time goes in one command.
    "$JFR_TOOL" print --events ExecutionSample --stack-depth 2048 "$1" \
      | awk '
          /stackTrace = \[/            { inside = 1; found = 0; next }
          inside && /^[[:space:]]*\]/  { inside = 0; next }
          inside && !found && /com\.footballmanagergamesimulator/ {
            sub(/ line:.*/, ""); gsub(/^[[:space:]]+/, "")
            print; found = 1
          }' \
      | sort | uniq -c | sort -rn | head -40
    ;;

  hotall)
    require_file "${1:-}" hotall
    # Topmost frame regardless of package. Use when `hot` comes back empty or
    # flat: the time is then going into the JDK, Hibernate or the driver.
    "$JFR_TOOL" print --events ExecutionSample --stack-depth 2048 "$1" \
      | awk '
          /stackTrace = \[/ { getline; sub(/ line:.*/, ""); gsub(/^[[:space:]]+/, ""); print }' \
      | sort | uniq -c | sort -rn | head -40
    ;;

  open)
    require_file "${1:-}" open
    open -a VisualVM "$1"
    ;;

  *)
    sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
