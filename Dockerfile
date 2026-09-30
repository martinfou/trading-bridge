FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY trading-core/pom.xml trading-core/
COPY trading-backtest/pom.xml trading-backtest/
COPY trading-genetics/pom.xml trading-genetics/
COPY trading-strategies/pom.xml trading-strategies/
COPY trading-data/pom.xml trading-data/
COPY trading-broker/pom.xml trading-broker/
COPY trading-parser/pom.xml trading-parser/
COPY trading-examples/pom.xml trading-examples/
COPY trading-runtime/pom.xml trading-runtime/
COPY trading-intelligence/pom.xml trading-intelligence/
COPY trading-tui/pom.xml trading-tui/
RUN mvn dependency:go-offline -q

COPY . .
RUN mvn install -Dmaven.test.skip=true -q -pl trading-core,trading-data,trading-broker,trading-parser,trading-strategies,trading-backtest,trading-examples,trading-intelligence,trading-runtime -am && \
    mvn dependency:copy-dependencies -DoutputDirectory=/app/libs -q -pl trading-strategies,trading-backtest,trading-examples -am

FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy all module classes (classpath)
COPY --from=build /app/trading-core/target/classes /app/classes/trading-core
COPY --from=build /app/trading-data/target/classes /app/classes/trading-data
COPY --from=build /app/trading-strategies/target/classes /app/classes/trading-strategies
COPY --from=build /app/trading-broker/target/classes /app/classes/trading-broker
COPY --from=build /app/trading-parser/target/classes /app/classes/trading-parser
COPY --from=build /app/trading-backtest/target/classes /app/classes/trading-backtest
COPY --from=build /app/trading-examples/target/classes /app/classes/trading-examples
COPY --from=build /app/trading-intelligence/target/classes /app/classes/trading-intelligence
COPY --from=build /app/trading-runtime/target/classes /app/classes/trading-runtime

# Copy all dependency JARs
COPY --from=build /app/libs/ /app/libs/

COPY scripts/docker-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Strategy config (backtest-derived risk params)
COPY config/ /app/config/

ENV CLASSPATH="/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/libs/*"

# Liveness — « le conteneur tourne » ne veut pas dire « la stratégie trade ».
# Un token OANDA révoqué laissait les conteneurs « Up » avec des stratégies
# mortes pendant 69 jours (10 juil → 21 sept 2026). Cette sonde exige que le
# processus LiveStrategyRunner existe réellement : sinon `docker ps` affiche
# « Up (unhealthy) » au lieu de « Up ».
# Le motif est coupé en deux (« LiveStrategy » + « Runner ») parce qu'une
# sonde qui cherche un littéral se retrouve dans sa propre ligne de commande
# et se déclare toujours saine — piège reproduit puis corrigé le 21 sept 2026.
# Outils utilisés (tr/grep) vérifiés présents dans l'image finale.
HEALTHCHECK --interval=60s --timeout=10s --start-period=120s --retries=3 \
  CMD n="LiveStrategy"; n="${n}Runner"; for p in /proc/[0-9]*; do tr '\0' ' ' < "$p/cmdline" 2>/dev/null | grep -q "$n" && exit 0; done; exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
