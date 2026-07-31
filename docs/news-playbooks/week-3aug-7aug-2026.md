# News Playbook — Semaine du 3 au 7 août 2026

> Généré: vendredi 31 juillet 2026
> Projet: trading-bridge
> Auteur: Simons Research

## ⚠️ Constat préliminaire

**Aucune edge systématique backtestable pour cette semaine — NFP week (⭐⭐⭐).**
Ce document sert de guide pour le paper trading manuel.

Raison du skip :
1. ✅ Aucune fenêtre saisonnière active (prochaine : USD/JPY Sep 27, 88% hit rate — dans 59 jours)
2. ✅ H1 systematic edge absente (PF ~0.9 après coûts — documenté, confirmé encore aujourd'hui par le deep dive TurnOfMonth)
3. ✅ Aucune stratégie codée ne couvre le NFP de façon validée post-fix (NfpWeekStrategy est pré-fix, suspecte)
4. ✅ Contexte : semaine après Triple Central Bank Week (FOMC + BOE + BOJ du 27-31 juillet) — digestion + NFP

**Contexte particulier :** C'est la première semaine complète après les 3 décisions CB simultanées.
Le marché digère les signaux multiples et le NFP d'août sera interprété à travers le prisme de ces décisions.

---

## 📅 Calendrier Macro (confirmé via ForexFactory)

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| Lun | 3 Aug | 🇨🇦 Bank Holiday (marché CA fermé) | USD/CAD | ⭐⭐ | All Day |
| Lun | 3 Aug | **US ISM Manufacturing PMI** | USD | **⭐⭐⭐** | **10:00 ET** |
| Lun | 3 Aug | US Final Manufacturing PMI | USD | ⭐⭐ | 09:45 ET |
| Mar | 4 Aug | US Trade Balance | USD | ⭐⭐ | 08:30 ET |
| Mar | 4 Aug | US JOLTS Job Openings | USD | ⭐⭐ | 10:00 ET |
| **Mar** | **4 Aug** | **🇳🇿 NZ Employment Change + Unemployment Rate** | **NZD/USD** | **⭐⭐⭐** | **18:45 ET** |
| Mer | 5 Aug | UK Final Services PMI | GBP | ⭐⭐ | 04:30 ET |
| Mer | 5 Aug | EZ PPI | EUR | ⭐⭐ | 05:00 ET |
| **Mer** | **5 Aug** | **US ADP Non-Farm Employment** | **USD** | **⭐⭐** | **08:15 ET** |
| **Mer** | **5 Aug** | **US ISM Services PMI** | **USD** | **⭐⭐⭐** | **10:00 ET** |
| Jeu | 6 Aug | US Challenger Job Cuts | USD | ⭐⭐ | 05:30 ET |
| **Jeu** | **6 Aug** | **US Unemployment Claims** | **USD** | **⭐⭐** | **08:30 ET** |
| **Ven** | **7 Aug** | **⚡ NFP + Unemployment Rate** | **USD toutes paires** | **⭐⭐⭐** | **08:30 ET** |

**⚠️ À noter :** Pas de banque centrale majeure cette semaine (FOMC/BOE/BOJ/ECB/RBA tous passés ou absents).
Le NFP du 7 août est **l'événement dominant** — semaine typique "employment week".

---

## 🏦 Contexte Macro

### Fed (décision du 29 juillet — semaine dernière)
- **Taux actuel**: 3.50-3.75% (contexte du playbook précédent — à confirmer avec le résultat réel)
- **Fed Chair**: Kevin Warsh (considéré hawkish)
- **Thesis**: Biais haussier USD si FOMC a confirmé le hawkishness. Le NFP d'août est le premier test post-décision.
  - NFP "hot" + FOMC hawkish → USD rallye confirmé
  - NFP "cold" + FOMC hawkish → sell-off USD (doute sur la crédibilité)

### BOE (décision du 30 juillet — semaine dernière)
- **Taux actuel**: 3.75% (contexte — à confirmer)
- **Impact GBP/USD**: dépend du ton. Le UK Services PMI de mercredi (04:30 ET) donnera un indice avant le NFP.

### BOJ (décision du 30-31 juillet — semaine dernière)
- **Taux**: En normalisation (zone basse/proche de zéro — contexte)
- **USD/JPY**: Très sensible aux surprises BOJ. Si BOJ a resserré → biais JPY fort toute la semaine.
- **⚠️ Le NFP + JPY = combo volatil.** Un NFP chaud avec un JPY hawkish peut produire des mouvements violents sur USD/JPY.

### NZD (mardi 18:45 ET)
- **Employment Change q/q + Unemployment Rate** — le seul événement ⭐⭐⭐ hors USD cette semaine
- Volatilité NZD/USD attendue mardi soir — fenêtre asiatique, liquidité réduite → spreads larges

### Fenêtres saisonnières
| Paire | Fenêtre | Hit Rate | Statut |
|-------|---------|:--------:|--------|
| USD/JPY | Sep 27 - Nov 11 (BUY) | 88% | ⏳ Dans 59 jours |
| Aucune | — | — | **Aucune fenêtre active cette semaine** |

---

## 📋 Paper Trading Setups

### Setup 1: ISM Manufacturing Pre-NFP Mood (Lun 3 Aug, 10:00 ET)
**Thèse**: L'ISM Manufacturing donne le "mood" avant le NFP. Un ISM < 50 (contraction) affaiblit le USD en début de semaine ; > 50 le renforce.
**Paire**: EUR/USD ou USD/JPY
**Entrée**: 15 min après publication, dans la direction de la réaction initiale
**Stop**: 1.0× ATR(14) H1
**TP**: 1.5× ATR(14) H1 (scalper macro — la semaine est longue)
**Risk**: 0.3%
**⚠️**: Le lundi est aussi un Bank Holiday canadien — USD/CAD à éviter (liquidité réduite).

### Setup 2: NZ Employment Night Trade (Mar 4 Aug, 18:45 ET)
**Thèse**: L'emploi néo-zélandais est un événement ⭐⭐⭐ rare. La réaction initiale de NZD/USD est souvent directionnelle.
**Paire**: NZD/USD
**Entrée**: 10 min après publication (18:55 ET), direction de la bougie réaction
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1 ou fermeture 23:00 ET
**Risk**: 0.3%
**⚠️**: Session asiatique = liquidité faible → taille réduite de moitié.

### Setup 3: ADP → NFP Correlation (Mer 5 Aug, 08:15 ET)
**Thèse**: L'ADP (secteur privé) donne le "preview" du NFP. Un ADP fort mercredi pré-positionne le marché pour un NFP fort vendredi.
**Paire**: USD/JPY
**Entrée**: 15 min après publication, direction ADP
**Stop**: 1.0× ATR(14) H1
**TP**: Fermeture jeudi 16:00 ET (pas de position overnight avant NFP)
**Risk**: 0.3%
**⚠️**: L'ADP n'est PAS un prédicteur fiable du NFP — c'est un trade de momentum court, pas un trade de conviction.

### Setup 4: NFP Friday Play (Ven 7 Aug, 08:30 ET) ⭐ LE SETUP PRINCIPAL
**Thèse**: Premier NFP après la Triple CB Week. La direction dépend du contexte FOMC (voir section Fed).
**Scénarios**:
- **FOMC hawkish + NFP hot** → BUY USD (EUR/USD short, USD/JPY long)
- **FOMC hawkish + NFP cold** → SELL USD (doute sur crédibilité hawkish)
- **FOMC dovish + NFP hot** → BUY USD fort (le marché repricie)
- **FOMC dovish + NFP cold** → SELL USD

**Paire**: USD/JPY (la plus directionnelle) OU EUR/USD (la plus liquide)
**Entrée**: Attendre **15-30 min** après 08:30 ET — laisser le whipsaw initial se résoudre
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5% max
**Fenêtre**: 08:45-10:30 ET uniquement. Aucune position laissée après 11:00 ET.

### Setup 5 (optionnel): Claims Confirmation (Jeu 6 Aug, 08:30 ET)
**Thèse**: Les Claims sont le dernier indicateur d'emploi avant le NFP. Un spike inattendu (> 250K) affaiblit le USD.
**Paire**: EUR/USD
**Entrée**: 15 min après publication, direction de la réaction
**Stop**: 1.0× ATR(14) H1
**TP**: 1.5× ATR(14) H1
**Risk**: 0.2% (taille réduite — setup faible conviction)
**⚠️**: Seulement si les Claims dévient fortement du consensus.

---

## 📊 Allocation Suggérée

Compte OANDA paper: $50,000
Trade size max: 0.5% ($250 risque/trade)
Max positions ouvertes: 2

| Setup | Direction | Taille max | $ Risque | Paire |
|-------|-----------|------------|----------|-------|
| 1 - ISM | TBD lundi 10:00 | 0.3% | $150 | EUR/USD |
| 2 - NZ | TBD mardi 18:45 | 0.3% | $150 | NZD/USD |
| 3 - ADP | TBD mercredi 08:15 | 0.3% | $150 | USD/JPY |
| 4 - NFP | TBD vendredi 08:45 | 0.5% | $250 | USD/JPY ou EUR/USD |
| 5 - Claims | TBD jeudi 08:30 | 0.2% | $100 | EUR/USD |

**Budget risque semaine : 1.6% max ($800)** — avec 5 setups à risque cumulé, ne pas tous les prendre.
**Règle : max 3 setups exécutés par semaine.**

---

## ⚠️ Règles Strictes

1. [ ] Max 2 positions ouvertes simultanément
2. [ ] Chaque trade est indépendant — ne pas "averager" une perdante
3. [ ] Si pas de signal clair → PAS DE TRADE
4. [ ] **Aucune position overnight entre jeudi et vendredi** — le NFP du vendredi matin est imprévisible, on ne dort pas avec une position macro
5. [ ] **Le vendredi**, fermeture à 11:00 ET — PAS de positions overnight week-end (triple swap)
6. [ ] Pendant le NFP (08:30-08:45 ET) : **ne pas trader** — attendre la résolution du whipsaw
7. [ ] USD/CAD à éviter lundi (Bank Holiday canadien)

---

## 📝 Post-Mortem (à remplir après la semaine)

| Setup | Exécuté? | PnL | Leçon |
|-------|----------|-----|-------|
| 1 - ISM | | | |
| 2 - NZ | | | |
| 3 - ADP | | | |
| 4 - NFP | | | |
| 5 - Claims | | | |

---

## 🔄 Lien avec la recherche

- **Deep dive du jour** : TurnOfMonthFlowStrategy rejetée (bug compteur + aucune edge month-end après coûts) — confirme que la semaine prochaine n'a pas d'edge codé exploitable, d'où ce playbook.
- **Note du 30 juillet** (Intermarket Analysis) : prévoyait déjà NFP vendredi 7 août ⭐⭐⭐ — confirmé par ForexFactory.
- **Prochaine fenêtre saisonnière** : USD/JPY BUY Sep 27 (88% hit rate) — à surveiller pour une stratégie codée fin septembre.

*Généré par Simons Research — Vendredi 31 Juillet 2026, 07:00 ET*
