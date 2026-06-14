# Story 25.2: Déclaration des records cibles et DTOs partagés

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

En tant que développeur,
Je veux déclarer les Records Java 21 requis pour le schéma cible et les outils d'ingestion,
afin que les modules en aval puissent les consommer sans dépendre du framework LLM (LangChain4j).

## Acceptance Criteria

1. **Given** le module `trading-core`
   **When** je crée le package `com.martinfou.trading.core.agent`
   **Then** il contient les records publics `WeeklyStrategyOutlook`, `TradeTriggerCondition`, `RiskFactors` et les enums `MarketDirection`, `MarketRegime`, `ComfortLevel`.
2. **Given** le module `trading-intelligence`
   **When** je crée le package `com.martinfou.trading.intelligence.agent`
   **Then** il contient les records publics `WeeklyStrategyOutlookRaw`, `SentimentData` et `SeasonalityData`.
3. **Given** l'ensemble des structures de données déclarées
   **When** elles sont compilées
   **Then** elles correspondent exactement aux spécifications définies dans la section §5 du PRD.
   **And** elles compilent avec succès via `mvn clean install` à la racine du monorepo.

## Tasks / Subtasks

- [x] **Task 1: Déclarer les types partagés dans trading-core (AC #1, #3)**
  - [x] Créer l'énumération `MarketDirection` (`BULLISH`, `BEARISH`, `NEUTRAL`) dans [MarketDirection.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/MarketDirection.java)
  - [x] Créer l'énumération `MarketRegime` (`HIGH_VOL_TREND`, `LOW_VOL_CONSOLIDATION`, `MEAN_REVERSION`, `HIGH_RISK_EVENT_LOCK`) dans [MarketRegime.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/MarketRegime.java)
  - [x] Créer l'énumération `ComfortLevel` (`HIGH`, `MEDIUM`, `LOW`) dans [ComfortLevel.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/ComfortLevel.java)
  - [x] Créer le record `RiskFactors` (champs : `macroEventConflict` (boolean), `sentimentDivergence` (boolean), `coreFrictionDetails` (String)) dans [RiskFactors.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/RiskFactors.java)
  - [x] Créer le record `TradeTriggerCondition` (champs : `setupName` (String), `side` (`com.martinfou.trading.core.Order.Side`), `type` (`com.martinfou.trading.core.Order.Type`), `targetedPriceZone` (double), `invalidationPips` (int), `executionContextRules` (`Map<String, String>`)) dans [TradeTriggerCondition.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/TradeTriggerCondition.java)
  - [x] Créer le record `WeeklyStrategyOutlook` (champs : `targetAsset` (String), `bias` (`MarketDirection`), `identifiedRegime` (`MarketRegime`), `comfortLevel` (`ComfortLevel`), `rawSentimentScore` (double), `seasonalityWinRate` (double), `strategyRationale` (String), `setups` (`List<TradeTriggerCondition>`), `riskFactors` (`RiskFactors`), `alphaKillSwitchCondition` (String)) dans [WeeklyStrategyOutlook.java](file:///home/martinfou/dev/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/agent/WeeklyStrategyOutlook.java)
- [x] **Task 2: Déclarer les types dans trading-intelligence (AC #2, #3)**
  - [x] Créer le record `WeeklyStrategyOutlookRaw` dans [WeeklyStrategyOutlookRaw.java](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/WeeklyStrategyOutlookRaw.java) réutilisant les types de `trading-core` (sans le champ `comfortLevel` calculé en Java, champs : `targetAsset` (String), `bias` (`MarketDirection`), `identifiedRegime` (`MarketRegime`), `rawSentimentScore` (double), `seasonalityWinRate` (double), `strategyRationale` (String), `setups` (`List<TradeTriggerCondition>`), `riskFactors` (`RiskFactors`), `alphaKillSwitchCondition` (String))
  - [x] Créer le record `SentimentData` (champs : `asset` (String), `sentimentScore` (double), `retailRatioString` (String), `newsHeadlines` (`List<String>`)) dans [SentimentData.java](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/SentimentData.java)
  - [x] Créer le record `SeasonalityData` (champs : `asset` (String), `weekOfYear` (int), `directionalBias` (`MarketDirection`), `averagePips` (int)) dans [SeasonalityData.java](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/SeasonalityData.java)
- [x] **Task 3: Écriture des tests unitaires et validation (AC #3)**
  - [x] Écrire `WeeklyStrategyOutlookTest.java` dans `trading-core/src/test/java/com/martinfou/trading/core/agent/` pour tester l'instanciation et le bon fonctionnement des getters implicites des records de `trading-core`.
  - [x] Écrire `WeeklyStrategyOutlookRawTest.java` dans `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/` pour tester l'instanciation et le bon fonctionnement des getters implicites des records de `trading-intelligence`.
  - [x] Lancer la compilation globale du projet via `mvn clean install` depuis la racine pour valider l'absence de régression.

## Dev Notes

* **Zéro framework magique (pas de Lombok/Spring) :** Toutes les classes doivent être de purs records Java 21 sans dépendances tierces magiques.
* **Graphe de dépendances acyclique :** Le module `trading-core` est à la base du graphe de dépendance et ne doit absolument pas dépendre de `trading-intelligence`. Les records partagés vont donc dans `trading-core`, et `trading-intelligence` peut les réutiliser directement grâce à sa dépendance Maven existante sur `trading-core`.
* **Réutilisation de types existants :** Réutiliser directement les enums `com.martinfou.trading.core.Order.Side` et `com.martinfou.trading.core.Order.Type` au sein de `TradeTriggerCondition`.

### Project Structure Notes

* Les fichiers de `trading-core` doivent être placés sous : `trading-core/src/main/java/com/martinfou/trading/core/agent/`
* Les fichiers de `trading-intelligence` doivent être placés sous : `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/`

### References

* **Spécifications PRD de l'Agentic Strategist (Records Spec §5)** : [prd.md](file:///home/martinfou/dev/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#5.-structured-data-schema-spec-(target-records)-[req-ag-06])
* **Spécifications des Epics** : [epics.md](file:///home/martinfou/dev/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#story-25.2-declaration-des-records-cibles-et-dtos-partages)

## Dev Agent Record

### Agent Model Used

- Antigravity (Google DeepMind)

### Debug Log References

N/A

### Completion Notes List

- Verified existing classes and tests matching Section 5 and 5.2 of the Agentic Strategist PRD.
- Ran tests in trading-core and trading-intelligence modules (all successfully passed).
- Confirmed acyclic dependency rules.

### File List

- `trading-core/src/main/java/com/martinfou/trading/core/agent/MarketDirection.java`
- `trading-core/src/main/java/com/martinfou/trading/core/agent/MarketRegime.java`
- `trading-core/src/main/java/com/martinfou/trading/core/agent/ComfortLevel.java`
- `trading-core/src/main/java/com/martinfou/trading/core/agent/RiskFactors.java`
- `trading-core/src/main/java/com/martinfou/trading/core/agent/TradeTriggerCondition.java`
- `trading-core/src/main/java/com/martinfou/trading/core/agent/WeeklyStrategyOutlook.java`
- `trading-core/src/test/java/com/martinfou/trading/core/agent/WeeklyStrategyOutlookTest.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/WeeklyStrategyOutlookRaw.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/SentimentData.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/SeasonalityData.java`
- `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/WeeklyStrategyOutlookRawTest.java`
