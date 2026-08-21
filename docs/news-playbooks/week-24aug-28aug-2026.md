# News Playbook — Semaine du 24 au 28 août 2026

> Généré: mercredi 19 août 2026
> Projet: trading-bridge
> Auteur: Simons Research

## ⚠️ Constat préliminaire

**Aucune edge systématique backtestable pour cette semaine — JACKSON HOLE WEEK (⭐⭐⭐).**
Ce document sert de guide pour le paper trading manuel.

Raison du skip :
1. ✅ Aucune fenêtre saisonnière active (prochaine : USD/JPY BUY Sep 27-Nov 11, 88% ; USDCAD BUY Oct 12-Nov 26, 94%)
2. ✅ H1 systematic edge absente sur FX (PF ~0.9 après coûts — documenté). Les 2 seuls edges positifs post-fix (GoldTurtleTrend PF 1.17, GoldTurtlePyramid 1.16) sont sur XAU et non pertinents pour une semaine macro
3. ✅ Nouveau résultat du jour : BearishMonthsFade (MAY+AUG) EXPLORE — edge saisonnier FX net positif sur GBP/EUR, mais fenêtres mai/août uniquement, PAS un trade pour cette semaine (la fenêtre août se termine le 31)
4. ✅ Contexte : semaine CPI UK + FOMC Minutes + Employment AUD (17-21 août) derrière nous. La semaine prochaine est dominée par le **Jackson Hole Symposium (jeudi-vendredi 27-28 août)** — le discours de Kevin Warsh (Fed hawkish) est l'événement macro de l'été

**Contexte particulier :** Jackson Hole est LE rendez-vous annuel de la Fed. Sous la nouvelle présidence Warsh
(hawkish, décision du 29 juillet : maintien 3.50-3.75%), le discours de vendredi 28 sera scruté pour :
- Le signal sur la trajectoire des taux H2 2026 (encore restrictif ? data-dependent ?)
- La réponse au dernier CPI US (12 août) et aux FOMC Minutes (19 août)
- Toute déclaration sur la neutralité ou le bilan

**⚠️ Double risque :** La semaine contient AUSSI un **Core PCE US mercredi 26** (la mesure d'inflation préférée
de la Fed — publiée AVANT Jackson Hole, elle conditionne le ton du discours) et un **AUD CPI mardi 25**
(3 jours avant la décision RBA du 1er septembre). C'est une semaine où le calendrier prépare le discours.

---

## 📅 Calendrier Macro (confirmé via ForexFactory)

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| Lun | 24 Aug | 🇦🇺 RBA Monetary Policy Meeting Minutes | AUD | ⭐⭐ | 21:30 |
| **Mar** | **25 Aug** | **🇦🇺 AUD CPI m/m + Trimmed Mean CPI** | **AUD/USD** | **⭐⭐⭐** | **21:30** |
| Mar | 25 Aug | 🇩🇪 German ifo Business Climate | EUR | ⭐⭐ | 04:00 |
| Mar | 25 Aug | 🇺🇸 CB Consumer Confidence | USD | ⭐⭐ | 10:00 |
| **Mer** | **26 Aug** | **🇺🇸 Core PCE Price Index m/m** | **USD toutes** | **⭐⭐⭐** | **08:30** |
| **Mer** | **26 Aug** | **🇺🇸 Prelim GDP q/q** | **USD** | **⭐⭐⭐** | **08:30** |
| Mer | 26 Aug | 🇺🇸 Durable Goods Orders m/m | USD | ⭐⭐ | 08:30 |
| **Jeu** | **27 Aug** | **🌍 Jackson Hole Symposium (jour 1)** | **All** | **⭐⭐/⭐⭐⭐** | **09:00-17:00** |
| Jeu | 27 Aug | 🇺🇸 Unemployment Claims | USD | ⭐⭐ | 08:30 |
| Jeu | 27 Aug | 🇯🇵 Tokyo Core CPI y/y | JPY | ⭐⭐ | 18:30 |
| **Ven** | **28 Aug** | **🌍 Jackson Hole Symposium (jour 2) — Discours Warsh attendu** | **All** | **⭐⭐⭐** | **10:00** |
| **Ven** | **28 Aug** | **🇨🇦 CAD GDP m/m** | **USD/CAD** | **⭐⭐⭐** | **08:30** |
| **Ven** | **28 Aug** | **🇺🇸 Prelim Benchmark Payrolls Revision** | **USD** | **⭐⭐⭐** | **08:30** |
| Ven | 28 Aug | 🇺🇸 Revised UoM Consumer Sentiment | USD | ⭐⭐ | 10:00 |

