#!/bin/bash
# RunRefugeCrossCostFade — lundi 12 octobre 2026 (55e résultat, IDÉE NOUVELLE).
# Le fade du vendredi sur les crosses REFUGE en MOTEUR : coût réellement mesuré (spread coté)
# + carry réel par année. Compile contre le moteur de master (trading-bridge) sans y écrire :
# les classes du runner vont dans un répertoire de scratch.
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
SRC=/home/martinfou/projects/tb-research
RUN=/home/martinfou/projects/trading-bridge
OUT="$HOME/.hermes/cache/scratch/refuge-cross-classes"
M=$HOME/.m2/repository

mkdir -p "$OUT"
cd "$RUN" || exit 1

CP="$OUT"
for mod in trading-core trading-data trading-strategies trading-backtest trading-examples; do
  CP="$CP:$RUN/$mod/target/classes"
done
for j in $(find "$M/com/fasterxml/jackson" -name "*.jar" -not -name "*sources*" -not -name "*javadoc*" 2>/dev/null); do CP="$CP:$j"; done
for j in $(find "$M/org/slf4j" -name "*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$j"; done
for j in $(find "$M/org/tukaani" -name "*.jar" 2>/dev/null); do CP="$CP:$j"; done
for j in $(find "$M/com/github" -name "*.jar" -not -name "*sources*" 2>/dev/null | head -20); do CP="$CP:$j"; done

javac -nowarn -cp "$CP" -d "$OUT" \
  "$SRC/trading-examples/src/main/java/com/martinfou/trading/examples/RunRefugeCrossCostFade.java" || exit 1

MODE="${1:---all}"
echo "=== MODE: $MODE | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
exec java -Xss64m -cp "$CP" com.martinfou.trading.examples.RunRefugeCrossCostFade "$MODE"
