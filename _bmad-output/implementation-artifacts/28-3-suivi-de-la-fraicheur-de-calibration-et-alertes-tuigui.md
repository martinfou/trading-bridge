# Story 28.3: Suivi de la fraîcheur de calibration & Alertes (TUI/GUI Multi-Actifs)

Status: backlog

## Story

En tant que trader,
Je veux que le système surveille la fraîcheur de ma calibration en temps réel sur l'ensemble de mes stratégies (Forex, Futures Small/Mid Cap, Actions) et m'alerte lorsqu'une recalibration est nécessaire,
Afin d'éviter de faire tourner des stratégies périmées ou en dérive de performance.

## Acceptance Criteria

1. **Given** une stratégie active en production (live/paper) annotée avec `@CalibrationPolicy(maxAgeDays, maxBarsCount, maxTradesCount)`
2. **When** le `ControlSummaryService` évalue l'état de fraîcheur par rapport aux limites déclarées :
   - Âge en jours depuis le dernier run WFA réussi
   - Nombre de barres consommées depuis la calibration
   - Nombre de trades exécutés depuis la calibration
3. **Then** le système calcule l'état de dégradation selon les seuils mathématiques stricts :
   - 🔋 **FRESH** : $< 70\%$ des seuils `maxAgeDays`, `maxBarsCount`, et `maxTradesCount`.
   - 🔔 **WARNING** : $\ge 70\%$ et $< 100\%$ sur au moins un des seuils.
   - ⚠️ **EXPIRED** : $\ge 100\%$ sur au moins un des seuils.
4. **And** pour toute stratégie ne comportant pas l'annotation `@CalibrationPolicy`, le statut est défini comme `UNMANAGED` (badge gris neutre).
5. **And** ces alertes s'affichent en temps réel dans :
   - Le tableau de bord principal Desktop (`StrategyCard.vue` avec badge coloré et tooltip d'explication).
   - L'interface console TUI (`TradingTuiMain` sous la colonne "Calibration").

## Tasks / Subtasks

- [ ] **Task 1: Évaluation de fraîcheur dans `ControlSummaryService` (`trading-runtime`)** (AC: 1, 2, 3, 4)
  - [ ] Calculer les ratios et déterminer l'état (`FRESH`, `WARNING`, `EXPIRED`, `UNMANAGED`).
- [ ] **Task 2: Exposition DTO et TUI (`trading-tui`)** (AC: 5)
  - [ ] Enrichir le DTO `ControlSummary` avec le statut de calibration.
- [ ] **Task 3: Affichage Desktop GUI (`desktop/`)** (AC: 5)
  - [ ] Badge de fraîcheur sur les cartes de stratégie dans `LiveRoom.vue` et `DashboardView.vue`.
- [ ] **Task 4: Tests unitaires** (AC: 1-5)
  - [ ] `CalibrationFreshnessEvaluatorTest` testant tous les cas de seuils et stratégies non annotées.
