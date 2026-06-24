# Story 24.2: CLI Launcher & Export JSON

Status: done

## Story

As a trader,
I want to launch a WFA execution in command line and export the complete report in JSON,
so that I can automate my calibrations or inspect the raw results.

## Acceptance Criteria

1. **Given** a CSV/bar historical data file
2. **When** I run the CLI execution command via the `trading-examples` module (ex: `mvn exec:java -pl trading-examples ... --wfa`) with the symbol, dates, and fold configuration
3. **Then** the system executes the WFA engine and displays a console summary of the results (global Sharpe, average IS/OOS Sharpe, efficiency ratio WFE, total trades)
4. **And** the complete report in JSON format containing the full detail of each fold and all OOS trades is saved in `data/reports/wfa/wfa-{id}.json`
5. **And** the subdirectory `data/reports/wfa/` is correctly ignored in the `.gitignore` file.

## Tasks / Subtasks

- [x] **Task 1: Mettre à jour `.gitignore`** (AC: 5)
  - [x] Ajouter la règle d'exclusion pour le dossier de rapports Walk-Forward : `data/reports/wfa/`
- [x] **Task 2: Implémenter le parsing CLI pour `--wfa`** (AC: 2)
  - [x] Mettre à jour `RunBacktest.java` dans `trading-examples` pour intercepter l'argument `--wfa`.
  - [x] Parser la commande : `--wfa <strategyId> <symbol> <yearSpec> --config <config-file.json>`.
- [x] **Task 3: Charger les barres historiques et la configuration JSON** (AC: 1, 2)
  - [x] Charger la configuration du WFA (`WfaConfig`) depuis le fichier JSON spécifié par `--config` à l'aide de Jackson.
  - [x] Charger les barres historiques via `HistoricalDataLoader` en utilisant les arguments `<symbol>` et `<yearSpec>`.
- [x] **Task 4: Exécuter `WfaEngine` et exporter le JSON** (AC: 3, 4)
  - [x] Instancier `WfaEngine` avec un `Supplier<Strategy>` qui crée la stratégie ciblée via `StrategyCatalog.create(strategyId, symbol)`.
  - [x] S'assurer que le helper de réflexion dans `WfaEngine` déballe le `FixedQuantityStrategy` pour injecter directement les paramètres sur la stratégie sous-jacente.
  - [x] Écrire le rapport complet `WfaReport` au format JSON dans `data/reports/wfa/wfa-{id}.json`. Créer le dossier s'il n'existe pas.
- [x] **Task 5: Afficher le résumé dans la console** (AC: 3)
  - [x] Afficher les métriques globales après l'exécution : ID du run, Sharpe global OOS, Sharpe IS moyen, Sharpe OOS moyen, WFE (efficacité), et nombre total de transactions OOS.

## Dev Notes

### Architecture & Modularity Guardrails
- **Unpacking FixedQuantityStrategy** : Le `WfaEngine` doit utiliser la réflexion pour accéder au champ privé `delegate` de la classe `FixedQuantityStrategy` afin d'appliquer correctement les paramètres aux classes de stratégies concrètes.
- **Répertoires locaux** : Le dossier `data/reports/wfa/` doit être créé s'il est manquant au moment de l'écriture du fichier.

### Source tree components to touch
- `trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java` (Modifier)
- `.gitignore` (Modifier)
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/wfa/WfaEngine.java` (Vérifier et ajuster le déballage de `FixedQuantityStrategy` si nécessaire)

### Testing standards summary
- Lancer une exécution d'essai via :
  ```bash
  mvn exec:java -pl trading-examples \
    -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
    -Dexec.args="--wfa SmaCrossover EUR_USD 2012 --config wfa-config.json"
  ```

### References
- Spécification générale WFA : [epics-and-stories-walk-forward-optimization.md](file:///_bmad-output/planning-artifacts/epics-and-stories-walk-forward-optimization.md)
- PRD de robustesse WFA : [prd.md](file:///_bmad-output/planning-artifacts/prds/prd-Trading%20Bridge-2026-06-15/prd.md)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash (Medium) (Antigravity Core)

### Debug Log References

- None

### Completion Notes List

- Implemented WFA CLI command processing in RunBacktest.java with argument parsing and validation.
- Added WFA config file loading from JSON utilizing Jackson.
- Integrated WFA engine execution and console summary output.
- Saved detailed WFA JSON report to data/reports/wfa/wfa-{id}.json.
- Added dynamic FixedQuantityStrategy unwrapping via reflection in WfaEngine.java's parameter helper.
- Added test case to verify FixedQuantityStrategy unwrapping in WfaEngineTest.java.
- Added data/reports/wfa/ to .gitignore.

### File List

- `.gitignore`
- `trading-examples/pom.xml`
- `trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/wfa/WfaEngine.java`
- `trading-backtest/src/test/java/com/martinfou/trading/backtest/wfa/WfaEngineTest.java`

### Review Findings

- [x] [Review][Patch] Fix infinite loop in anchored fold generation [WfaEngine.java:319]
- [x] [Review][Patch] Fix reflection primitive setters exception on boxed wrapper fields [WfaEngine.java:465]
- [x] [Review][Patch] Use Java 21 Virtual Threads and Semaphore for concurrency resource regulation [WfaEngine.java:49]
- [x] [Review][Patch] Handle exception in parallel grid search combination tasks gracefully instead of crashing entire run [WfaEngine.java:359]
- [x] [Review][Patch] Guard against division-by-zero on drawdown peak and bankruptcy ratio calculations [WfaEngine.java:208]
- [x] [Review][Patch] Limit combinations search space and validate step is positive to avoid infinite loops and OOM [WfaEngine.java:435]
- [x] [Review][Patch] Check report directory creation status in CLI launcher [RunBacktest.java:451]
- [x] [Review][Defer] Boundary Trades Purging is Ineffective on OOS Performance Metrics [WfaEngine.java:200] — deferred, pre-existing
