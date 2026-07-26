# News Playbook — Semaine du 27 au 31 juillet 2026

> Généré: dimanche 26 juillet 2026
> Projet: trading-bridge
> Auteur: Simons Research

## ⚠️ Constat préliminaire

**Aucune edge systématique backtestable n'est disponible pour cette semaine.**

Raisons:
1. **Aucune fenêtre saisonnière active** — Les patterns SeasonalityFilter (AUD_USD Jun 4-Jul 19, USDCAD Oct-Nov, EUR/USD/GBP/USD Apr-March) sont tous hors de cette période.
2. **H1 systematic edge absente** — Le fix look-ahead (commit `c7a552db`) a éliminé toutes les edges simples sur H1. 4+ familles de stratégies convergent vers PF ~0.9 après coûts.
3. **TurnOfMonthFlowStrategy existe déjà** — Stratégie pour le month-end déjà codée dans `creative/`.

→ **Skip backtest automatisé ce cycle.** Ce document sert de guide pour le paper trading manuel.

---

## 📅 Calendrier Macro — 3 Banques Centrales + Month-End

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| Lun | 27 Jul | — | — | Faible | — |
| Mar | 28 Jul | Pre-FOMC positioning | EUR/USD, USD/JPY | Moyen | Toute session |
| **Mer** | **29 Jul** | **⚡ FOMC Rate Decision** | **USD toutes paires** | **⭐⭐⭐** | **14:00 ET** |
| Mer | 29 Jul | FOMC Press Conference (Warsh) | USD | ⭐⭐⭐ | 14:30 ET |
| **Jeu** | **30 Jul** | **⚡ BOE MPC Rate Decision** | **GBP/USD, EUR/GBP** | **⭐⭐⭐** | **07:00 ET (12:00 GMT)** |
| Jeu | 30 Jul | **⚡ US PCE Inflation** | **USD** | **⭐⭐⭐** | 08:30 ET |
| **Jeu** | **30 Jul** | **⚡ BOJ Policy Decision** | **USD/JPY, EUR/JPY** | **⭐⭐⭐** | ~23:00 ET (jeu→ven) |
| Ven | 31 Jul | **Month-End Rebalancing** | Toutes | ⭐⭐ | Toute session |
| Ven | 31 Jul | BOJ Governor Press Conference | JPY | ⭐⭐⭐ | ~00:30 ET |
| Ven | 31 Jul | US Employment Cost Index | USD | ⭐⭐ | 08:30 ET |

---

## 🏦 Contexte des Banques Centrales

### FOMC (Mer 29 Jul, 14:00 ET)
- **Taux actuel**: 3.50-3.75%
- **Probabilité de hausse**: En hausse (selon Motley Fool, "doublé/triplé la semaine dernière")
- **Fed Chair**: Kevin Warsh (nommé sous Trump, considéré hawkish)
- **Enjeu**: Soit statu quo, soit surprise haussière
- **Thesis préférée**: Biais haussier USD avant FOMC (positionnement institutionnel), réaction directionnelle après

### BOE (Jeu 30 Jul, 07:00 ET / 12:00 GMT)
- **Taux actuel**: 3.75%
- **Publication**: Trimestrielle (accompagnée du Monetary Policy Report)
- **Enjeu**: Guidance sur l'inflation UK, forward guidance
- **GBP/USD**: Volatilité élevée prévue les 30min suivant la publication

### BOJ (Jeu 30-Ven 31 Jul)
- **Taux actuel**: Zone négative ou proche de zéro (en normalisation)
- **Calendrier**: Réunion de 2 jours (30-31), décision soir jeudi, conférence presse vendredi matin
- **Enjeu**: Toute surprise sur le resserrement fait exploser USD/JPY

### Month-End (Ven 31 Jul)
- **Flux**: Rééquilibrage de portefeuilles institutionnels
- **Caractéristique**: Prix peuvent bouger sans news (purement des flux)
- **Clé**: Les flux month-end sont amplifiés en fin de trimestre mais juillet n'est pas quarter-end

---

## 📋 Paper Trading Playbook

### Setup 1: Pré-FOMC Positioning (Mar 28 - Mer 29 matin)

**Thèse**: Les institutions réduisent le risque et couvrent les positions avant la décision FOMC. 
Cela crée du "position squaring" qui peut amplifier les mouvements techniques.

