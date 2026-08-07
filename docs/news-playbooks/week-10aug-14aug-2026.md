# News Playbook — Semaine du 10 au 14 août 2026

> Généré: vendredi 7 août 2026
> Projet: trading-bridge
> Auteur: Simons Research

## ⚠️ Constat préliminaire

**Aucune edge systématique backtestable pour cette semaine — CPI week (⭐⭐⭐).**
Ce document sert de guide pour le paper trading manuel.

Raison du skip :
1. ✅ Aucune fenêtre saisonnière active (prochaine : USD/JPY Sep 27 - Nov 11, 88% hit rate — dans 51 jours)
2. ✅ H1 systematic edge absente (PF ~0.9 après coûts — documenté ; deep dive du jour : HMM Regime REJECT, 6e famille convergente)
3. ✅ Aucune stratégie codée validée post-fix ne couvre CPI (les stratégies news du catalogue sont pré-fix, suspectes)
4. ✅ Contexte : semaine après NFP (7 août) + digestion Triple CB Week (27-31 juillet) — le marché repricie le récit désinflation

**Contexte particulier :** Première CPI week complète sous la nouvelle Fed (Kevin Warsh, hawkish — décision du 29 juillet).
Le CPI de mercredi est le premier test d'inflation depuis la décision. Si Warsh a haussé/confirmé le hawkishness
tout en gardant un biais "data-dependent", un CPI chaud sera amplifié (USD rally) ; un CPI froid testera la crédibilité hawkish.

---

## 📅 Calendrier Macro (confirmé via ForexFactory)

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| Lun | 10 Aug | 🇯🇵 Economy Watchers Sentiment | JPY | ⭐ | 01:00 |
| Lun | 10 Aug | 🇪🇺 Sentix Investor Confidence | EUR | ⭐ | 04:30 |
| **Mar** | **11 Aug** | **🇦🇺 RBA Cash Rate + Statement + Press Conf** | **AUD/USD** | **⭐⭐⭐** | **00:30** |
| **Mer** | **12 Aug** | **🇺🇸 Core CPI m/m + CPI m/m + y/y** | **USD toutes** | **⭐⭐⭐** | **08:30** |
| Mer | 12 Aug | 🇩🇪 German Final CPI m/m | EUR | ⭐⭐ | 02:00 |
| Mer | 12 Aug | 🇳🇿 NZ Inflation Expectations q/q | NZD | ⭐⭐ | 23:00 |
| **Jeu** | **13 Aug** | **🇬🇧 GDP m/m + Prelim GDP q/q** | **GBP/USD** | **⭐⭐** | **02:00** |
| **Jeu** | **13 Aug** | **🇺🇸 Core PPI m/m + PPI m/m + Unemployment Claims** | **USD** | **⭐⭐/⭐⭐⭐** | **08:30** |
| **Ven** | **14 Aug** | **🇺🇸 Retail Sales m/m + Core Retail Sales** | **USD** | **⭐⭐⭐** | **08:30** |
| Ven | 14 Aug | 🇺🇸 Prelim UoM Consumer Sentiment + Inflation Exp | USD | ⭐⭐ | 10:00 |

**⚠️ À noter :** Pas de FOMC/BOE/BOJ cette semaine. L'événement dominant est le **CPI US de mercredi 08:30 ET**,
suivi du **PPI jeudi** et des **Retail Sales vendredi** — la semaine complète tourne autour du récit inflation US.

---

## 🏦 Contexte Macro

### Fed (décision du 29 juillet — semaine dernière)
- **Taux actuel**: 3.50-3.75% (post-FOMC 29 juillet)
- **Fed Chair**: Kevin Warsh (considéré hawkish)
- **Thesis**: Le NFP du 7 août et le CPI du 12 août sont les deux premiers tests post-décision.
  - CPI m/m ≥ 0.3% ou y/y en accélération → le marché valide Warsh → USD rally (EUR/USD ↓, USD/JPY ↑)
  - CPI m/m ≤ 0.1% (désinflation confirmée) → le marché doute de la crédibilité hawkish → USD sell-off
