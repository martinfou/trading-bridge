#!/bin/bash
# RunFxCurrencyLegFade — mardi 6 octobre 2026 (50e résultat, rotation = VARIATION d'une stratégie existante).
# Le fade du vendredi (37e/43e/47e) est-il un effet GBP ou un risk-off générique ?
# Décomposition des 8 paires en JAMBES de devises (moindres carrés, USD = numéraire) + crosses GBP synthétiques.
#
# ⚠️ INFRA (6 oct 2026) : ~/.m2 ne contient PLUS ni ta4j ni les SNAPSHOT frères ⇒
#    `mvn dependency:build-classpath` échoue et /tmp/bt-cp.txt (purgé) ne peut plus être régénéré.
#    Ce harnais ne dépend QUE de trading-core + trading-data (+ jackson/slf4j/xz) : classpath minimal,
#    pas de Maven, pas de ta4j, pas de moteur de backtest. Run en quelques secondes.
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
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxCurrencyLegFade.java || exit 1

echo "=== RunFxCurrencyLegFade | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxCurrencyLegFade "$@" 2>/tmp/currencyleg-stderr.log | tee reports/2026-10-06-currency-leg-fade.txt
