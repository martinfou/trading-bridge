# News Playbook — Semaine du 17 au 21 août 2026

> Généré: vendredi 14 août 2026
> Projet: trading-bridge
> Auteur: Simons Research

## ⚠️ Constat préliminaire

**Aucune edge systématique backtestable pour cette semaine — FOMC Minutes + CPI UK + Employment AUD (⭐⭐⭐).**
Ce document sert de guide pour le paper trading manuel.

Raison du skip :
1. ✅ Aucune fenêtre saisonnière active (prochaine : USD/JPY BUY Sep 27-Nov 11, 88% ; USDCAD BUY Oct 12-Nov 26, 94%)
2. ✅ H1 systematic edge absente (PF ~0.9 après coûts — documenté ; re-baseline moteur fees-swaps du 14 août : EmaPullback EUR 0.98 / GBP 1.13 inchangés, swap ≈ 0 sur les trades intra-journée)
3. ✅ Aucune stratégie codée validée ne couvre FOMC Minutes (stratégies news du catalogue = pré-fix, suspectes)
4. ✅ Contexte : semaine CPI (10-14 août) derrière nous, la prochaine semaine est dominée par les **minutes du FOMC du 29 juillet — les premières sous Kevin Warsh (hawkish)**

**Contexte particulier :** Les FOMC Minutes de mercredi 14:00 ET sont l'événement le plus important de la semaine.
Elles révèleront le débat interne autour de la décision du 29 juillet (maintien 3.50-3.75% ? hausse ?) et le
degré d'engagement hawkish de Warsh. Les minutes sont historiquement un catalyseur de volatilité USD quand
elles contiennent une surprise sur le ton (dovish surprise = USD sell-off, hawkish confirmé = USD rally).
Attention : la publication des minutes est décalée de 3 semaines — le marché a déjà digéré le NFP (7 août) et
le CPI (12 août). Le risque est un marché déjà positionné vs un texte qui reflète des données de fin juillet.

---

## 📅 Calendrier Macro (confirmé via ForexFactory)

| Jour | Date | Événement | Paire | Impact | Heure (ET) |
|------|------|-----------|-------|--------|------------|
| **Lun** | **17 Aug** | **🇨🇦 CPI m/m + Median/Trimmed CPI y/y** | **USD/CAD** | **⭐⭐⭐** | **08:30** |
| **Mar** | **18 Aug** | **🇬🇧 Claimant Count Change + Average Earnings 3m/y** | **GBP/USD** | **⭐⭐/⭐⭐⭐** | **02:00** |
| Mar | 18 Aug | 🇦🇺 RBA Deputy Gov Hauser Speaks | AUD | ⭐ | 21:00 |
| **Mer** | **19 Aug** | **🇬🇧 CPI y/y + Core CPI y/y** | **GBP/USD** | **⭐⭐⭐** | **02:00** |
| **Mer** | **19 Aug** | **🇺🇸 FOMC Meeting Minutes** | **USD toutes** | **⭐⭐⭐** | **14:00** |
| **Mer** | **19 Aug** | **🇦🇺 Employment Change + Unemployment Rate** | **AUD/USD** | **⭐⭐⭐** | **21:30** |
| **Jeu** | **20 Aug** | **🇺🇸 Unemployment Claims + Philly Fed** | **USD** | **⭐⭐** | **08:30** |
| Ven | 21 Aug | 🇬🇧 Retail Sales m/m | GBP/USD | ⭐⭐ | 02:00 |
| Ven | 21 Aug | 🇪🇺 Flash PMIs (FR/DE/EU/UK) | EUR/GBP | ⭐⭐ | 03:15-04:30 |

**⚠️ À noter :** Pas de décision de taux cette semaine. Les 3 événements dominants :
1. **CPI CAD lundi 08:30** — la BoC est en cycle de baisse (taux 2.25% ?) ; un CPI chaud retarde les baisses → CAD rally
2. **FOMC Minutes mercredi 14:00** — LE catalyseur USD de la semaine (premières minutes Warsh)
3. **Employment AUD mercredi 21:30** — la RBA a maintenu à 4.35% mardi dernier ; un emploi fort → AUD rally

---

## 🏦 Contexte Macro