**⚠️ À noter :** Pas de décision de taux cette semaine (RBA décide le 1er sept). Les 3 blocs dominants :
1. **Core PCE mercredi 08:30** — l'inflation préférée de la Fed, publiée 2 jours AVANT le discours Jackson Hole
2. **Jackson Hole jeudi-vendredi** — le discours Warsh (vendredi ~10:00 ET) est LE catalyseur USD de l'été
3. **AUD CPI mardi 21:30** — 3 jours avant la RBA ; un CPI chaud → le marché pricing d'une hausse en septembre

---

## 🏦 Contexte Macro

### Fed (Jackson Hole — discours attendu vendredi 28 août)
- **Taux actuel**: 3.50-3.75% (maintien au FOMC du 29 juillet)
- **Fed Chair**: Kevin Warsh (considéré hawkish)
- **Thesis**: Le marché cherche dans le discours Jackson Hole :
  - La confirmation ou l'inflexion du ton hawkish (l'inflation est revenue proche de 3% en 2026 ?)
  - Le langage sur la neutralité — est-ce que 3.50-3.75% est restrictif ou neutre ?
  - La réponse aux données récentes : NFP (7 août), CPI (12 août), Minutes (19 août), PCE (26 août)
- **Scénarios** :
  - Discours hawkish ferme (résistance à toute baisse, vigilance inflation) → **USD rally** (EUR/USD ↓, USD/JPY ↑)
  - Discours équilibré/data-dependent (risques bilatéraux, attend les données) → réaction modérée, revente du rally
  - Discours dovish surprise (mention de risques de croissance, baisse possible) → **USD sell-off**
- **⚠️ Le PCE de mercredi est le prélude** : PCE chaud → Warsh justifié hawkish → rally USD amplifié ;
  PCE froid → le marché anticipe un discours plus neutre.

### RBA (AUD CPI mardi 25 août, 21:30 ET — décision RBA le 1er sept)
- **Taux actuel**: 4.35% (maintien le 11 août)
- **Thesis**: Le CPI est le dernier chiffre majeur AVANT la décision du 1er septembre.
  - CPI m/m ≥ 0.5% ou Trimmed Mean ≥ 0.7% → le marché pricing une hausse en sept → **AUD rally**
  - CPI froid (≤ 0.3%) → maintien certain, AUD sous pression
- **⚠️ Fenêtre 21:30 ET = liquidité faible** → spreads larges, taille réduite.

### BoC (CAD GDP vendredi 28 août, 08:30 ET)
- **Taux actuel**: en cycle de baisse (la BoC a baissé plusieurs fois en 2025-26)
- **Thesis**: GDP chaud → le marché retire des baisses → **USD/CAD ↓**. GDP froid → USD/CAD ↑.
  Le CAD a déjà le CPI de lundi 17 derrière lui (résultat à intégrer).

### Fenêtres saisonnières
| Paire | Fenêtre | Hit Rate | Statut |
|-------|---------|:--------:|--------|
| USD/JPY | Sep 27 - Nov 11 (BUY) | 88% | ⏳ Dans 37 jours |
| USDCAD | Oct 12 - Nov 26 (BUY) | 94% | ⏳ Dans 52 jours |
| GBP/USD | Avr (BUY) + Mai/Août (SELL) — SeasonalCalendar 4+5+8 | 68-89% | ⏳ Fenêtre août SELL se termine le 31 août |
| GBP/USD | Mai + Août (SELL — BearishMonthsFade MAY+AUG) | 68-71% | ⏳ Fenêtre août se termine le 31 août |
| Aucune | — | — | **Aucune fenêtre à trader cette semaine** |

**Mise à jour 21 août (deep dive) :** la piste « 2e long leg octobre » est REJECT — octobre meurt en OOS (GBP IS hit 77.8% → OOS 28.6%, EUR pareil) et empoisonne le calendrier 4+5+8+10 (GBP net -20%, OOS +$158 vs +$1 096). Le calendrier validé reste **4+5+8** (GBP PF 2.83).

---

## 📋 Paper Trading Setups

### Setup 1: AUD CPI Tuesday (Mar 25 Aug, 21:30 ET) ⭐ LE SETUP ASIATIQUE
**Thèse**: CPI ⭐⭐⭐ 3 jours avant la RBA — direction selon le chiffre vs consensus.
**Scénarios**:
- **CPI hot (≥ 0.5% m/m)** → BUY AUD (AUD/USD long)
- **CPI cold (≤ 0.3% m/m)** → SELL AUD (AUD/USD short)
**Paire**: AUD/USD
**Entrée**: 15-30 min après 21:30 ET (laisser le whipsaw se résoudre)
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.3% (liquidité asiatique réduite)
**Fenêtre**: 21:45-23:30 ET uniquement

