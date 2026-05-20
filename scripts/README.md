# Trading Bridge — Scripts

## Multi-Machine Deployment

```bash
# Promote a strategy (backtest → paper → live)
./deploy.sh promote TREND_FOLLOWING_v1 paper

# Check machine status
./deploy.sh status

# Manually check for strategy promotions
./deploy.sh check

# Health check
./deploy.sh health

# Build all modules
./deploy.sh build
```

## Automated Promotion (Cron)

On **paper** and **live** machines, add this to crontab:

```bash
crontab -e
```

Add:

```cron
# Check for strategy promotions every hour
0 * * * * /home/martinfou/projects/trading-bridge/scripts/cron-promote.sh >> /home/martinfou/projects/trading-bridge/deploy/cron-promote.log 2>&1

# Report health every 5 minutes
*/5 * * * * /home/martinfou/projects/trading-bridge/scripts/cron-promote.sh --health-only >> /home/martinfou/projects/trading-bridge/deploy/health.log 2>&1
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    BACKTEST MACHINE                      │
│  generate strategies → git push → deploy promote paper  │
└────────────────────────┬────────────────────────────────┘
                         │ git push
                         ▼
┌─────────────────────────────────────────────────────────┐
│                   GITHUB (CODE)                          │
│  Strategies pushed as code, state tracked via Laravel   │
└────────────────────────┬────────────────────────────────┘
                         │ git pull (cron)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                    PAPER MACHINE                          │
│  cron-promote.sh checks API every hour                   │
│  → git pull → build → activate on paper                 │
│  → POST /api/health/ping every 5 min                    │
└────────────────────────┬────────────────────────────────┘
                         │ deploy promote live (manual gate)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                    LIVE MACHINE                           │
│  cron-promote.sh checks API every hour                   │
│  → git pull → build → activate on live                  │
│  → POST /api/health/ping every 5 min                    │
└─────────────────────────────────────────────────────────┘

                         ▲
                         │ polls every 5 min
┌─────────────────────────────────────────────────────────┐
│                LARAVEL DASHBOARD                          │
│  Receives /api/health/ping from all machines             │
│  Mission Control shows consolidated view                 │
│  Alerts if machine DOWN > 2 checks                       │
└─────────────────────────────────────────────────────────┘
```