- **NFP du 7 août**: publié aujourd'hui — le post-mortem de son impact sera intégré au contexte de lundi

### RBA (mardi 11 août, 00:30 ET)
- **Taux actuel**: 4.35% (attente : maintien)
- **Thesis**: L'Australie n'a pas le même cycle que la Fed — la RBA a maintenu plus longtemps. Un maintien
  avec ton dovish → AUD affaibli (AUD/USD ↓). Une surprise hawkish (hike) → AUD rally.
- **⚠️ Fenêtre asiatique 00:30 ET = liquidité faible** → spreads larges, taille réduite.

### BCE / BOE / BOJ (pas de décision cette semaine)
- **BOE**: décision passée (30 juillet, taux 3.75%) — le GDP britannique de jeudi 02:00 ET donnera le ton
  pour la prochaine réunion. GDP m/m fort → GBP/USD ↑ ; contraction → GBP/USD ↓.
- **BOJ**: en normalisation — USD/JPY à ~158 (niveaux actuels). Un CPI US chaud + JPY faible = combo volatil
  sur USD/JPY mercredi.
- **ECB**: pas d'événement majeur — EUR/USD piloté par le dollar mercredi.

### Fenêtres saisonnières
| Paire | Fenêtre | Hit Rate | Statut |
|-------|---------|:--------:|--------|
| USD/JPY | Sep 27 - Nov 11 (BUY) | 88% | ⏳ Dans 51 jours |
| Aucune | — | — | **Aucune fenêtre active cette semaine** |

---

## 📋 Paper Trading Setups

### Setup 1: RBA Night Trade (Mar 11 Aug, 00:30 ET)
**Thèse**: Décision de taux ⭐⭐⭐ en fenêtre asiatique. Réaction initiale souvent directionnelle et exploitable.
**Paire**: AUD/USD
**Entrée**: 10-15 min après publication (00:40-00:45 ET), direction de la bougie réaction
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1 ou fermeture 03:00 ET
**Risk**: 0.3%
**⚠️**: Session asiatique = liquidité faible → taille réduite de moitié. Ne pas overnight.

### Setup 2: CPI Wednesday Play (Mer 12 Aug, 08:30 ET) ⭐ LE SETUP PRINCIPAL
**Thèse**: Premier CPI sous Warsh. La direction dépend du chiffre et du récit hawkish/dovish.
**Scénarios**:
- **CPI hot (≥ 0.3% m/m)** + Warsh hawkish → **BUY USD** (EUR/USD short, USD/JPY long)
- **CPI cold (≤ 0.1% m/m)** → **SELL USD** (test de crédibilité hawkish)
- **CPI in-line (~0.2%)** → réaction initiale faible, trader le rejet aux niveaux clés

**Paire**: USD/JPY (la plus directionnelle) OU EUR/USD (la plus liquide)
**Entrée**: Attendre **15-30 min** après 08:30 ET — laisser le whipsaw initial se résoudre
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5% max
**Fenêtre**: 08:45-10:30 ET uniquement. Aucune position laissée après 11:00 ET.

### Setup 3: UK GDP Morning (Jeu 13 Aug, 02:00 ET)
**Thèse**: GDP britannique ⭐⭐ donne le ton post-BOE. Un GDP solide renforce le GBP après la décision de maintien.
**Paire**: GBP/USD
**Entrée**: 15 min après publication (02:15 ET), direction de la réaction
**Stop**: 1.0× ATR(14) H1
**TP**: 1.5× ATR(14) H1
**Risk**: 0.2% (setup moyen — GDP ⭐⭐ seulement)

