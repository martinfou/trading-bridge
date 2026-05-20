FROM eclipse-temurin:21-jdk AS build
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
RUN mvn dependency:go-offline -q

COPY . .
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/trading-strategies/target/*.jar app.jar
COPY --from=build /app/trading-strategies/target/dependency-jars/ /app/libs/
COPY scripts/run-live.sh /app/run-live.sh

EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8083/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
