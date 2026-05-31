# Deferred Work

## Deferred from: code review of 2-10-utc-timezone-migration (2026-05-18)

- **Horloge UTC injectable** — `TimeConventions.clock()` statique ; tests déterministes sur `Order` / `AutoTrader` reportés hors scope minimal.
- **Lignes CSV ignorées silencieusement** — Comportement pré-existant dans `DataLoader` ; pas introduit par la migration UTC.
- **Tests d’intégration OandaPriceClient** — Smoke test seulement ; tests unitaires parsing candle optionnels.
- **SmaCrossoverStrategy** — Aucun champ temporel ; pas de migration nécessaire pour AC4.
- **BacktestEngine diff minimal** — Import cleanup seulement ; OK avec domaine `Instant`.
- **Preuve AC7 build** — `mvn clean install` à confirmer en local par le développeur.
- **Zone Germany PMI (Berlin vs London)** — Décision 4-C : vérifier l’heure officielle sur le calendrier source avant de changer `Europe/Berlin`.

## Deferred from: code review of stories 12.3–12.6 (2026-05-23)

- **Couplage `trading-backtest` → `trading-strategies`** — Résolu story 13.1 (`RunContexts` dans `trading-examples`).
- **Paper stub sémantiquement identique au backtest** — By design story 12.6 ; label `Paper mode (stub)` CLI + doc ; live paper = Epic 4.
- **SLF4J sur stdout avec `--json`** — Pollue JSONL ; mitiger via log level WARN en mode json ou control plane (Epic 13).
- `**RunPropBacktest --all` + `--json`/`--paper**` — Non supporté ; suite prop reste mode humain uniquement.
- **Tests CLI d'intégration RunBacktest** — Pas de test end-to-end exec:java ; couverture unitaire suffisante pour MVP.
- **Validation stricte args positionnels (capital vs chemin)** — Heuristique actuelle ; amélioration TUI/control plane.

## Deferred from: code review of 12-10-backtest-engine-trust (2026-05-30)

- **Golden test skip sans `data/historical/` en CI** — By design (AC3 story 12.1) ; CI sans données locales skip, pas fail.
- **`stopSlippagePct` / take-profit non couverts par contract tests** — Hors AC2 explicite ; red team gap résiduel acceptable pour MVP trust.
- **`BASELINE_COMMIT` hash `ec6dc72` vs re-vérif 2026-05-30** — Display-only ; mettre à jour au prochain commit intentionnel de baseline.