### Setup 4: PPI + Claims Confirmation (Jeu 13 Aug, 08:30 ET)
**Thèse**: Le PPI confirme ou infirme le récit CPI de la veille. PPI chaud après CPI chaud → USD renforcé ;
PPI froid après CPI froid → USD affaibli. Les Claims donnent le pouls emploi.
**Paire**: EUR/USD
**Entrée**: 15 min après publication, direction de la réaction
**Stop**: 1.0× ATR(14) H1
**TP**: 1.5× ATR(14) H1
**Risk**: 0.2%
**⚠️**: Ne trader que si le PPI dévie fortement du consensus — sinon skip.

### Setup 5: Retail Sales Friday (Ven 14 Aug, 08:30 ET)
**Thèse**: Retail Sales ⭐⭐⭐ — le dernier gros chiffre de la semaine. Confirme la vigueur du consommateur US
après le CPI. Un chiffre fort clôt la semaine en force USD.
**Paire**: USD/JPY (directionnelle) ou EUR/USD (liquide)
**Entrée**: 15-30 min après publication (08:45-09:00 ET)
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.3%
**⚠️**: Vendredi = fermeture à 11:00 ET, PAS de positions overnight week-end (triple swap).

---

## 📊 Allocation Suggérée

Compte OANDA paper: $50,000
Trade size max: 0.5% ($250 risque/trade)
Max positions ouvertes: 2

| Setup | Direction | Taille max | $ Risque | Paire |
|-------|-----------|------------|----------|-------|
| 1 - RBA | TBD mardi 00:30 | 0.3% | $150 | AUD/USD |
| 2 - CPI | TBD mercredi 08:45 | 0.5% | $250 | USD/JPY ou EUR/USD |
| 3 - UK GDP | TBD jeudi 02:00 | 0.2% | $100 | GBP/USD |
| 4 - PPI | TBD jeudi 08:30 | 0.2% | $100 | EUR/USD |
| 5 - Retail | TBD vendredi 08:45 | 0.3% | $150 | USD/JPY ou EUR/USD |

**Budget risque semaine : 1.5% max ($750)** — avec 5 setups à risque cumulé, ne pas tous les prendre.
**Règle : max 3 setups exécutés par semaine.**

---

## ⚠️ Règles Strictes

1. [ ] Max 2 positions ouvertes simultanément
2. [ ] Chaque trade est indépendant — ne pas "averager" une perdante
3. [ ] Si pas de signal clair → PAS DE TRADE
4. [ ] **Pendant le CPI (08:30-08:45 ET mercredi) : ne pas trader** — attendre la résolution du whipsaw
5. [ ] **Le vendredi**, fermeture à 11:00 ET — PAS de positions overnight week-end (triple swap)
6. [ ] Pas de positions overnight entre mardi soir et mercredi matin si position RBA ouverte (CPI imminent)
7. [ ] AUD/USD mardi : taille réduite de moitié (liquidité asiatique)

---

## 📝 Post-Mortem (à remplir après la semaine)

| Setup | Exécuté? | PnL | Leçon |
|-------|----------|-----|-------|
| 1 - RBA | | | |
| 2 - CPI | | | |
| 3 - UK GDP | | | |
| 4 - PPI | | | |
| 5 - Retail | | | |

---

## 🔄 Lien avec la recherche

- **Deep dive du jour (7 août)** : HMM Regime Momentum REJECT (2 variantes pré-fix re-testées avec coûts —
  11-12 trades/20 ans au seuil baseline, sweep complet 15 cellules × 4 paires sans plateau PF ≥ 1.2).
  Confirme que la semaine prochaine n'a pas d'edge codé exploitable, d'où ce playbook.
- **Note du 5 août** (Pattern saisonnier XAU/USD August REJECT + scan macro) : avait déjà identifié la CPI week
  du 10-14 août comme candidate playbook — confirmé par ForexFactory aujourd'hui.
- **Prochaine fenêtre saisonnière** : USD/JPY BUY Sep 27 (88% hit rate) — à surveiller pour une stratégie codée fin septembre.

*Généré par Simons Research — Vendredi 7 Août 2026, cron 7h*
