# Architecture Multi-Machine — Synchronisation & Monitoring

> Bmad — Plan d'architecture
> 3 machines : Backtest, Paper, Live + 1 Dashboard central

## Cœur du problème

Chaque machine a un rôle différent et ne doit PAS partager son état
dans git. Pourtant on a besoin:

1. Qu'une stratégie puisse être promue backtest → paper → live
2. Que le dashboard montre les stats de TOUTES les machines
3. Que les alertes marchent quelque soit la machine
4. Que le code soit synchronisé sans dépendre d'un déploiement manuel

## Solution : Git pour le CODE, API pour l'ÉTAT

### Synchronisation du code (Git)

```
BACKTEST ──git push──► GitHub ──git pull──► PAPER VPS
                          │
                          └──git pull──► LIVE VPS
```

Chaque machine fait `git pull` avant de lancer son runner.
Le code est identique partout — seule la config change.

### Synchronisation de l'état (Laravel API)

```
BACKTEST ──POST /api/deployments──────► LARAVEL DASHBOARD
PAPER    ──POST /api/deployments/{id}/trades──► LARAVEL DASHBOARD
LIVE     ──POST /api/deployments/{id}/metrics──► LARAVEL DASHBOARD
LARAVEL  ──GET  /api/strategies──────► toutes les machines (lecture seule)
```

### Synchronisation des données historiques

Les données NE sont PAS synchronisées entre machines:

| Machine    | Données                    | Source               |
|------------|---------------------------|----------------------|
| Backtest   | 20 ans H1 + M1 (5 GB)    | Dukascopy download   |
| Paper      | 1 mois (cache local)      | OANDA Practice API   |
| Live       | real-time uniquement       | OANDA Real API       |
| Dashboard  | aucune (lit l'API REST)   | PostgreSQL           |

Chaque machine télécharge SES propres données.
Pas de sync — pas de point de défaillance unique.

## Monitoring Centralisé

### Health Check HTTP (toutes les 5 min)

Chaque machine expose un endpoint HTTP:

```
BACKTEST   http://192.168.x.x:9090/health
PAPER VPS  http://vps-paper:9090/health
LIVE VPS   http://vps-live:9090/health
```

Le endpoint retourne:

```json
{
  "machine": "backtest",
  "uptime": "72h 30m",
  "version": "1.0.0",
  "git_commit": "abc1234",
  "active_strategies": ["TREND_FOLLOWING_1_EURUSD_H1_v1.0.0"],
  "resources": {"cpu": 12, "memory": 45, "disk": 23},
  "last_trade": "2026-05-20T14:30:00Z",
  "errors_24h": 0,
  "oanda_api_status": "ok"
}
```

### Alerting (Telegram + Discord)

Le dashboard Laravel fait un poll health check toutes les 5 min:

```
┌─────────────────────────────────────────────┐
│  HEALTH MONITOR (Dashboard VPS)             │
│                                             │
│  while true:                                │
│    for machine in [backtest, paper, live]:  │
│      try: curl /health (timeout: 5s)        │
│      if 5xx or timeout:                     │
│        → Telegram ALERTE: "Machine DOWN"    │
│      if drawdown > 15%:                     │
│        → Discord ALERTE: "Risk warning"     │
│    sleep 300                                │
└─────────────────────────────────────────────┘
```

### Synchronisation des clés API

Les clés API ne sont jamais dans git. Chaque machine a son propre `.env`:

```
BACKTEST (.env):
  OANDA_API_KEY=xxx           # pas utilisé (pas de live)
  DUKASCOPY_CACHE=/data/bars/ # 20 ans de données

PAPER VPS (.env):
  OANDA_API_KEY=xxx-practice
  OANDA_ACCOUNT_ID=101-002-...
  OANDA_ENV=practice

LIVE VPS (.env):
  OANDA_API_KEY=xxx-real
  OANDA_ACCOUNT_ID=001-xxx-...
  OANDA_ENV=live
```

Le deploy.sh passe les clés via variables d'environnement Docker,
jamais dans un fichier commité.

## Workflow complet

### 1. Un développeur (toi) code sur BACKTEST

```bash
# Backtest local
mvn test                                       # 131 tests
./scripts/batch-gen.sh --count 500              # génère stratégies
./scripts/deploy.sh promote TREND_FOLLOWING_v1.0.0 paper

# deploy.sh POST /api/deployments → Laravel
# Laravel enregistre: "TREND_FOLLOWING → paper, active"
```

### 2. PAPER reçoit la stratégie

```bash
# Sur PAPER VPS (via SSH ou cron)
git pull
./scripts/deploy.sh activate TREND_FOLLOWING_v1.0.0

# Le runner Java:
#   - lit la config active depuis Laravel API
#   - commence paper trading OANDA Practice
#   - POST /api/deployments/{id}/trades après chaque trade
#   - POST /api/deployments/{id}/metrics toutes les heures
```

### 3. Après 30 jours paper → promotion LIVE

```bash
# Sur le VPS PAPER ou depuis ton dashboard
./scripts/deploy.sh promote TREND_FOLLOWING_v1.0.0 live

# deploy.sh vérifie les gates:
#   30 jours paper ✓   trades > 50 ✓   Sharpe > 1.0 ✓
# POST /api/deployments → Laravel (phase=live)
#
# Réponse: "✅ Promu vers LIVE. Redéployer le LIVE VPS."
```

### 4. LIVE VPS prend le relais

```bash
# Sur LIVE VPS
git pull
./scripts/deploy.sh activate TREND_FOLLOWING_v1.0.0

# Le runner live:
#   - passe en mode REAL (OANDA api-fxtrade)
#   - trades réels avec 0.01 lot
#   - POST chaque trade → Laravel
```

## Monitoring Dashboard (vue unique)

```
┌─────────────────────────────────────────────────────────────────┐
│  TRADING BRIDGE — MISSION CONTROL                22:41 UTC      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  🟢 BACKTEST        UP 72h    CPU 12%   Mem 45%                │
│     Stratégies: 7 | Batch en cours: 500/500 générés            │
│     20 ans H1: ✅ | 20 ans M1: ⏳ download (15%)               │
│                                                                 │
│  🟢 PAPER           UP 48h    CPU 3%    Mem 28%                │
│     Actif: TREND_FOLLOWING_v1.0.0 | Sharpe: 1.24               │
│     Trades: 52/50 ✅ | PnL: +$450 | DD: 8.5%                  │
│     ⏳ Promotion live dans 28 jours                             │
│                                                                 │
│  🔴 LIVE            DOWN      Dernier uptime: jamais            │
│     Aucune stratégie active                                     │
│     Docker: pas déployé / VPS non configuré                     │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│  📊 ALERTES (dernières 24h)                                     │
│  22:30 ✅ Backtest: Batch 500/500 terminé                      │
│  22:15 ⚠️ M1 download lent (rate limit Dukascopy)             │
├─────────────────────────────────────────────────────────────────┤
│  🚀 ACTIONS RAPIDES                                             │
│  [Promouvoir TREND_FOLLOWING → LIVE]  [Kill switch]            │
│  [Recalibrer]  [Tout arrêter]                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Résumé : Qui fait quoi

| Tâche                        | Où ?              | Comment ?                          |
|------------------------------|-------------------|------------------------------------|
| Code                         | Git + GitHub      | 3 machines pull                    |
| Stratégies actives           | Laravel DB        | API REST, 3 machines écrivent      |
| Trades + PnL                 | Laravel DB        | POST /api/trades après chaque trade |
| Métriques (Sharpe, DD)       | Laravel DB        | POST /api/metrics toutes les heures |
| Données historiques          | disque local      | download-data.sh sur chaque machine |
| Clés API                     | .env (pas git)    | Docker env vars                    |
| Alertes                      | Telegram/Discord  | Health Monitor (dashboard)         |
| Monitoring                   | Laravel Dashboard | GET /api/health → toutes les 5 min |

## Fichiers de config par machine

Chaque machine a son propre dossier `deploy/` (gitignored):

```
deploy/
├── backtest.env       → OANDA_API_KEY= (pas utilisé)
├── paper.env          → OANDA_API_KEY=practice-xxx
├── live.env           → OANDA_API_KEY=real-xxx
└── README.md
```

Le deploy.sh lit le bon fichier selon le hostname.
