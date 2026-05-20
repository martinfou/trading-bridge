# Stratégie de Déploiement des Algorithmes de Trading

> Bmad — Document de planification
> Sprint 9 (VPS Live Trading)

## Vue d'ensemble

```
┌────────────────────────────────────────────────────────────────────┐
│                         PIPELINE DE DÉPLOIEMENT                     │
│                                                                     │
│  BACKTEST ──► VALIDATION ──► PAPER ──► SMALL LIVE ──► SCALE        │
│     ↓            ↓             ↓             ↓             ↓        │
│  Genetic     Walk-       OANDA       OANDA        0.01 →    │
│  Engine      Forward     Practice    Practice      0.10 lot         │
│  50 gen ×    + MC        API         API           + compound      │
│  50 pop      + Correl.   (free)      (real data)                   │
└────────────────────────────────────────────────────────────────────┘
```

## Phase 0 : Backtest (Sprint 7-8)

**Déjà fait :**
- ✅ GeneticEngine (50 pop × 50 gen, Virtual Threads)
- ✅ StrategyTemplate (Trend, MeanRev, Breakout, Momentum)
- ✅ ProvenStrategies (7 stratégies codées)
- ✅ Walk-Forward Analysis
- ✅ Multi-Market Validator
- ✅ Robustness Score + Ranking Dashboard
- ✅ Parameter Sensitivity Analysis
- ✅ BatchStrategyRunner (500+ stratégies)

**Reste :**
- ⏳ Calibrer sur 20 ans de données (download en cours)
- ⏳ Seasonality filters (Phase 1-5)
- ⏳ News sentiment filter gene

## Phase 1 : Validation (Sprint 8-9)

```bash
# Fitness criteria pour passage en Paper Trading
SHARPE_MIN  = 1.5
PF_MIN      = 2.0
MAX_DD      = 15%  # max drawdown
WIN_RATE    = 35%  # minimum (acceptable si PF > 2.5)
MIN_TRADES  = 100  # statistiquement significatif
WFO_ROBUST  = true  # Walk-Forward Optimization passée
MC_95       = true  # Monte Carlo 95% de scénarios rentables
```

**Gate 1 : Validation automatique**
```
BatchStrategyRunner
  → génère 500+ stratégies
  → filtre avec critères ci-dessus
  → Top 10% passent en Paper Trading
```

## Phase 2 : Paper Trading (Sprint 9)

**Architecture :**
```
┌─────────────────────┐     ┌─────────────────────┐
│   Trading Bridge    │     │    OANDA Practice    │
│   (Java, VPS)       │────►│    API (v20)         │
│                     │     │                      │
│  StrategyRunner     │     │  Account:            │
│  ← bar() callback   │     │  101-002-4729622-008 │
│  ← execute order    │     │  Balance: $99,581    │
└─────────────────────┘     └─────────────────────┘
         │
         ▼
┌─────────────────────┐
│   Monitoring        │
│   HTTP Server       │
│   (port 8080)       │
│                     │
│  /status → positions│
│  /health → uptime   │
│  /pnl   → daily P&L │
└─────────────────────┘
```

**Règles Paper :**
- Mêmes prix que le réel (OANDA Practice = vrai market)
- Lots virtuels (0.01 simulé)
- Journal de TOUS les trades dans SQLite
- Durée minimum: 1 mois de paper rentable
- Max drawdown paper: 30% (alerte si dépassé)

**Gateway de monitoring :**
```java
class MonitoringServer {
    // HTTP sur port 8080
    // Endpoints:
    GET /status     → { strategy: "EMA_CROSS_EURUSD_H1",
                        position: "LONG", pnl: "+127.50",
                        trades: 45, win_rate: 62.2% }
    GET /health     → { uptime: "127h", memory: "256MB",
                        last_trade: "2025-05-19T22:30:00Z" }
    GET /risk       → { current_dd: 8.2%, var_95: 1.3%,
                        exposure: 2.5% }
}
```

## Phase 3 : Small Live (Sprint 10-11)

**Critères d'entrée :**
1. ✅ 1 mois de paper trading rentable (Sharpe > 1.0)
2. ✅ Max drawdown paper < 15%
3. ✅ 50+ trades en paper
4. ✅ Walk-Forward valide sur 20 ans

**Paramètres de départ :**
```
CAPITAL     = $1,000 (sur les $99k dispo — 1%)
RISK_PER    = 0.5%  (risque par trade)
MAX_OPEN    = 2     (positions simultanées)
LEVERAGE    = 1:10  (max OANDA)
LOT_SIZE    = 0.01  (mini lot)
```