### Fed (minutes du FOMC du 29 juillet — mercredi 19 août, 14:00 ET)
- **Taux actuel**: 3.50-3.75% (décision du 29 juillet)
- **Fed Chair**: Kevin Warsh (considéré hawkish)
- **Thesis**: Les minutes sont le premier texte officiel détaillé sous Warsh. Le marché cherche :
  - La répartition des voix (combien de membres ont voté pour une hausse ?)
  - Le langage sur la trajectoire de taux (encore restrictif ? data-dependent ?)
  - Les discussions sur le bilan / la neutralité
- **Scénarios** :
  - Minutes hawkish (débat sur hausse, ton ferme) → USD rally (EUR/USD ↓, USD/JPY ↑)
  - Minutes dovish surprise (membres prudents sur l'inflation, risques bilatéraux) → USD sell-off
  - Minutes in-line → réaction faible, le marché revient au récit CPI de la semaine passée
- **⚠️ Les données post-réunion (NFP 7 août, CPI 12 août) ne sont PAS dans les minutes** — le marché peut réagir au décalage.

### BoC (CPI lundi 17 août, 08:30 ET)
- **Taux actuel**: en cycle de baisse (la BoC a baissé plusieurs fois en 2025-26)
- **Thesis**: CPI chaud (≥ 0.3% m/m) → le marché retire des baisses → **USD/CAD ↓** (CAD rally).
  CPI froid → USD/CAD ↑. Les mesures Median/Trimmed (préférées de la BoC) sont aussi importantes.

### RBA (Employment mercredi 19 août, 21:30 ET)
- **Taux actuel**: 4.35% (maintien le 11 août)
- **Thesis**: Employment Change fort (> 30K) + Unemployment Rate en baisse → AUD rally (AUD/USD ↑).
  Le WPI q/q (mardi 21:00, medium) donne un avant-goût du marché du travail.
- **⚠️ Fenêtre 21:30 ET = liquidité faible** → spreads larges, taille réduite.

### BOE (jobs mardi 02:00 + CPI mercredi 02:00)
- **Taux actuel**: 3.75% (décision du 30 juillet)
- **Thesis**: Le Claimant Count (mardi) et le CPI (mercredi) donnent le ton pré-réunion.
  Un CPI UK chaud (≥ 3% y/y ?) → GBP rally ; un CPI froid → GBP/USD ↓.
- **Séquence dangereuse** : CPI UK mercredi 02:00 → FOMC Minutes mercredi 14:00 → Employment AUD 21:30.
  Trois événements majeurs dans la même journée = volatilité multi-devises.

### Fenêtres saisonnières
| Paire | Fenêtre | Hit Rate | Statut |
|-------|---------|:--------:|--------|
| USD/JPY | Sep 27 - Nov 11 (BUY) | 88% | ⏳ Dans 44 jours |
| USDCAD | Oct 12 - Nov 26 (BUY) | 94% | ⏳ Dans 59 jours |
| Aucune | — | — | **Aucune fenêtre active cette semaine** |

---

## 📋 Paper Trading Setups

### Setup 1: CAD CPI Monday (Lun 17 Aug, 08:30 ET)
**Thèse**: CPI ⭐⭐⭐ en début de semaine — direction selon le chiffre vs consensus.
**Scénarios**:
- **CPI hot (≥ 0.3% m/m)** → BUY CAD (USD/CAD short)
- **CPI cold** → SELL CAD (USD/CAD long)
**Paire**: USD/CAD
**Entrée**: 15-30 min après 08:30 ET (laisser le whipsaw se résoudre)
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.4%
**Fenêtre**: 08:45-10:30 ET uniquement

### Setup 2: UK CPI + FOMC Minutes Wednesday (Mer 19 Aug) ⭐ LE SETUP PRINCIPAL
**Thèse**: Double événement sur GBP/USD — CPI UK à 02:00 ET puis FOMC Minutes à 14:00 ET.
Le matin GBP est piloté par le CPI UK, l'après-midi par le USD.
**Scénarios FOMC Minutes (14:00 ET)**:
- **Hawkish surprise** → BUY USD (EUR/USD short, USD/JPY long)
- **Dovish surprise** → SELL USD (EUR/USD long, USD/JPY short)
**Paire**: USD/JPY (la plus directionnelle) OU EUR/USD (la plus liquide)
**Entrée**: Attendre **20-30 min** après 14:00 ET — les minutes créent souvent un double mouvement (spike puis correction)
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.5% max
**Fenêtre**: 14:20-16:00 ET uniquement. Aucune position laissée après 16:30 ET.

### Setup 3: AUD Employment Night (Mer 19 Aug, 21:30 ET)
**Thèse**: Employment ⭐⭐⭐ en fenêtre asiatique. Réaction initiale directionnelle.
**Paire**: AUD/USD
**Entrée**: 10-15 min après publication, direction de la bougie réaction
**Stop**: 1.5× ATR(14) H1
**TP**: 2.0× ATR(14) H1
**Risk**: 0.3%
**⚠️**: Session asiatique = liquidité faible → taille réduite de moitié. Ne pas overnight (FOMC déjà passé mais risque de gap).

### Setup 4: Claims + Philly Fed Thursday (Jeu 20 Aug, 08:30 ET)
**Thèse**: Claims ⭐⭐ et Philly Fed ⭐⭐ — confirmation du récit FOMC de la veille.
**Paire**: EUR/USD
**Entrée**: 15 min après publication, direction de la réaction
**Stop**: 1.0× ATR(14) H1
**TP**: 1.5× ATR(14) H1
**Risk**: 0.2%
**⚠️**: Ne trader que si les chiffres dévient fortement du consensus — sinon skip.

---

## 📊 Allocation Suggérée

Compte OANDA paper: $50,000
Trade size max: 0.5% ($250 risque/trade)
Max positions ouvertes: 2

| Setup | Direction | Taille max | $ Risque | Paire |
|-------|-----------|------------|----------|-------|
| 1 - CAD CPI | TBD lundi 08:45 | 0.4% | $200 | USD/CAD |
| 2 - FOMC Minutes | TBD mercredi 14:20 | 0.5% | $250 | USD/JPY ou EUR/USD |
| 3 - AUD Employment | TBD mercredi 21:40 | 0.3% | $150 | AUD/USD |
| 4 - Claims/Philly | TBD jeudi 08:45 | 0.2% | $100 | EUR/USD |

**Budget risque semaine : 1.4% max ($700)** — avec 4 setups à risque cumulé, ne pas tous les prendre.
**Règle : max 2-3 setups exécutés par semaine.**

---

## ⚠️ Règles Strictes

1. [ ] Max 2 positions ouvertes simultanément
2. [ ] Chaque trade est indépendant — ne pas "averager" une perdante
3. [ ] Si pas de signal clair → PAS DE TRADE
4. [ ] **Pendant les publications (08:30/14:00/21:30 ET) : ne pas trader pendant les 15-30 premières minutes** — attendre la résolution du whipsaw
5. [ ] **Le vendredi**, fermeture à 11:00 ET — PAS de positions overnight week-end (triple swap)
6. [ ] Pas de positions overnight entre mardi soir et mercredi matin (CPI UK + FOMC imminent)
7. [ ] Mercredi : pas de position GBP/USD après 10:00 ET si FOMC Minutes en approche (risque de double exposition)
8. [ ] AUD/USD mercredi nuit : taille réduite de moitié (liquidité asiatique)

---

## 📝 Post-Mortem (à remplir après la semaine)

| Setup | Exécuté? | PnL | Leçon |
|-------|----------|-----|-------|
| 1 - CAD CPI | | | |
| 2 - FOMC Minutes | | | |
| 3 - AUD Employment | | | |
| 4 - Claims/Philly | | | |

---

## 🔄 Lien avec la recherche

- **Deep dive du jour (14 août)** : Re-baseline moteur fees-swaps corrigé (39.1 coûts par défaut non-zéro + 39.2 swap sur sorties SL/TP/force-close). Résultat : EmaPullbackContinuation (seul PF ~1.05 post-fix look-ahead) reste à EUR 0.98 / GBP 1.13 — les trades sont quasi tous intra-journée (swap ≈ 0), le fix ne change rien à l'empirical reality H1. La macro semaine reste le seul edge exploitable → ce playbook.
- **Note du 12 août** (August curse REJECT) : le pattern août baissier est réel mais faible, tué par le swap modèle constant. La brique manquante = swaps dynamiques par année (Epic 40.2).
- **Prochaines fenêtres saisonnières** : USD/JPY BUY Sep 27 (88%) et USDCAD BUY Oct 12 (94%) — à surveiller pour des stratégies codées fin septembre.

*Généré par Simons Research — Vendredi 14 Août 2026, cron 7h*
