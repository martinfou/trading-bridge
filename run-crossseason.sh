#!/bin/bash
# RunFxCrossSeasonal — mercredi 7 octobre 2026 (51e résultat, rotation = PATTERN SAISONNIER).
# La saisonnalité mensuelle FX survit-elle au RETRAIT DU DOLLAR ? (12 crosses + 7 jambes + facteur 50e)
#
# ⚠️ INFRA (6 oct 2026) : ~/.m2 ne contient PLUS ni ta4j ni les SNAPSHOT frères ⇒
#    `mvn dependency:build-classpath` échoue et /tmp/bt-cp.txt (purgé) ne peut plus être régénéré.
#    Ce harnais ne dépend QUE de trading-core + trading-data (+ jackson/slf4j/xz) : pas de Maven,
#    pas de ta4j, pas de moteur de backtest. Run en quelques secondes.
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
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxCrossSeasonal.java || exit 1

echo "=== RunFxCrossSeasonal | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
# ⚠️ le rapport daté n'est écrit QUE pour le run complet : un mode partiel (--p0/--matrix/…)
#    écraserait le rapport du jour avec une sortie tronquée (leçon du 7 oct sur run-currencyleg.sh).
if [ "${1:---all}" = "--all" ]; then
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxCrossSeasonal "$@" 2>/tmp/crossseason-stderr.log | tee reports/2026-10-07-cross-seasonal.txt
else
  timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxCrossSeasonal "$@" 2>/tmp/crossseason-stderr.log | tee "/tmp/crossseason-smoke-$1.txt"
fi
