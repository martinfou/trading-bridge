# Story 15.9: Exception de promotion pour les stratégies HARNESS

Status: done

## Story

As a Martin,
I want the promotion gates to PAPER mode to automatically pass for strategies belonging to the HARNESS family,
so that I can run them in paper trading to validate system execution even if they are not profitable.

## Acceptance Criteria

1. **Bypass des verrous pour HARNESS** : Si la stratégie appartient à la famille `HARNESS` (détecté via `StrategyCatalog.family(strategyId) == StrategyCatalog.Family.HARNESS`) et est promue vers le mode `PAPER` (REST API / CLI), les verrous de performance suivants doivent automatiquement passer (`GateCheckResult.passed = true`) :
   - `minTrades`
   - `maxDrawdown`
   - `minReturn`
   - `goldenBaseline`
   - `validationModule`
2. **Auditabilité (Messages clairs)** : Les résultats de ces verrous bypassés doivent porter un message explicite indiquant le bypass (ex: `"[Bypass HARNESS] ..."`) dans la liste des `GateCheckResult` retournée et persistée.
3. **Obligation d'un backtest existant** : Un backtest complété avec succès (présence d'un `runId` valide et statut `COMPLETED`) reste requis pour la promotion PAPER. Si aucun backtest n'existe ou s'il n'est pas complété, la promotion doit échouer avec `backtest_exists` = false.
4. **Maintien des contrôles système courtier** : Les contrôles d'identifiants de courtier (OANDA/IBKR) et de validité de compte associés au label d'exécution demandé (ex. PAPER_OANDA) doivent être exécutés et appliqués normalement.
5. **Régression non-HARNESS évitée** : Les stratégies appartenant à d'autres familles (ex. `PROP` ou `SQ_IMPORTED`) doivent continuer à être évaluées normalement selon les règles et seuils de performance habituels.
6. **Couverture de tests unitaires** : Les tests unitaires dans `PromoteServiceTest` doivent valider ces comportements (succès de la promotion d'une stratégie HARNESS avec des métriques hors-normes et présence des messages de bypass).

## Tasks / Subtasks

- [x] Modifier la logique de promotion dans `PromoteService.java`
  - [x] Détecter la famille de la stratégie à l'aide de `StrategyCatalog.family(strategyId)`
  - [x] Adapter la logique sous `if (targetMode == RunMode.PAPER)` : si la stratégie est de la famille `HARNESS` et qu'un backtest est présent, forcer les résultats des verrous de performance à `true` avec des messages de bypass.
- [x] Ajouter des tests unitaires dans `PromoteServiceTest.java`
  - [x] Ajouter un test `promoteToPaper_harnessStrategy_bypassesMetricGates` utilisant une stratégie HARNESS pour prouver le bypass et la conservation des autres règles.
- [x] Valider l'exécution des tests du module `trading-runtime` via Maven

## Dev Notes

- **Fichier à modifier** : [PromoteService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java#L123-L159)
- **Fichier de test à modifier** : [PromoteServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java)
- Pour forcer le succès des gates dans `PromoteService.java`, on peut soit modifier directement la façon dont les checks sont construits en y insérant des `GateCheckResult` simulés pour la famille HARNESS, soit modifier la logique d'appel de `PromoteGates` pour passer des valeurs bypassées.

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash (High)

### Debug Log References

N/A

### Completion Notes List

- Added check using `StrategyCatalog.family(strategyId)` in `PromoteService.java`.
- Bypassed metric/validation gates for `Family.HARNESS` in `PAPER` mode promotion.
- Maintained OANDA/IBKR credentials and account validation.
- Added comprehensive unit test in `PromoteServiceTest.java`.
- Verified build and tests with `mvn test -pl trading-runtime` (all passed).

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java`

### References

- [PRD.md: FR7](file:///_bmad-output/planning-artifacts/prds/prd-Trading%20Bridge-2026-05-24/prd.md#L187-L194)
- [addendum.md: Exception HARNESS](file:///_bmad-output/planning-artifacts/prds/prd-Trading%20Bridge-2026-05-24/addendum.md#L76)
- [.decision-log.md: Decision 2026-06-13](file:///_bmad-output/planning-artifacts/prds/prd-Trading%20Bridge-2026-05-24/.decision-log.md#L25)
