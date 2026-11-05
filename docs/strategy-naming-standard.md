# Trading Bridge — Strategy Naming Standard

## 🏷️ Nom de stratégie (fichier + classe)

```
[MECHANISM]_[TF]_[Description]_V[N]
```

| Partie | Requis | Valeurs | Exemple |
|--------|--------|---------|---------|
| **MECHANISM** | ✅ | Voir tableau ci-bas | `TREND` |
| **TF** | ❌ (défaut H1) | Voir tableau ci-bas | `H1` |
| **Description** | ✅ | PascalCase, max 30 chars | `EmaCloudTrend` |
| **_V{N}** | ❌ | _V1 à _V99 | `_V1` |

**Exemples valides :**
- `TREND_H1_EmaCloudTrend_V1`
- `MOM_H1_RSIPulse_V2`
- `MR_H4_ZScoreReversion_V1`
- `CALENDAR_D1_NfpWeek_V1`
- `SESSION_LondonBreakout_V1` (sans TF → implicite H1)

---

## ⚙️ @StrategyState — Champs et valeurs valides

```java
@StrategyState(
    name = "TREND_H1_EmaCloudTrend_V1",
    mechanism = Mechanism.TREND,
    state = State.PRODUCTION,
    since = "2026-07-01",
    reason = ""           // optionnel, requis si state = SUSPENDED ou REJECTED
)
```

### Mechanism

| Constante | Code dossier | Quand l'utiliser |
|-----------|-------------|------------------|
| `TREND` | `trend/` | Suivi de tendance (EMA, Ichimoku, ADX) |
| `MOMENTUM` | `momentum/` | Momentum, accélération (RSI, Bollinger, VWAP) |
| `MEANREVERSION` | `meanreversion/` | Mean reversion (RSI3, Z-score) |
| `BREAKOUT` | `breakout/` | Breakout (Donchian, Inside Bar) |
| `PATTERN` | `pattern/` | Price action (Engulfing, Fractal, Wick) |
| `VOLATILITY` | `volatility/` | Volatilité (ATR, Spike, Squeeze) |
| `VOLUME` | `volume/` | Volume-based (OBV, Volume Profile) |
| `SESSION` | `session/` | Sessions (London Open, Asian Range) |
| `CALENDAR` | `calendar/` | Calendaire / News / Saisonnalité |
| `ML` | `ml/` | Machine Learning (à venir) |
| `OTHER` | `other/` | Tout ce qui ne fit pas ailleurs |

### Timeframe (TF)

| Constante | Code | Notes |
|-----------|------|-------|
| `M1` | M1 | 1 minute |
| `M5` | M5 | 5 minutes |
| `M15` | M15 | 15 minutes |
| `M30` | M30 | 30 minutes |
| `H1` | H1 | **Défaut** — 1 heure |
| `H4` | H4 | 4 heures |
| `D1` | D1 | 1 jour |
| `W1` | W1 | 1 semaine |

### State

| Constante | Icône | Signification |
|-----------|-------|---------------|
| `CONCEPT` | 🟤 | Idée dans Joplin, pas codée |
| `DRAFT` | 🟠 | En cours de codage |
| `EXPERIMENTAL` | 🟡 | Backtestée, prometteuse |
| `PAPER` | 🔵 | Paper trade sur PC |
| `STAGING` | 🟣 | Serveur de staging |
| `PRODUCTION` | 🟢 | Live 24/7 |
| `MONITORING` | 🔵 | En prod mais sous surveillance |
| `SUSPENDED` | 🟠 | Retirée temporairement |
| `HIBERNATING` | 💤 | Saisonnière |
| `REJECTED` | 🔴 | Failed quality gates |
| `ARCHIVED` | ⚫ | Morte, gardée pour référence |

### Raisons de suspension (state_reason)

| Constante | Usage |
|-----------|-------|
| `RANGE_MARKET` | Marché en range, strat de trend pas adaptée |
| `LOW_VOLATILITY` | Volatilité trop basse |
| `HIGH_VOLATILITY` | Volatilité trop haute, risque excessif |
| `REGIME_CHANGE` | Changement de régime (FED pivot, etc.) |
| `SEASONAL_OFF` | Hors-saison |
| `PERFORMANCE_DROP` | PF passé sous 1.0 |
| `TECHNICAL_ISSUE` | Bug, dépendance cassée |
| `DRAWDOWN_LIMIT` | Max drawdown atteint |
| `MANUAL` | Suspendue manuellement |

---

## 📁 Dossiers

```
strategies/
├── trend/
├── momentum/
├── meanreversion/
├── breakout/
├── pattern/
├── volatility/
├── volume/
├── session/
├── calendar/
├── ml/
├── other/
├── prop/          ← Catalogue (inchangé)
└── _deprecated/    ← Ancien _rejected/
```
