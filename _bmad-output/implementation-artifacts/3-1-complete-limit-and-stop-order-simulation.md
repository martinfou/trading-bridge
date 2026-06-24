---
baseline_commit: 99871fd33ee25c911d61dd86a301921391114906
---
# Story 3.1: Complete Limit And Stop Order Simulation

Status: ready-for-dev

## Story

En tant que développeur,
Je souhaite compléter et affiner la simulation des ordres LIMIT et STOP dans le moteur de backtesting (`BacktestEngine.java`),
Afin de garantir des résultats de backtest de haute fidélité conformes aux comportements réels (OANDA v20 / IBKR).

## Acceptance Criteria

1. **AC1 — Gap Fills pour les ordres LIMIT :**
   - Si un ordre d'achat LIMIT (BUY LIMIT) est placé à un prix limite `L`, et que la bougie suivante ouvre avec un gap baissier à un cours d'ouverture `O` (tel que `O <= L`), l'ordre doit être exécuté au cours d'ouverture `O` (car le gap offre un meilleur prix d'achat au trader).
   - Réciproquement, pour un ordre de vente LIMIT (SELL LIMIT) placé à un prix `L`, si la bougie suivante ouvre avec un gap haussier à un cours d'ouverture `O` (tel que `O >= L`), l'ordre doit être exécuté au cours d'ouverture `O`.
   - Actuellement, le moteur remplit toujours les ordres LIMIT exactement au prix limite `L`, ce qui ignore l'effet positif des gaps.

2. **AC2 — Gap Fills pour les ordres STOP :**
   - Les ordres d'achat STOP (BUY STOP) franchis à la hausse par l'ouverture d'une bougie (gap haussier) doivent être exécutés au cours d'ouverture (exécution défavorable à cause du gap).
   - Les ordres de vente STOP (SELL STOP) franchis à la baisse par l'ouverture d'une bougie (gap baissier) doivent être exécutés au cours d'ouverture.
   - (Note: Ce comportement est déjà en partie géré pour STOP dans `BacktestEngine.java` via `Math.max(bar.open(), order.price())`, mais doit être validé de manière exhaustive avec des tests unitaires dédiés).

3. **AC3 — Double barrière sur la même bougie (SL & TP) :**
   - Si une position ouverte touche à la fois sa barrière de Stop Loss (SL) et sa barrière de Take Profit (TP) au cours de la même bougie (c'est-à-dire `bar.low() <= SL` et `bar.high() >= TP`), le moteur de backtest doit par conservatisme financier (pessimisme comptable) considérer que le **Stop Loss** a été touché en premier.
   - La position doit alors être clôturée au prix du Stop Loss (et non au Take Profit).

4. **AC4 — Non-régression :**
   - Tous les tests de la suite `trading-backtest` et les tests d'intégration globaux doivent compiler et passer avec succès.

## Tasks / Subtasks

- [ ] Task 1: Core Implementation (Gap Fills pour LIMIT et Double Barrière SL/TP)
  - [ ] Modifier la logique d'exécution des ordres LIMIT dans `BacktestEngine.processOrders(Bar bar)` pour supporter les gap fills favorables.
  - [ ] Modifier la méthode `checkStopLossesTakeProfits(Bar bar)` dans `BacktestEngine` pour gérer le cas où le SL et le TP sont tous les deux franchis sur la même bougie, en appliquant par défaut la clôture au Stop Loss.
- [ ] Task 2: Testing & Verification
  - [ ] Écrire des cas de test unitaires dans `BacktestEngineContractTest.java` pour valider le gap fill sur BUY LIMIT et SELL LIMIT.
  - [ ] Écrire un cas de test unitaire dans `BacktestEngineContractTest.java` pour valider la double barrière (SL & TP simultanés sur la même bougie) et confirmer la clôture pessimiste au prix du SL.
  - [ ] Lancer la suite de tests complète du reactor Maven pour s'assurer de l'absence de régression.

## Dev Notes

- **Fichier à modifier :**
  - [BacktestEngine.java](file:///Volumes/T7/src/trading-bridge/trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java)
- **Fichier de test à modifier :**
  - [BacktestEngineContractTest.java](file:///Volumes/T7/src/trading-bridge/trading-backtest/src/test/java/com/martinfou/trading/backtest/BacktestEngineContractTest.java)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash

### Debug Log References

*(Sera rempli lors de l'exécution)*

### Completion Notes List

*(Sera rempli lors de l'exécution)*

### File List

*(Sera rempli lors de l'exécution)*

## Change Log

- 2026-06-21: Story réinitialisée avec les spécifications correctes de simulation d'ordres LIMIT/STOP et de double barrière SL/TP.
