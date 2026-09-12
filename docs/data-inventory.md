# Inventaire des données — Trading Bridge

> **Créé le 2026-09-12** (revue hebdo Simons, semaine 7-11 sept).
> **Pourquoi ce fichier :** le pipeline de recherche a répété pendant **4 semaines** (13 août → 10 sept) que
> « un vrai lead-lag cross-asset exigerait le S&P 500 (données absentes du repo) ». C'était **faux** :
> `data/historical/futures/MES_D1.csv` existe depuis le début. Idem pour la piste « XAG si données »,
> relancée plusieurs fois alors que l'argent n'a **jamais** été présent.
> **Règle : avant de conclure qu'un actif manque, exécuter les commandes de vérification ci-dessous.**

## Comment vérifier (2 commandes)

```bash
# 1. Les symboles FX / métaux disponibles (fichiers .bars = binaire Dukascopy, d'où l'inventaire par nom de fichier)
ls data/historical/bars/ | sed -E 's/_(H1|D1|M1).*//' | sort -u

# 2. Les séries hors FX (CSV texte) + leur couverture
for f in data/historical/futures/* data/external/*; do
  printf "%-45s %6s lignes | %s -> %s\n" "$f" "$(wc -l < "$f")" \
    "$(sed -n 2p "$f" | cut -d, -f1)" "$(tail -1 "$f" | cut -d, -f1)"
done
```

⚠️ **Les fichiers `.bars` sont BINAIRES** (format Dukascopy) — `head`/`tail`/`grep` n'y donnent rien d'exploitable.
La **couverture** se lit sur les **noms de fichiers annuels** (`XAU_USD_H1_2025.bars`), pas sur le contenu.
Pour les lire en Java, passer par le lecteur de barres du module `trading-data`.

## 1. FX & métaux précious — `data/historical/bars/` (H1, binaire)

| Symbole | Fichiers annuels | Couverture | Notes |
|---------|-----------------|------------|-------|
| `AUD_USD` | 2006 → 2026 | 2006 → 2026 | + M1 2024-2025 |
| `EUR_USD` | 2006 → 2026 | 2006 → 2026 | + M1 2024-2025 |
| `GBP_USD` | 2006 → 2026 | 2006 → 2026 | + M1 |
| `NZD_USD` | 2006 → 2026 | 2006 → 2026 | + M1 |
| `USD_CAD` | 2006 → 2026 | 2006 → 2026 | + M1 |
| `USD_CHF` | 2006 → 2026 | 2006 → 2026 | + M1 |
| `USD_JPY` | 2006 → 2026 | 2006 → 2026 | + M1 |
| `XAU_USD` | 2006 → **2025** | 2006 → **16 mai 2025** | ⚠️ **pas de fichier 2026** — toute recherche or post-mai-2025 est impossible |
| `XAG_USD` (argent) | — | **ABSENT** | ❌ **aucun fichier** — la piste « XAG si données » est **définitivement fermée** |

Variantes sans underscore également présentes : `EURUSD`, `GBPJPY` (héritage — préférer la forme `XXX_YYY`).

⚠️ **Les `.bars` sont CONTINUS 24/7 avec barres PLATES de carry le week-end** (samedi 100 % plat, dimanche
reprise 21-23h UTC). Toute étude intraday / horaire / de gap **DOIT filtrer les barres réelles**
(`close != close précédent`), sinon elle travaille sur des zéros artificiels. Découvert le 7 sept 2026.

⚠️ **Le moteur de backtest saute samedi/dimanche.** Conséquence : une position ouverte « vendredi »
est en réalité fermée **lundi 01:00** (ex. `GoldWeekdayEffect` — 43 % du net documenté était une jambe
week-end). Toujours vérifier le timestamp de sortie réel par un probe. Découvert le 8 sept 2026.

## 2. Indices actions — `data/historical/futures/` (CSV texte)

