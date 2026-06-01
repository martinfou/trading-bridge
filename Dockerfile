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
COPY trading-tui/pom.xml trading-tui/
RUN mvn dependency:go-offline -q

COPY . .
RUN mvn install -Dmaven.test.skip=true -q && \
    mvn dependency:copy-dependencies -DoutputDirectory=/app/libs -q

FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy all module classes (classpath)
COPY --from=build /app/trading-core/target/classes /app/classes/trading-core
COPY --from=build /app/trading-data/target/classes /app/classes/trading-data
COPY --from=build /app/trading-strategies/target/classes /app/classes/trading-strategies
COPY --from=build /app/trading-broker/target/classes /app/classes/trading-broker
COPY --from=build /app/trading-parser/target/classes /app/classes/trading-parser

# Copy all dependency JARs
COPY --from=build /app/libs/ /app/libs/

COPY scripts/docker-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Strategy config (backtest-derived risk params)
COPY config/ /app/config/

ENV CLASSPATH="/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/libs/*"
ENTRYPOINT ["/app/entrypoint.sh"]
