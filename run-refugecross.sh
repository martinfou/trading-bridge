#!/bin/bash
# RunFxRefugeCrossFade — jeudi 8 octobre 2026 (52e résultat, rotation = INTERMARKET / CROSS-ASSET).
# Les crosses REFUGES du 50e sur COTATIONS RÉELLES (GBP_CHF, EUR_CHF, AUD_CHF Dukascopy H1 2006-2026).
#
# ⚠️ INFRA : ~/.m2 ne contient plus ni ta4j ni les SNAPSHOT frères ⇒ pas de Maven, pas de moteur.
#    Classpath minimal : trading-core + trading-data + trading-examples (+ jackson/slf4j/xz).
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/tb-research
M=$HOME/.m2/repository
cd "$REPO" || exit 1

CP="trading-core/target/classes:trading-data/target/classes:trading-examples/target/classes"
for j in \
  "$M/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar" \
  "$M/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar" \
  "$M/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar" \
  "$M/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.17.2/jackson-datatype-jsr310-2.17.2.jar" \
  "$M/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar" \
  "$M/org/slf4j/slf4j-simple/2.0.16/slf4j-simple-2.0.16.jar" \
  "$M/org/tukaani/xz/1.9/xz-1.9.jar" ; do
  [ -f "$j" ] && CP="$CP:$j"
done

javac -nowarn -cp "$CP" -d trading-examples/target/classes \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxRefugeCrossFade.java || exit 1

echo "=== RunFxRefugeCrossFade | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
# ⚠️ le rapport daté n'est écrit QUE pour le run complet (leçon du 7 oct) : un mode partiel écraserait le rapport.
if [ "${1:---all}" = "--all" ]; then
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxRefugeCrossFade "$@" 2>/tmp/refugecross-stderr.log | tee reports/2026-10-08-refuge-cross-fade.txt
else
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxRefugeCrossFade "$@" 2>/tmp/refugecross-stderr.log | tee "/tmp/refugecross-smoke-$1.txt"
fi
