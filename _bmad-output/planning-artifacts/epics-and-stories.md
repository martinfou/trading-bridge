# Epic 27 — Portfolio de Stratégies Long Terme

**Objectif :** Concevoir, implémenter et valider un portefeuille de 10+ stratégies de trading long terme (horizon 15+ ans, H1, 4 paires majeures) avec position sizing standardisé, walk-forward validation, et documentation reproductible.

**Statut :** `done`  
**Branche :** `feature/lt-strategies`

---

## User Stories (valeur métier)

| # | As a... | I want to... | So that... | Statut |
|---|---------|-------------|------------|--------|
| US-1 | Trader long terme | Backtester des stratégies sur 15+ ans de données H1 | Je valide leur robustesse sur plusieurs cycles de marché | ✅ |
| US-2 | Trader long terme | Voir les résultats en walk-forward (IS/OOS/OOS2) | Je distingue le vrai signal du bruit | ✅ |
| US-3 | Trader long terme | Avoir un position sizing standardisé basé sur le risque | Mon exposition est cohérente entre toutes les stratégies | ✅ |
| US-4 | Trader long terme | Utiliser le TUI ou la GUI pour lancer les backtests | Je peux tester rapidement sans ligne de commande | ✅ |
| US-5 | Trader long terme | Avoir un playbook pour créer de nouvelles stratégies | Je peux reproduire le processus sans tout réinventer | ✅ |
| US-6 | Développeur | Ajouter une nouvelle stratégie en suivant un template standard | Le code est cohérent et réutilisable | ✅ |

---

## Coding Stories

| # | Story | Effort | Description | Dependencies | Statut |
|---|-------|--------|-------------|--------------|--------|
| **Phase 4.1 — Conception & Infra** |
| 27-1 | Consolidation des branches | M | Migrer 10 branches séparées vers une branche unique `feature/lt-strategies` | Aucune | ✅ |
| 27-2 | Structure package longterm | XS | Créer package `strategies/longterm/` avec template de base | 27-1 | ✅ |
| 27-3 | LongTermStrategyCatalog | S | Créer le catalog + enregistrement dans StrategyCatalog (famille LONG_TERM) | 27-2 | ✅ |
| 27-4 | Frontend: couleur LONG_TERM | XS | Ajouter la couleur orange `#f97316` dans StrategyCard.vue + StrategiesView.vue | 27-3 | ✅ |
| **Phase 4.2 — Stratégies individuelles** |
| 27-5 | LtCrossMomentum | M | SMA(20)/SMA(100) golden cross / death cross | 27-2 | ✅ |
| 27-6 | LtRSIMeanRev | M | RSI(14) mean reversion avec EMA(200) trend filter | 27-2 | ✅ |
| 27-7 | LtRSI3Momentum | M | RSI(3) momentum avec EMA(200) trend filter | 27-2 | ✅ |
| 27-8 | LtRangeBreakout | M | Donchian channel breakout avec trailing SL | 27-2 | ✅ |
| 27-9 | LtVolRegime | M | Volatilité adaptative (trend-follow / mean-reversion selon ATR ratio) | 27-2 | ✅ |
| 27-10 | LtBollingerSqueeze | M | Bollinger Bandwidth squeeze breakout | 27-2 | ✅ |
| 27-11 | LtSqueezeMomentum | M | Bollinger squeeze + RSI momentum confirmation | 27-2 | ✅ |
| 27-12 | LtPullbackEntry | M | Pullback EMA(50) en direction du trend | 27-2 | ✅ |
| 27-13 | LtDoubleMA | M | EMA(50)/SMA(200) golden cross / death cross | 27-2 | ✅ |
| 27-14 | LtEfficiencyRatio | M | Kaufman Efficiency Ratio trend filter | 27-2 | ✅ |
| **Phase 4.3 — Position Sizing** |
| 27-15 | calcRiskPosition dans Indicators | S | Ajouter `Indicators.calcRiskPosition(capital, riskPct, atr, slMult, symbol)` | Aucune | ✅ |
| 27-16 | Standardiser position sizing 10 stratégies | L | Remplacer tous les BASE_UNITS/MIN_POSITION par calcRiskPosition(10_000, 0.01, atr, 2.0, symbol) | 27-15 | ✅ |
| **Phase 4.4 — Validation & Documentation** |
| 27-17 | Walk-forward validation 10 stratégies | XL | Tester chaque stratégie sur 4 paires × 4 périodes (FULL, IS, OOS1, OOS2) | 27-5→27-14 | ✅ |
| 27-18 | Rapports de validation | M | Rédiger rapport + leçons apprises pour chaque stratégie | 27-17 | ✅ |
| 27-19 | Post-mortem échecs | S | Documenter pourquoi LtPullbackEntry et LtBollingerSqueeze ont échoué | 27-17 | ✅ |
| 27-20 | LT Strategy Playbook | M | Rédiger `docs/lt-strategy-playbook.md` — processus reproductible complet | 27-18, 27-19 | ✅ |
| 27-21 | Sauvegarde Joplin | XS | Sauvegarder leçons + playbook dans Joplin (02-Projects/Trading) | 27-20 | ✅ |

---

## Résumé

| Métrique | Valeur |
|----------|--------|
| Stratégies créées | 10 |
| Stratégies qualifiées (PF ≥ 1.05 OOS) | 8 |
| Stratégies échouées (PF < 1.0 OOS) | 2 |
| Branches consolidées | 10 → 1 (`feature/lt-strategies`) |
| Paires testées | EUR/USD, GBP/USD, USD/JPY, AUD/USD |
| Périodes de validation | FULL (2010-2025), IS (2010-2018), OOS1 (2019-2022), OOS2 (2023-2025) |
| Capital de référence | $10 000 |
| Risque par trade | 1% |
| Position sizing | `calcRiskPosition(10_000, 0.01, atr, 2.0, symbol)` |

## Leçons clés

Voir `docs/lt-strategy-playbook.md` section 1.3 pour la liste complète des 9 leçons.

Top 3 :
1. Trend following > Mean reversion sur H1
2. GBP/USD surperforme EUR/USD (1.5-3x PF)
3. Toujours valider en walk-forward (IS/OOS/OOS2)

## Build Order

| Phase | Stories | Effort total |
|-------|---------|-------------|
| 4.1 — Conception & Infra | 27-1 → 27-4 | S |
| 4.2 — Stratégies | 27-5 → 27-14 (10 stratégies en parallèle par batchs de 3) | XL |
| 4.3 — Position Sizing | 27-15 → 27-16 | M |
| 4.4 — Validation & Docs | 27-17 → 27-21 | L |
