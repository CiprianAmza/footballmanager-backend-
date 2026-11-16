#!/usr/bin/env bash
# Builds the Tactic Arena simulator package into simulator/dist/.
#
#   simulator/build.sh <frontend-dir>
#
# <frontend-dir> is a checkout of the Angular frontend with its node_modules installed.
# Environment (all optional):
#   MVN       Maven command (default: mvn)
#   MVN_ARGS  extra Maven arguments, e.g. -o for an offline build
#   NG        Angular CLI command (default: npx --no-install ng)
#   SKIP_UI=1 reuse an existing <frontend-dir>/dist/simulator instead of building it
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
FRONT="${1:?usage: simulator/build.sh <frontend-dir>}"
FRONT="$(cd "$FRONT" && pwd)"
DIST="$HERE/dist"

if [ "${SKIP_UI:-0}" != "1" ]; then
  echo "== Building the interface (ng build --configuration simulator) in $FRONT"
  (cd "$FRONT" && ${NG:-npx --no-install ng} build --configuration simulator)
fi
UI="$FRONT/dist/simulator"
[ -f "$UI/browser/index.html" ] && UI="$UI/browser"
[ -f "$UI/index.html" ] || { echo "No $UI/index.html: the interface build is missing" >&2; exit 1; }

echo "== Packaging the backend (mvn package -DskipTests)"
(cd "$ROOT" && ${MVN:-mvn} ${MVN_ARGS:-} -q package -DskipTests)
JAR="$(ls "$ROOT"/target/*.jar | grep -v '\.original$' | head -1)"
[ -f "$JAR" ] || { echo "No executable jar in $ROOT/target" >&2; exit 1; }

echo "== Assembling $DIST"
rm -rf "$DIST"
mkdir -p "$DIST"
cp "$JAR" "$DIST/simulator.jar"
cp -r "$UI" "$DIST/ui"
cp "$HERE/run.sh" "$HERE/run.ps1" "$HERE/run.bat" "$HERE/README.md" "$DIST/"
chmod +x "$DIST/run.sh"
du -sh "$DIST" 2>/dev/null || true
echo "Done. Copy $DIST anywhere and start it with run.sh (Linux/macOS/Git Bash) or run.bat (Windows)."