| Fichier | Instrument | Lignes | Couverture | Notes |
|---------|-----------|--------|------------|-------|
| `MES_D1.csv` | S&P 500 (micro E-mini) | 5 198 | **2006-01-03 → 2026-08-21** | ✅ exploitable en backtest |
| `MNQ_D1.csv` | Nasdaq 100 (micro) | 5 198 | **2006-01-03 → 2026-08-21** | ✅ exploitable en backtest |
| `M2K_D1.csv` | Russell 2000 (micro) | 2 296 | 2017-07-10 → 2026-08-21 | ⚠️ 9 ans seulement |
| `MES_H1.csv` | S&P 500 | 13 697 | 2024-04-01 → 2026-08-21 | ⚠️ ~2,4 ans — **trop court** pour un backtest aux standards du pipeline |
| `MNQ_H1.csv` | Nasdaq 100 | 13 694 | 2024-04-01 → 2026-08-21 | ⚠️ idem |
| `M2K_H1.csv` | Russell 2000 | 13 720 | 2024-04-01 → 2026-08-21 | ⚠️ idem |
| `EMD_D1.csv` | S&P MidCap 400 | **1** | — | ❌ **FICHIER CASSÉ** (1 seule ligne de données) |

**Statut de recherche :** l'univers indices D1 a été **testé et REJECTÉ** le 11 sept 2026
(mécanique Turtle 55/20 : S&P PF 1.03, Nasdaq 1.08 avec DD 31 %, Russell 1.20 sur 9 ans ; sweep sans
plateau ; régime bull calme négatif). Raison mécanique : la **signature LONG/SHORT** — S&P LONG +$6 277 /
SHORT −$5 726, Nasdaq +$60 262 / −$53 603 → le net est un **résidu de cancellation** (les indices ont des
reprises en V qui tuent les breakouts short, contrairement à l'or).
**Ne pas re-tester « Turtle sur indices actions ».**

## 3. Énergie — `data/external/`

| Fichier | Instrument | Lignes | Couverture | Notes |
|---------|-----------|--------|------------|-------|
| `WTISPLC.csv` | WTI spot (St. Louis Fed) | **246** | 2006-01 → **2026-06** | ⚠️ **SÉRIE MENSUELLE**, pas quotidienne — `observation_date` = 1er du mois |

⚠️ **`WTISPLC.csv` n'est PAS exploitable pour un breakout Turtle D1/H1** (246 points = 246 mois).
Seules des études à l'échelle **mensuelle** sont envisageables : momentum mensuel, saisonnalité,
turn-of-month, tendance longue. Découvert le 12 sept 2026 — la piste « WTI = prochain univers candidat
pour un turtle » doit être reformulée en conséquence.

## 4. Ce dont le repo NE dispose PAS

- **Argent (`XAG`)** — aucun fichier, sous aucune forme. Piste fermée définitivement.
- **Obligations / taux (UST 10Y)** — absents.
- **Actions individuelles** — absentes (uniquement indices futures).
- **Séries WTI quotidiennes ou horaires** — absentes (mensuel seulement).
- **Swaps historiques par année** — `SwapCalculator.SWAP_RATES` = **taux constants 2024-2026** appliqués
  sur toute la période 2006-2026. C'est un artefact sur longue période (il crédite +3.8 pips/jour de carry
  AUD/USD en 2020-2022 alors que le carry réel y était nul). **Brique manquante structurelle** — tout edge
  dont le net dépend du swap sur les paires AUD/NZD est inévaluable en l'état.

## 5. Pièges de couverture connus

| Piège | Détail | Découvert |
|-------|--------|-----------|
| `XAU_USD` annoncé « 2006-2025 » | signifie en réalité **2006 → 16 mai 2025** | 10 sept 2026 |
| `EMD_D1.csv` « cassé (1 ligne) » | 1 seule ligne de données (2 lignes au total) | 10 sept 2026 |
| `.bars` 24/7 + carry plat | filtrage des barres réelles obligatoire en intraday | 7 sept 2026 |
| Moteur saute sam/dim | les positions « vendredi » sortent lundi 01:00 | 8 sept 2026 |
| Clé `SWAP_RATES` = `USDCAD` sans underscore | ≠ symbole moteur `USD_CAD` → swap = 0 involontaire | 24 août 2026 |
| Clé `SeasonalityFilter` = `USDCAD` | même bug de normalisation → filtre silencieusement inactif | 31 juil 2026 |

## 6. Règle de conduite

1. **Avant d'annoncer qu'un actif manque** → lancer les 2 commandes du §« Comment vérifier ».
2. **Avant d'annoncer une plage de dates** → vérifier la couverture réelle (fichiers annuels pour `.bars`,
   première/dernière ligne pour les CSV).
3. **Avant de réutiliser une piste « à tester »** → relire cet inventaire : la moitié des pistes ouvertes
   dans les revues hebdo sont fermées par simple absence de données.
