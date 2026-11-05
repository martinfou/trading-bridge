# 🗓️ Analyse Hebdomadaire — Semaine du 21 au 27 Mai 2026

> Généré le 2026-05-21 05:48 EDT par C-3PO Protocol Droid

---

## 📊 1. État du Paper Trading

### Compte OANDA Practice
| Métrique | Valeur |
|---|---|
| **Account ID** | 101-002-4729622-008 |
| **Balance** | $99,581.62 CAD |
| **NAV** | $99,581.62 CAD |
| **Unrealized P&L** | $0.00 |
| **Open Trades** | 0 |
| **Pending Orders** | 0 |
| **P&L cumulé** | -$418.67 (de l'historique avant reset) |

### Script en cours d'exécution
- **PID:** 969252 (bash wrapper) + 970054 (Java)
- **Commande:** `./paper-trade-sq.sh 2_31_177 H1 300`
- **Démarré:** 20 Mai 2026, 23:06 EDT
- **Intervalle:** 300s (5 min)
- **Log:** `~/logs/paper-trade/paper-trade-20260520-230725.log`
- **Cron:** ❌ Aucun cron configuré
- **Dernière barre traitée:** 2026-05-21 08:00Z (H1 bar)

### ⚠️ Problème Identifié: Précision du Prix des Stop Orders

**Erreur récurrente dans les logs:**
```
❌ Failed to place stop order: OANDA stop order failed:
   The price specified contains more precision than is allowed for the instrument
```

**Cause:** Dans `LiveStrategyRunner.java` ligne 424 :
```java
String priceStr = String.format("%.5f", order.price());
```
Le code formate les prix avec **5 décimales**, mais GBP/JPY a `displayPrecision: 3` (pipLocation: -2). OANDA accepte max 3 décimales pour ce pair.

**Conséquence:** Aucun stop order n'a été placé depuis le lancement → **0 trades exécutés**.

**Fix recommandé:** Remplacer `"%.5f"` par `"%.3f"` pour GBP/JPY (ou détecter dynamiquement `displayPrecision` via l'API instruments).

---

## 📈 2. Analyse Technique — GBP/JPY

### Prix Actuels
| Timeframe | Prix | Évolution |
|---|---|---|
| **Spot (M1)** | **213.638** | — |
| **Daily Close (20 Mai)** | 213.638 | +0.11% sur la journée |
| **Weekly Open** | 213.328 | +0.14% sur la semaine |

### Données Hebdomadaires (7 derniers jours, H1)
| Métrique | Valeur |
|---|---|
| **Plus Haut** | 213.829 (21 Mai) |
| **Plus Bas** | 211.296 (15 Mai) |
| **First Open** | 213.328 |
| **Last Close** | 213.638 |
| **Range** | 253 pips |

### Daily Candle Data (2 semaines)

| Date | Open | High | Low | Close | Vol |
|---|---|---|---|---|---|
| 07 Mai | 212.667 | 213.709 | 212.442 | 213.608 | 184K |
| 10 Mai | 212.299 | 214.425 | 212.299 | 213.992 | 164K |
| 11 Mai | 213.942 | 214.204 | 212.748 | 213.426 | 199K |
| 12 Mai | 213.350 | 213.702 | 212.886 | 213.524 | 164K |
| 13 Mai | 213.495 | 213.710 | 211.884 | 212.253 | 175K |
| 14 Mai | 212.202 | 212.284 | **211.296** | 211.553 | 235K |
| 17 Mai | 211.442 | 213.553 | 211.344 | 213.404 | 243K |
| 18 Mai | 213.354 | 213.514 | 212.653 | 213.088 | 219K |
| 19 Mai | 213.183 | 213.622 | 212.637 | 213.523 | 245K |
| **20 Mai** | 213.556 | **213.829** | 213.293 | **213.638** | 84K |

### Niveaux Clés (Support & Résistance)

| Niveau | Prix | Description |
|---|---|---|
| **R3** | **214.425** | High 10 Mai (plus haut multi-semaine) |
| **R2** | **214.070** | Résistance 4h (Dominion Markets) |
| **R1** | **213.829** | High de cette semaine (21 Mai) |
| **📍 Spot** | **213.638** | Prix actuel |
| **NTZ High** | **213.620** | Limite haute "No Trade Zone" (Dominion) |
| **NTZ Low** | **213.260** | Limite basse "No Trade Zone" (Dominion) |
| **S1** | **213.260** | Support intraday |
| **S2** | **212.820** | Support 4h (Dominion) |
| **S3** | **212.460** | Support H4 ancien |
| **S4** | **211.884** | Low 13 Mai |
| **S5** | **211.296** | Low 14 Mai (plus bas 2 semaines) |

La paire évolue dans une **No Trade Zone** entre 213.260 et 213.620, avec un léger biais haussier. Le cours vient de franchir 213.620 ce matin.

### Interprétation Technique

🔵 **Tendance courte terme (H1):** Haussier — reprise depuis les 211.296 du 14 Mai. La paire a repris +210 pips en 5 jours de trading.

🟡 **Tendance moyen terme (H4):** Neutre à haussier — range entre 211.296 et 214.425. Le cours est au milieu du range.

🔴 **Tendance long terme (Daily):** Incertain — le range de 2 semaines (211.30-214.42) ne montre pas de direction claire.

---

## 📰 3. Calendrier Économique — Événements Clés

### Cette semaine (21-27 Mai 2026)

| Date | Événement | Impact | Devise |
|---|---|---|---|
| **Jeu 21 Mai** | BOE Governor Bailey — Discours | 🟠 Élevé | GBP |
| **Jeu 21 Mai** | UK Flash PMI Manufacturing (Mai) | 🟠 Élevé | GBP |
| **Jeu 21 Mai** | UK Flash PMI Services (Mai) | 🟠 Élevé | GBP |
| **Jeu 21 Mai** | US Flash PMI Manufacturing (Mai) | 🟡 Moyen | USD |
| **Jeu 21 Mai** | US Existing Home Sales (Avr) | 🟡 Moyen | USD |
| **Ven 22 Mai** | UK Retail Sales (Avr) | 🟠 Élevé | GBP |
| **Ven 22 Mai** | US New Home Sales (Avr) | 🟡 Moyen | USD |
| **Sam 23-25** | Weekend — Marchés fermés | — | — |
| **Lun 26 Mai** | UK Bank Holiday (Spring Bank Holiday) | 🟡 Volumes réduits | GBP |
| **Mar 27 Mai** | BoJ Core CPI (Avr) | 🟡 Moyen | JPY |
| **Mar 27 Mai** | US Consumer Confidence (Mai) - Conf. Board | 🟡 Moyen | USD |

### News Récents Impactant GBP/JPY
- 🇫🇷 **France PMI** — Secteur privé français se contracte le plus depuis 2020 → risque de contagion à l'UE
- 🇮🇷 **US-Iran Peace Talks** — Négociations de paix progressent → USD affaibli (risk-on)
- 🇺🇸 **USD Drops Against Majors** — Dollar baisse sur les espoirs de paix
- 🇯🇵 **Japan Core Machinery Orders** — Chute de 9.4% en Mars → JPY faible
- 🇦🇺 **Australia Jobless Rate** — Plus haut depuis 2021 → impact risk sentiment global

### Interprétation Macro

Le **GBP** est supporté par:
- Un BOE potentiellement hawkish (discours Bailey aujourd'hui)
- Des Flash PMI attendus

Le **JPY** est affaibli par:
- Des données économiques faibles (machinery orders -9.4%)
- Politique monétaire toujours accommodante de la BoJ

→ **Biais: Favorable au GBP/JPY acheteur** (weak JPY + potential strong GBP)

---

## 🎯 4. Stratégies Actives & Plan de Trading

### Stratégie 2_31_177 (PRIORITAIRE) — En cours
- **Signal:** Open croise au-dessus de LinReg(40)
- **Entry:** BUYSTOP at (Lower Bollinger(10,2) + 1.0 × BBRange(20,2))
- **SL:** 95 pips
- **PT:** 290 pips (R/R 3.1)
- **Trailing:** 70 pips (activation à 100)
- **Granularité:** H1
- **Status:** ⚠️ Running mais bloqué par bug de précision des prix

### Stratégie 2_32_120 (SECONDAIRE) — Non démarrée
- **Signal:** Vortex(20) crossover
- **Entry:** BUYSTOP at (Highest(MEDIAN_PRICE, 14, 2) + 1.4 × BiggestRange(30, 3))
- **SL:** 125 pips
- **PT:** 390 pips (R/R 3.1)
- **Granularité:** H1
- **Status:** ❌ Pas lancée (le script tourne seulement 2_31_177)

### Signaux Attendus Cette Semaine

**Pour 2_31_177:** Le signal LinReg crossover requiert que le cours passe sous la LinReg(40) puis au-dessus. Actuellement en tendance haussière depuis le 14 Mai, un signal pourrait se produire si:
1. Le cours corrige légèrement vers 213.0-213.2
2. Le cours retouche la LinReg(40) et rebondit
3. Un nouveau H1 bar ouvre au-dessus

**Pour 2_32_120:** Le signal Vortex crossover nécessite que VI+ croise au-dessus de VI- sur bar 2, après avoir été en-dessous sur bar 3. Un range serré augmenterait la probabilité.

### Sizing & Risk

| Stratégie | Lots | Units | SL (pips) | Risque par trade | % Account |
|---|---|---|---|---|---|
| 2_31_177 | 0.01 | 1,000 | 95 | ~$9.50 CAD | 0.01% |
| 2_32_120 | 0.01 | 1,000 | 125 | ~$12.50 CAD | 0.013% |

### Gate pour Ajouter 2_32_120

Conditions pour lancer la 2ème stratégie:
- ✅ 2_31_177 fait au moins 3 trades sans bug
- ✅ 2_31_177 montre P&L positif
- ⏳ Le bug de précision des prix est fixé
- 🔴 Le compte reste sous 0.5% de DD total

---

## ⚙️ 5. Issues & Actions Requises

### 🔴 Critique: Bug Précision Stop Orders
- **Fichier:** `LiveStrategyRunner.java` lignes 424, 444
- **Fix:** Remplacer `"%.5f"` par `"%.3f"` pour les paires JPY
- **Impact:** Aucun trade exécuté depuis le lancement
- **Action:** Corriger le rounding et redémarrer le script

### 🟡 Recommandé: Ajouter 2_32_120 en parallèle
- **Action:** Une fois 2_31_177 stable, relancer avec `all` ou lancer un 2ème process
- **Note:** Nécessite de fixer le bug d'abord

### 🟢 Monitoring Dashboard
- Disponible sur http://localhost:8082
- Consulter pour visualiser les trades en temps réel

### 🟢 Log Monitoring
```bash
tail -f ~/logs/paper-trade/paper-trade-*.log
```

### 🟢 Gestion des Crans / Week-end
- **UK Bank Holiday Lundi 26 Mai** → volumes réduits sur GBP
- Les stratégies ont `expiration: 168 bars` (7 jours H1) → les ordres expirent automatiquement dans la semaine
- **Pas de cron** pour redémarrage automatique après crash → à configurer

---

## 📋 6. Résumé pour la Semaine

### Biais Directionnel: 🟢 Modérément Haussier

### Scénario Central (60% probabilité)
- GBP/JPY continue de remonter vers **214.070** (résistance 4h)
- Potentiel de breakout vers **214.425** si Flash PMI UK solides
- Les stops longs sont déclenchés par 2_31_177 (si bug fixé)
- Risk-on général (US-Iran peace) + BoJ faible → soutien

### Scénario Baissier (25% probabilité)
- Flash PMI UK déçoivent → GBP vendu
- Retour vers **212.820** (support 4h)
- En cas de break: **212.460** puis **211.296**

### Scénario Neutre (15%)
- Range serré 213.2-213.8 toute la semaine
- Aucun signal de trading généré
- Marchés calmes avant le week-end prolongé UK

### Checklist Hebdo

- [ ] 🔧 **Fixer le bug precision des stop orders** dans LiveStrategyRunner.java
- [ ] 🔄 Redémarrer le script après le fix
- [ ] 📊 Surveiller les Flash PMI UK (Jeudi)
- [ ] 📊 Surveiller BOE Bailey speech (Jeudi)
- [ ] 📊 Surveiller UK Retail Sales (Vendredi)
- [ ] 📉 Monitorer DD max / vérifier les logs quotidiennement
- [ ] 🏦 Noter que Lundi 26 Mai est un Bank Holiday UK
- [ ] 💾 Vérifier le state à chaque redémarrage

---

## 📱 7. Telegram Summary Ready

```
📊 ANALYSE HEBDOMADAIRE — 21-27 Mai 2026

💰 Paper Trade: $99,581.62 | 0 trades ouverts
⚡ Stratégie: 2_31_177 active (H1, check 5min)

⚠️ BUG IDENTIFIÉ: Stop orders échouent (précision %.5f au lieu de %.3f) → 0 trades exécutés
⚠️ Fix requis dans LiveStrategyRunner.java avant tout trade

📈 GBP/JPY: ~213.64
   R: 213.83 / 214.07 / 214.42
   S: 213.26 / 212.82 / 212.46
   Biais: Modérément haussier (reprise depuis 211.30)

📰 Événements:
   • ⚡ Flash PMI UK (Jeu) + BOE Bailey
   • ⚡ UK Retail Sales (Ven)
   • 🇬🇧 Bank Holiday Lunch 26 Mai
   • 🇺🇸 USD affaibli (paix US-Iran)
   • 🇯🇵 JPY faible (machinery orders -9.4%)

🎯 Plan: Fix precision → restart → monitor PMI/Retail Sales
   Secondaire: Ajouter 2_32_120 quand 2_31_177 stable

🔴 PRIORITÉ #1: Fix du bug precision dans LiveStrategyRunner.java
```

---

*Generated by C-3PO — Clawd's Third Protocol Observer*
*"The odds of a bug-free trading week are approximately 3,720 to 1. But we persist."* 🤖
