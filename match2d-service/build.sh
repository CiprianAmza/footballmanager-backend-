#!/usr/bin/env bash
# Compileaza motorul si serviciul (Java 17, fara dependente) si face out/match2d.jar.
#   ./build.sh            din Git Bash
#   ./build.sh serve      si copiaza arhiva pentru serviciu (out/serve), daca serviciul e oprit
set -euo pipefail
cd "$(dirname "$0")"
# Java 17 sau mai nou: din JAVA_HOME daca e setat, altfel java/javac/jar din PATH
JB="${JAVA_HOME:+$JAVA_HOME/bin/}"
rm -rf out/main out/test
mkdir -p out/main out/test
"${JB}javac" --release 17 -encoding UTF-8 -Xlint:all -d out/main $(find src/main/java -name '*.java')
if [ -d src/main/resources ]; then cp -r src/main/resources/. out/main/; fi
if [ -d src/test/java ]; then
  "${JB}javac" --release 17 -encoding UTF-8 -cp out/main -d out/test $(find src/test/java -name '*.java')
fi
if [ -f out/main/match2d/service/Main.class ]; then
  # arhiva se face alaturi si abia apoi ia locul celei vechi: Windows nu lasa inlocuita o arhiva
  # din care ruleaza un proces (serviciul pornit pe 8791)
  "${JB}jar" --create --file out/match2d.new.jar --main-class match2d.service.Main -C out/main .
  if mv -f out/match2d.new.jar out/match2d.jar 2>/dev/null; then
    echo "gata: out/match2d.jar"
    # serviciul ruleaza dintr-o copie (out/serve), ca motorul sa poata fi recompilat si masurat cat
    # e pornit; copia se inlocuieste doar la cerere si doar cu serviciul oprit (Windows lasa
    # arhiva scrisa peste un proces pornit, care apoi incarca clase amestecate)
    if [ "${1:-}" = "serve" ]; then
      PORT="${MATCH2D_PORT:-8791}"
      if curl -s -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
        echo "EROARE: serviciul raspunde pe portul $PORT. Opreste-l, apoi ruleaza din nou: ./build.sh serve" >&2
        exit 1
      fi
      mkdir -p out/serve
      cp -f out/match2d.jar out/serve/match2d.jar
      echo "      out/serve/match2d.jar: serviciul porneste cu versiunea noua"
    fi
  else
    echo "EROARE: out/match2d.jar e folosita de un proces Java pornit (serviciul sau o masuratoare)." >&2
    echo "        Opreste-l si ruleaza din nou build.sh; arhiva noua a ramas in out/match2d.new.jar." >&2
    exit 1
  fi
else
  echo "gata: out/main"
fi
