# Guide de Développement — Backend Java (`trading-bridge-java`)

**Date de mise à jour :** 2026-11-05  
**Portée :** Les 11 modules Maven du moteur Java 21 (`trading-core` à `trading-tui`)

---

## 1. Conventions du Code Java 21 & Règles du DAG

1. **Racine des packages** : `com.martinfou.trading.<module>` (ex. `com.martinfou.trading.core`, `com.martinfou.trading.backtest`, `com.martinfou.trading.runtime`).
2. **Respect absolu du DAG des modules** :
   * `trading-core` ne dépend d'aucun autre module interne. Ne jamais y ajouter de code HTTP, SQLite ou courtier.
   * `trading-tui` ne dépend d'aucun module interne (client HTTP pur).
   * Toute nouvelle dépendance externe doit être déclarée dans `<dependencyManagement>` du `pom.xml` racine avant d'être référencée sans version dans un module enfant.
   * **Aucun Lombok, aucun Spring Framework** dans le projet.
3. **Style du modèle de domaine** :
   * Accesseurs directs (`bar.close()`, `order.symbol()`) et non getters JavaBeans (`getClose()`).
   * Utiliser `java.time.Instant` (UTC) partout en interne. Ne jamais utiliser `LocalDateTime.now()` dans la logique de trading ou de backtest.
4. **Contrat `Strategy` & Files d'Ordres** :
   * Implémenter `name()`, `onBar(Bar)` (ou `onBar(Bar, Position)`), `onTick(...)`, `getPendingOrders()`, `reset()`.
   * `getPendingOrders()` doit retourner une copie et vider la file interne (utiliser `StrategyOrderQueues.drainPending()` dans `trading-strategies`).

---

## 2. Commandes Maven Essentielles

```bash
# Compiler et exécuter tous les tests sur les 11 modules
./mvnw clean install

# Tester un seul module rapidement
./mvnw test -pl trading-core
./mvnw test -pl trading-backtest
./mvnw test -pl trading-parser
./mvnw test -pl trading-runtime

# Vérifier le style (Google Checkstyle) et l'analyse statique (SpotBugs)
./mvnw checkstyle:check spotbugs:check

# Construire le JAR Shaded autonome du Control Plane
./mvnw package -pl trading-runtime -am -DskipTests
```

---

## 3. Ajouter une Nouvelle Stratégie ou un Endpoint API

### 3.1 Ajouter une Stratégie au Catalogue (`trading-strategies`)
1. Créez votre classe dans le sous-package correspondant (`prop/`, `lt/`, `sqimported/`, `generated/`).
2. Utilisez les indicateurs partagés de `com.martinfou.trading.core.indicators.*` (ne dupliquez pas SMA/EMA/RSI/ATR).
3. Enregistrez la stratégie et ses métadonnées (`AssetClass`, `TradingStyle`, `recommendedSymbols`, `timeframeSuitability`) dans le sous-catalogue approprié (`PropStrategyCatalog`, `LongTermStrategyCatalog`, `SqImportedStrategyCatalog`, etc.).
4. Vérifiez sa présence via `GET http://localhost:8080/api/strategies` et lancez un backtest réaliste (`RealCostModel`).

### 3.2 Ajouter un Endpoint au Control Plane (`trading-runtime`)
1. Déclarez la route dans `ControlPlaneServer.java` (ou `BacktestController.java`).
2. Si la route mute l'état des runs ou des stratégies, assurez-vous qu'elle se situe sous `/api/runs*` ou `/api/strategies*` afin d'hériter automatiquement du verrou de réconciliation (`503 Service Unavailable` tant que `!runManager.isReconciliationComplete()`).
3. Documentez le contrat dans `docs/api-contracts-trading-bridge-java.md` et ajoutez l'interface TypeScript correspondante dans `desktop/src/types/control-plane.ts`.

