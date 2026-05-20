# 🚀 Mission Control — Documentation

> Martin Fournier — Dernière mise à jour: 2026-05-19

---

## 🧠 Agents OpenClaw

| Agent | ID | Personnalité | Rôle |
|:------|:---|:-------------|:------|
| 🤖 **C-3PO** | `dev` | Protocol droid | Code, trading, infra, Discord |
| 🏛️ **Seneca** | `stoic` | Philosophe stoic | Journaling matin/soir |
| ❤️ **Vitalis** | `health` | Coach santé | Poids, nutrition, check-in |
| 🤑 **TradeMaster** | `trading` | Trader | Stratégies, marchés |
| ✍️ **Scriptor** | `content` | Écrivain | Blog posts, scripts YouTube |

---

## 📡 URLs & Services

| Service | URL | Port | Statut |
|:--------|:---|:----:|:------:|
| OpenClaw Dashboard | `http://localhost:8082` | 8082 | ✅ |
| Laravel Trading | `http://localhost:8000` | 8000 | ✅ |
| Laravel Login | `martinfou@gmail.com` / `ght1cdkc` | — | ✅ |
| OANDA Practice API | `api-fxpractice.oanda.com` | — | ✅ $99,581.62 |
| IP Réseau local | `192.168.4.195` | — | ⚠️ Eero isolation |

---

## ⏰ Cron Jobs

| Horaire | Agent | Tâche | Channel |
|:-------:|:------|:------|:--------|
| **01:00** | dev | 🤖 Nightly Build — code auto | Discord #agent-office |
| **08:00** | stoic | ☀️ Stoic Morning Journal | Telegram |
| **08:00** | dev | 🔒 Security Audit | Discord #security |
| **20:00** | dev | 🔒 Security Audit | Discord #security |
| **21:00** | stoic | 🌙 Stoic Evening Journal | Telegram |
| **23:00** | dev | 🧹 Compaction mémoire | Telegram |
| **Dim 10:00** | content | ✍️ Blog post / YouTube | Telegram |
| **Sam 18:00** | health | 📊 Weekly check-in | Telegram |
| **Toutes les heures** | — | 🧠 Memory Consolidation | Silencieux |

---

## 📂 Projets (9 actifs)

| Projet | Stack | Path | Bmad |
|:-------|:------|:-----|:----:|
| 🚀 trading-bridge | Java/Maven | `~/projects/trading-bridge/` | ✅ |
| 🧾 ocr-receipt | Python/PyQt | `~/projects/ocr-receipt/` | ✅ |
| 📱 facebook-marketplace | — | `~/projects/facebook-marketplace-auto-responder/` | ✅ |
| 🌐 martinfournier-website | Laravel/Vue | `~/projects/martinfournier-website/` | ✅ |
| 🖥️ trading-dashboard | Laravel/Vue | `~/projects/trading-dashboard/` | ✅ |
| ❤️ health-dashboard | Laravel | `~/projects/health-dashboard/` | ✅ |
| 🎬 la-minute-copilot | JSON | `~/projects/la-minute-copilot/` | ✅ |
| 📈 oanda-strategies | Java | `~/projects/oanda-strategies/` | ✅ |
| 💰 personal-finance | — | `~/projects/personal-finance/` | ✅ |

---

## 📊 Dashboard OpenClaw (mudrii)

| Info | Valeur |
|:-----|:-------|
| URL | `http://localhost:8082` |
| Binaire | `~/.openclaw/dashboard/openclaw-dashboard` |
| Config | `~/.openclaw/dashboard/config.json` |
| Version | v2026.5.13 |
| Gateway | Port 18789 (⚠️ version mismatch, cosmétique) |
| Thème | Midnight (6 disponibles) |

---

## 🔗 GitHub

| Projet | URL |
|:-------|:----|
| trading-bridge | `https://github.com/martinfou/trading-bridge` |
| trading-dashboard | `https://github.com/martinfou/trading-dashboard` |
| health-dashboard | `https://github.com/martinfou/health-dashboard` |
| ocr-receipt | `https://github.com/martinfou/ocr-receipt` |
| facebook-marketplace | `https://github.com/martinfou/facebook-marketplace-auto-responder` |
| martinfournier-website | `https://github.com/martinfou/martinfournier-website` |
| la-minute-copilot | `https://github.com/martinfou/la-minute-copilot` |
| oanda-strategies | `https://github.com/martinfou/oanda-strategies` |
| personal-finance | `https://github.com/martinfou/personal-finance` |

---

## 🧬 Sprints Trading Bridge

| Sprint | Sujet | Statut |
|:------:|:------|:------:|
| 1 | Foundation | ✅ |
| 2 | XML Parser | 🚧 |
| 3 | Advanced Backtest | ✅ |
| 4 | Broker Connectors | 🚧 |
| 5 | Production | 🚧 |
| 6 | Backtesting Analytics | ✅ |
| 7 | StrategyQuant Replication | ✅ **131 tests** |
| 8 | 🗞️ Multi-Factor Strategy | 📅 Planifié |
| 9 | Trade Journal | 📅 |
| 10 | Risk Management | 📅 |
| 11 | Dashboard & Scanner | 📅 |
| 12 | AI/ML Integration | 📅 |
| 13 | News & Sentiment | 📅 |
| 14 | Enterprise Quality | 📅 |

---

## 🔐 Credentials

| Service | Où |
|:--------|:----|
| OANDA API | `trading-dashboard/.env` |
| Joplin Token | `/tmp/joplin_token.txt` |
| Discord Bot | `DISCORD_BOT_TOKEN` (env var) |
| DeepSeek API | OpenClaw config |
| Gemini API | Stoic journal cron |
| OpenClaw Gateway | `openclaw.json` (port 18789) |

---

## 🛠️ Commandes utiles

```bash
# Trading Bridge
cd ~/projects/trading-bridge
mvn test                          # 131 tests
./scripts/batch-gen.sh --count 500 # Générer des stratégies
./scripts/run-live.sh 2_31_177    # Tester live sur OANDA
./scripts/convert-jforex.sh       # Convertir JForex

# Trading Dashboard
cd ~/projects/trading-dashboard
php artisan serve --port=8000

# Security
openclaw security audit
openclaw security audit --fix

# Discord
systemctl --user restart openclaw-gateway
journalctl --user -u openclaw-gateway -f
```