**Paire cible**: EUR/USD
**Direction**: Attendre un range serré (vol contraction) puis 
- Entrer BUY si EUR/USD > EMA(20) sur D1 (uptrend intact)
- Entrer SELL si EUR/USD < EMA(20) sur D1 (downtrend)
**Risk**: 0.5% par trade
**Stop**: 1.5× ATR(14) H1
**Sortie**: Avant 14:00 ET Mer (fermer avant la décision)

### Setup 2: Post-FOMC Momentum (Mer 29, 14:00-16:00 ET)

**Thèse**: La réaction initiale au FOMC est souvent directionnelle pendant 30-60 min
avant le "fade" ou la continuation.

**Paire cible**: EUR/USD ou USD/JPY
**Direction**: Attendre 15 min après la publication (candle 15min de réaction)
- Entrer dans la direction de la bougie réaction
**Stop**: 1.0× ATR(14) H1 (serré — si le trade ne marche pas dans l'heure, sortir)
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5% max

⚠️ **Ne PAS trader la conférence de presse** (14:30 ET) — trop de whipsaw.

### Setup 3: BOE + PCE Thursday Overlap (Jeu 30, 07:00-09:00 ET)

**Thèse**: BOE à 07:00 ET + PCE US à 08:30 ET créent une double-volatilité sur GBP/USD.
Si BOE produit une GBP move dans une direction, PCE peut soit confirmer soit inverser.

**Paire cible**: GBP/USD
**Stratégie**: Attendre 15 min après PCE (08:30 ET) pour l'overlap BOE/PCE
**Entrée**: Dans la direction du move GBP/USD si BOE + PCE sont alignés
- BOE hawkish + PCE chaud → BUY GBP/USD
- BOE dovish + PCE froid → SELL GBP/USD
- Mixte → PAS DE TRADE
**Stop**: 1.5× ATR(14) H1
**Risk**: 0.5%

### Setup 4: Month-End Flow Capture (Ven 31 Jul)

**Thèse**: Le dernier jour de juillet (un vendredi) + FOMC+BOE+BOJ passés = les flux 
month-end sont amplifiés par la direction que les CB meetings ont établie.

**Paire cible**: USD/JPY (le plus sensible aux flux month-end)
**Stratégie**: USD/JPY fix à 12:00 ET (le fixing de la N.Y. Fix)
- Si USD/JPY > fixing level → acheter
- Si USD/JPY < fixing level → vendre
**Sortie**: Fin de journée vendredi 17:00 ET ou trailing stop ATR(14) 1.5×

---

## 📊 Allocation Suggérée

Compte OANDA paper: $50,000
Trade size max: 0.5% ($250 risque/trade)
Nombre max de trades ouverts: 2

| Setup | Direction | Taille max | $ Risque | Trade |
|-------|-----------|------------|----------|-------|
| 1 - Pré-FOMC | TBD mercredi | 0.5% | $250 | EUR/USD |
| 2 - Post-FOMC | TBD mercredi 14:00 | 0.5% | $250 | USD/JPY |
| 3 - BOE/PCE | TBD jeudi 07:00 | 0.3% | $150 | GBP/USD |
| 4 - Month-End | TBD vendredi 12:00 | 0.3% | $150 | USD/JPY |

---

## ⚠️ Règles Strictes

1. **Pas plus de 2 positions ouvertes simultanément**
2. **Chaque trade est indépendant** — ne pas "averager" une perdante
3. **Si un setup n'a pas de signal clair → PAS DE TRADE**
4. **Fermeture obligatoire à 16:30 ET les jours de CB meeting** (ne pas overnight les positions
   macro sans contrôle)
5. **Le vendredi**, fermeture à 17:00 ET — PAS de positions overnight week-end (triple swap)

---

## Analyse de Risque

Le plus grand risque cette semaine: **3 CB meetings non corrélés**.
Si FOMC est hawkish (USD fort) mais BOE est aussi hawkish (GBP fort), 
GBP/USD peut bouger dans une direction imprévisible.

**Recommandation**: Trader 1 paire par event si possible, pas de pairs trading.
Privilégier EUR/USD pour FOMC (pure exposition USD), GBP/USD pour BOE.

---

## 📝 Post-Mortem (à remplir après la semaine)

| Setup | Exécuté? | PnL | Leçon |
|-------|----------|-----|-------|
| 1 - Pré-FOMC | | | |
| 2 - Post-FOMC | | | |
| 3 - BOE/PCE | | | |
| 4 - Month-End | | | |
