#!/usr/bin/env bash
# Compileaza si ruleaza testele motorului (fara biblioteci de test). Intai ./build.sh, apoi:
#   ./test.sh
set -euo pipefail
cd "$(dirname "$0")"
# Java 17 sau mai nou: din JAVA_HOME daca e setat, altfel java/javac/jar din PATH
JB="${JAVA_HOME:+$JAVA_HOME/bin/}"
SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac
mkdir -p out/test
"${JB}javac" --release 17 -encoding UTF-8 -cp out/main -d out/test $(find src/test/java -name '*.java')
# ./test.sh Stage4Test   ruleaza doar o clasa
status=0
for class in ${@:-GoalRulesTest Stage4Test}; do
  echo "== $class"
  "${JB}java" -cp "out/main${SEP}out/test" "match2d.engine.$class" || status=1
done
exit $status
