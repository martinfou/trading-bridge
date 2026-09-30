# News Playbook — Semaine du 31 août au 4 septembre 2026

> Généré: mercredi 26 août 2026 (scan macro — tâche secondaire du mercredi)
> **Confirmé: vendredi 28 août 2026 (deep dive)** — RBA (1er mardi = 1 sept), ECB
> (1er jeudi = 3 sept), NFP (1er vendredi = 4 sept) = événements calendaire-fixes ✅
> Projet: trading-bridge
> Auteur: Simons Research
> ⚠️ Calendrier basé sur les événements RÉCURRENTS + contexte repo (RBA 1er sept confirmé
> par le playbook 24-28 août). À confirmer précisément vendredi (deep dive) via ForexFactory.

## ✅ Confirmation vendredi 28 août (deep dive)

- **Événements ⭐⭐⭐ confirmés par construction** : RBA mar 1 sept (~00:30 ET), ISM mar,
  ECB jeu 3 sept, NFP ven 4 sept. Semaine ⭐⭐⭐ inchangée.
- **Mise à jour XAU (setup #5)** : le deep dive du 28 août (GoldTurtleDxyFilter, 23e résultat)
  montre que les trades long or de la famille Turtle performent MIEUX quand le DXY est
  AU-DESSUS de sa moyenne ~21 jours (régime USD ferme), pas en « bear dollar ». Pour le
  hedging macro de la semaine : un long or se tient mieux si le DXY reste ferme ; éviter
  d'ajouter du long or en fenêtre de dollar faible. Voir note Joplin 28 août.
- Aucune fenêtre saisonnière active (USDCAD Oct 12 reste le prochain candidat H2).

## ⚠️ Constat préliminaire

**Semaine ⭐⭐⭐ : 2 banques centrales (RBA + ECB) + NFP + ISM = semaine macro majeure.**

- **Aucune fenêtre saisonnière active** : prochaines fenêtres = USDCAD BUY Oct 12-Nov 26
  (EXPLORE, re-test fin septembre) et USD/JPY Sep 27-Nov 11 (**REJECT** le 24 août — régime,
  ne pas trader). Les fenêtres GBP/EUR du H1 (Apr/May/Aug) sont passées.
- **Aucune edge H1 systématique** (PF ~0.9 documenté) ; les edges vivants (GoldTurtle H1 3u+S2,
  calendrier saisonnier 4+5+8 GBP) ne sont pas pertinents pour une semaine macro.
- Le calendrier 4+5+8 **sans filtre de régime** reste la config recommandée (le test du
  26 août — filtre D1 SMA-N — montre qu'il n'ajoute pas d'alpha : net coupé de moitié,
  contrôle OPPOSITE aussi profitable sur GBP → calendrier robuste dans tous les régimes).

## 📅 Calendrier Macro (événements récurrents — à confirmer vendredi)

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| Lun | 31 Aug | 🇨🇳 China PMI Manufacturing (tôt) / 🇩🇪 CPI préliminaire | AUD, EUR | ⭐⭐ | variable |
| **Mar** | **1 Sep** | **🇦🇺 RBA Rate Decision** (après CPI chaud du 25 août ?) | **AUD/USD** | **⭐⭐⭐** | ~00:30 |
| **Mar** | **1 Sep** | **🇺🇸 ISM Manufacturing PMI** | **USD** | **⭐⭐⭐** | 10:00 |
| Mer | 2 Sep | 🇺🇸 ADP Employment (si publié avant NFP) / JOLTS | USD | ⭐⭐ | 08:15 |
| **Jeu** | **3 Sep** | **🇪🇺 ECB Rate Decision + Conférence Lagarde** | **EUR/USD** | **⭐⭐⭐** | 08:15/08:45 |
| Jeu | 3 Sep | 🇺🇸 Unemployment Claims / ISM Services | USD | ⭐⭐ | 08:30/10:00 |
| **Ven** | **4 Sep** | **🇺🇸 NFP + Unemployment Rate** (1er vendredi du mois) | **USD toutes** | **⭐⭐⭐** | 08:30 |
| Ven | 4 Sep | 🇨🇦 Canada Employment | USD/CAD | ⭐⭐ | 08:30 |

## 🏦 Contexte des banques centrales (au 26 août)

### RBA (décision mardi 1er sept)
- **Taux actuel** : 4.35% (maintien le 11 août)
- **Contexte** : AUD CPI mardi 25 août — le dernier chiffre AVANT la décision.
  CPI chaud → hausse possible en sept → **AUD rally** ; CPI froid → maintien, AUD sous pression.
- **⚠️ Fenêtre 00:30 ET = liquidité faible** → spreads larges, taille réduite.

### ECB (décision jeudi 3 sept)
- Contexte H2 2026 : l'ECB est en cycle de baisse graduelle (2025-26) ; l'inflation
  zone euro revient vers la cible → baisse de 25 pb probable ou maintien selon données.
- EUR/USD : la paire est dans la fenêtre morte saisonnière (pas de biais calendaire H2
  validé — octobre REJECT, novembre poison). Le biais EUR saisonnier est un H1 (avril).

### Fed (NFP vendredi 4 sept)
- **Taux actuel** : 3.50-3.75% (maintien FOMC 29 juillet, présidence Warsh hawkish)
- Contexte : Jackson Hole 27-28 août (discours Warsh vendredi 28) + Core PCE mercredi 26.
  Le NFP du 4 sept sera LE premier chiffre après Jackson Hole.
- NFP fort → confirmation hawkish → **USD rally** ; NFP faible → paris de baisse septembre → **USD sell-off**.

## 🎯 Setups paper trading (à affiner vendredi dans le playbook officiel)

| # | Paire | Direction | Timing | Stop | Risk% |
|---|-------|-----------|--------|------|-------|
| 1 | AUD/USD | Selon RBA (hawkish → long) | Mar 1 sept, après décision + conférence | ATR 1.5j | 0.5% |
| 2 | EUR/USD | Selon ECB (dovish → short) | Jeu 3 sept, après Lagarde | ATR 1.5j | 0.5% |
| 3 | USD/JPY | Long USD si NFP fort | Ven 4 sept, 08:35 ET | ATR 1j | 0.5% |
| 4 | EUR/USD | Short USD si NFP faible | Ven 4 sept, 08:35 ET | ATR 1j | 0.5% |
| 5 | XAU/USD | Hedging macro (or réagit aux 2 CB) | Toute la semaine | — | 0.5% |

## ⚠️ Règles strictes
- **Couvre-feux** : pas de trade 10 min avant/après les décisions CB et NFP (spreads + slippage).
- **Max 2 positions simultanées** (les paires USD sont corrélées).
- **Taille max** : 0.5% risque par setup, 1.5% risque total semaine.
- **Fusion de positions** : ne pas cumuler EUR/USD short + USD/JPY long (même trade USD).
- Ce playbook est un **guide manuel** — pas de backtest possible (événements futurs).

## Post-mortem (à remplir samedi 5 sept)

| Setup | Paire | Direction | Résultat | Leçon |
|-------|-------|-----------|----------|-------|
| 1 | AUD/USD | | | |
| 2 | EUR/USD | | | |
| 3 | USD/JPY | | | |
| 4 | EUR/USD | | | |
| 5 | XAU/USD | | | |
