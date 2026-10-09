#!/bin/bash
# RunFxCrossAlign — jeudi 8 octobre 2026 (52e résultat, rotation = INTERMARKET / CROSS-ASSET).
# Le « plancher de bruit » de 13.9 bp du 50e est-il un fait de marché ou un artefact d'horodatage ?
# Mètre étalon : le SEUL cross réellement coté du panel (GBP_JPY). Aucun téléchargement requis.
#
# ⚠️ INFRA : ~/.m2 ne contient plus ni ta4j ni les SNAPSHOT frères ⇒ pas de Maven, pas de moteur.
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
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxCrossAlign.java || exit 1

echo "=== RunFxCrossAlign | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
if [ "${1:---all}" = "--all" ]; then
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxCrossAlign "$@" 2>/tmp/crossalign-stderr.log | tee reports/2026-10-08-cross-align.txt
else
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxCrossAlign "$@" 2>/tmp/crossalign-stderr.log | tee "/tmp/crossalign-smoke-$1.txt"
fi