### Setup 2: Core PCE Wednesday (Mer 26 Aug, 08:30 ET) ⭐ SETUP PRINCIPAL PRÉ-JACKSON
**Thèse**: PCE ⭐⭐⭐ — l'inflation préférée de la Fed, prélude direct au discours Jackson Hole.
**Scénarios**:
- **PCE chaud (≥ 0.3% m/m)** → BUY USD (EUR/USD short, USD/JPY long) — Warsh justifié hawkish
- **PCE froid (≤ 0.1% m/m)** → SELL USD (EUR/USD long) — le marché anticipe un discours neutre
**Paire**: EUR/USD (la plus liquide) OU USD/JPY (la plus directionnelle)
**Entrée**: 15-20 min après 08:30 ET
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5%
**Fenêtre**: 08:45-10:30 ET uniquement

### Setup 3: Jackson Hole — Discours Warsh (Ven 28 Aug, ~10:00 ET) ⭐ LE SETUP DE LA SEMAINE
**Thèse**: Le discours du président de la Fed à Jackson Hole est l'événement macro de l'été.
**Scénarios**:
- **Hawkish ferme** → BUY USD (EUR/USD short, USD/JPY long)
- **Dovish surprise** → SELL USD (EUR/USD long, USD/JPY short)
- **In-line/équilibré** → PAS DE TRADE (réaction trop faible, whipsaw)
**Paire**: USD/JPY (la plus directionnelle) OU EUR/USD (la plus liquide)
**Entrée**: Attendre **20-30 min** après le début du discours — les discours Jackson Hole créent souvent un spike puis une correction
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5% max
**Fenêtre**: 10:20-12:00 ET uniquement. Aucune position laissée après 12:30 ET.

### Setup 4: CAD GDP Friday (Ven 28 Aug, 08:30 ET) — avant le discours
**Thèse**: GDP ⭐⭐⭐ en fenêtre américaine — direction selon le chiffre vs consensus.
**Scénarios**:
- **GDP hot** → BUY CAD (USD/CAD short)
- **GDP cold** → SELL CAD (USD/CAD long)
**Paire**: USD/CAD
**Entrée**: 15-30 min après 08:30 ET
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.4%
**Fenêtre**: 08:45-10:00 ET uniquement (close avant le discours Jackson Hole)

---

## 💰 Allocation suggérée

- **Taille max par position**: 0.5% du capital
- **Risque total semaine**: 1.5% max (3 positions simultanées max)
- **Nombre max de positions**: 3 (pas de chevauchement PCE + Jackson Hole + CAD GDP)
- **Capital recommandé pour paper trading**: $10K-$50K (1 lot mini = 10K units)

## ⚖️ Règles strictes

- **Couvre-feux horaires** : pas de position ouverte pendant les publications suivantes :
  - Mardi 21:30 ET (AUD CPI) — fermer avant 21:15 si position AUD
  - Mercredi 08:30 ET (PCE/GDP) — fermer avant 08:15 si position USD
  - Vendredi 08:30 ET (CAD GDP) — fermer avant 08:15 si position CAD
  - Vendredi ~10:00 ET (Jackson Hole) — fermer avant 09:45 si position USD
- **Fusion de positions** : ne JAMAIS ouvrir un 2e trade dans la même paire si un trade de la même direction est ouvert
- **Pas de revenge trading** : max 2 tentatives par événement (1 entrée + 1 retry si stop touché dans les 10 min)
- **Laisser le post-mortem** : chaque trade est journalisé dans le post-mortem ci-dessous

---

## 📋 Post-mortem (à remplir après la semaine)

| Setup | Date | Paire | Direction | Entrée | Sortie | PnL | Leçon |
|-------|------|-------|-----------|--------|--------|-----|-------|
| 1. AUD CPI | 25 Aug | AUD/USD | | | | | |
| 2. Core PCE | 26 Aug | EUR/USD | | | | | |
| 3. Jackson Hole | 28 Aug | USD/JPY | | | | | |
| 4. CAD GDP | 28 Aug | USD/CAD | | | | | |

**Bilan de la semaine** (à remplir) :
- Nombre de trades : ___ / Profit : ___ / Perte : ___
- Qu'est-ce qui a marché ? ___
- Qu'est-ce qui n'a pas marché ? ___
- Leçon pour la prochaine semaine macro : ___