**Progression :**
```
Semaine 1-2:   0.01 lot, max 2 trades/jour
Semaine 3-4:   0.02 lot si +5% sur les 2 semaines
Mois 2:        0.05 lot si DD < 10%
Mois 3:        0.10 lot si Sharpe > 1.5
Mois 6:        Compound progressif
```

## Phase 4 : Scaling (Sprint 12+)

**Ajouter des instruments :**
- EUR/USD → 3 mois ✅ → ajouter USD/JPY
- +2 paires → 3 mois ✅ → ajouter GBP/USD
- Capital pool: max 30% per instrument
- Corrélation max: 0.7 entre paires

**Ajouter des stratégies :**
- Jusqu'à 5 stratégies en parallèle
- Chacune avec son propre risk budget
- Diversification: une trend, une mean-rev, une breakout

## Infrastructure VPS

**Docker Compose (trading-bridge/):**
```yaml
services:
  trader:
    build: .
    image: trading-bridge:latest
    ports:
      - "8080:8080"     # Monitoring HTTP
    environment:
      - OANDA_API_KEY=${OANDA_API_KEY}
      - OANDA_ACCOUNT_ID=${OANDA_ACCOUNT_ID}
      - OANDA_ENV=practice   # → live
      - STRATEGY=EMA_CROSS_EURUSD_H1
      - MODE=paper           # → live
      - MAX_RISK=0.5
    volumes:
      - ./data:/data         # Données historiques
```

**Monitoring externe (HTTP poll, pas OpenClaw) :**
```
Crond sur la machine hôte → curl VPS:8080/health
  → si 5xx → alerte Telegram
  → si drawdown > 20% → alerte URGENTE
  → si gap de prix > 5% → alerte (slippage)
```

## Règles Bmad pour le déploiement

### R1 — Jamais de changement le vendredi
Le vendredi 22h (NY close) → pas de déploiement.
Les marchés ferment, le gap du dimanche peut être violent.

### R2 — Un seul changement à la fois
Tu changes de stratégie?
→ Désactiver l'ancienne
→ Activer la nouvelle
→ Attendre 24h de paper avant live

Tu ajoutes un instrument?
→ Le trader en isolation 1 semaine paper
→ Puis 0.01 lot live 2 semaines

### R3 — Kill switch
```bash
ssh VPS
docker compose stop trader
curl -X POST localhost:8080/emergency-stop
```

Le kill switch doit être testé AVANT le premier trade live.

### R4 — Journalisation forensique
```
data/logs/
├── trades/          # Chaque trade: entrée, sortie, P&L
│   ├── 2026-05-19.csv
│   └── ...
├── prices/          # Snapshot des prix toutes les minutes
│   ├── 2026-05-19.csv
│   └── ...
└── errors/          # Stack traces, timeouts, OANDA API errors
    ├── 2026-05-19.log
    └── ...
```

### R5 — A/B testing
Quand une nouvelle version d'un algo est prête :
```
V1 (production) → paper + 0.01 lot
V2 (candidate)  → paper uniquement
Comparer: Sharpe ratio sur 2 semaines
Si V2 meilleure → remplacer V1
```

## Checklist de déploiement

```markdown
# Pre-flight checklist (avant chaque déploiement)

## Code
- [ ] `mvn test` passe (131 tests)
- [ ] Stratégie compile sans warning
- [ ] Walk-Forward passé sur 20 ans
- [ ] Monte Carlo 95% rentable

## Validation
- [ ] 1 mois de paper trading rentable
- [ ] 50+ trades en paper
- [ ] Max drawdown paper < 15%
- [ ] Sharpe paper > 1.0

## Infrastructure
- [ ] Docker compose up fonctionne
- [ ] Monitoring HTTP répond
- [ ] Kill switch testé
- [ ] Telegram alertes fonctionnent
- [ ] Règle R1 respectée (pas vendredi)

## Risk
- [ ] Position size: 0.5% risk par trade
- [ ] Max 2 positions simultanées
- [ ] Stop loss défini
- [ ] Take profit défini
- [ ] Capital alloué: max 1% du total
```

## Récapitulatif chronologique

```
Semaine 1:  20 ans de données H1     ← maintenant
Semaine 2:  Calibration des stratégies
Semaine 3:  VPS setup + Docker
Semaine 4:  Paper trading EUR/USD
  ↓
Mois 2:     Validation paper
Mois 3:     Small live (0.01 lot)
  ↓
Mois 4-6:   Scale up + diversification
  ↓
Objectif:   Vivre du trading (24 mois)
```

> Fil rouge : chaque passage de gate doit être documenté.
> Pas de saut de phase. La discipline paie.
