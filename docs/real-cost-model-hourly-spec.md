# Spec : `RealCostModel` sensible à l'heure ET (demi-spread par symbole × heure de fill)

> **Statut :** spec, **pas de code**. Décision de Martin du 2026-10-23 : « spec le samedi, code le lundi ».
> **Auteur :** Simons (recherche) : 2026-10-24
> **Origine :** 65e (2026-10-23), section E : le spread a une structure horaire d'un facteur 13 et
> `RealCostModel` applique une **constante** par symbole.
> **Mesure de référence :** `reports/2026-10-24-spread-by-hour.txt` (harnais `scripts/spread-by-hour.py`,
> données `data/oanda-ba/*_H1_BA.csv`).

---

## 1. Le défaut à corriger, en une phrase

`RealCostModel.halfSpread(symbol)` rend **un nombre par symbole** ; le broker facture un spread qui dépend de
**l'heure de la journée** (facteur mesuré jusqu'à **5,2×** entre deux heures ET) et **du jour** (la barre de
clôture du vendredi vaut 1,6 à 3,6× la même heure un autre jour). Toute jambe qui entre ou sort dans
`[17:00, 18:00) ET` est donc **sous-costée**, et toute jambe en `[08,12) ET` est **sur-costée** d'environ 5 %.

Ce n'est pas une opinion : c'est le 4e membre de la famille « le modèle de coût fabrique le verdict », après
la table de swap 2024-26 appliquée à 20 ans (47e), le spread du cross (55e) et le compteur de jours de
rollover (56e).

---

## 2. Ce qui est mesuré (et comment)

**Source :** OANDA v20, `granularity=H1`, `price=BA` (bid **et** ask), 2021-01-04 → 2026-10-23, **7 symboles**,
~34 000 barres chacun. Régénérable par :

```bash
cd ~/projects/tb-research
python3 scripts/hourly-spread-fetch.py      # H1 BA 2021 -> now, 7 symboles (~5 min)
python3 scripts/spread-by-hour.py           # profil horaire, contrôles P0 + WF (~20 s)
```

**Définition retenue :** `spread_bp(t) = (ask_close(t) − bid_close(t)) / mid_close(t) × 10 000`,
médiane par heure, l'heure étant celle de la **FIN** de la barre en `America/New_York` (le label OANDA est le
**début** : c'est exactement l'erreur d'une heure du 64e). Mon-Ven seulement ; barres `ask ≤ bid` jetées.

### 2.1 Le niveau des constantes actuelles est BON : c'est leur *structure* qui manque

| Symbole | Médiane mesurée (plein spread) | Constante `RealCostModel` (plein) | Mesure / constante |
|---|---|---|---|
| EUR_USD | 1,38 bp | 1,42 bp | **0,97×** |
| GBP_JPY | 1,67 bp | 1,63 bp | **1,02×** |
| USD_JPY | 1,18 bp | 1,01 bp | **1,16×** |
| AUD_USD | 1,95 bp | 1,88 bp | **1,04×** |
| USD_CHF | 1,78 bp | 1,81 bp | **0,99×** |
| GBP_USD | 1,42 bp | *(absent de `HALF_SPREAD_PRICE`)* | : |
| XAU_USD | 1,58 bp | 1,27 bp | **1,24× (sous-costé)** |

**Conclusion partielle :** le niveau de la mesure du 1er octobre 2026 est confirmé à ±16 % sur 5 ans et demi.
Le modèle n'a pas un problème de **niveau**, il a un problème de **structure**. Corollaire : ne pas « ajuster
les constantes » : ça ne réparerait rien. Et **GBP_USD manque** dans `HALF_SPREAD_PRICE` alors qu'il est
mesuré ici et utilisé par les harnais : à ajouter (demi = 0,71 bp/legue).

### 2.2 Multiplicateur par heure ET du close de barre (médiane de l'heure / médiane du symbole)

`1.00` = la constante est juste. `2.09` = la jambe coûte 2,09× ce que le modèle facture.

| Tranche ET (intervalle de la barre) | EUR_USD | GBP_JPY | USD_JPY | AUD_USD | USD_CHF | GBP_USD | XAU_USD |
|---|---|---|---|---|---|---|---|
| `[00,07)` | 1,00 | 0,98 | 0,98 | 0,99 | 0,99 | 0,99 | 0,97 |
| `[07,08)` | 0,98 | 0,95 | 0,97 | 0,96 | 0,97 | 0,95 | 0,97 |
| `[08,12)` | 0,98 | 0,96 | 0,97 | 0,97 | 0,96 | 0,95 | 1,00 |
| `[12,14)` | 0,99 | 0,95 | 0,97 | 0,99 | 0,97 | 0,97 | 0,97 |
| `[14,16)` | 0,99 | 0,96 | 0,97 | 0,99 | 0,97 | 0,97 | 0,97 |
| `[16,17)` : dernière barre du jour | 1,22 | 2,03 | 1,53 | 1,36 | 1,57 | 1,55 | 1,45 |
| ↳ *dont VENDREDI uniquement* | **1,85** | **5,08** | **3,93** | **4,42** | **3,20** | **4,76** | **2,24** |
| ↳ *dont lundi-jeudi* | ~1,0 | ~0,98 | ~1,0 | ~1,0 | ~1,0 | ~1,0 | ~1,0 |
| **`[17,18)` : après le halt 17:00** | **2,09** | **4,33** | **2,80** | **2,14** | **5,21** | **3,14** | *(aucune barre)* |
| `[18,19)` | 1,06 | 1,15 | 1,09 | 1,07 | 1,13 | 1,15 | 1,10 |
| `[19,24)` | 1,00 | 1,04 | 1,01 | 1,01 | 1,03 | 1,03 | 1,02 |

Deux populations distinctes, et il faut les traiter séparément :

1. **`[17:00, 18:00) ET`** : la première barre après le halt quotidien de 17:00 ET : médiane 2,1 à 5,2×.
   C'est le rollover (62e). **L'or n'a aucune barre dans cette tranche** (n = 0 sur 5 ans) : son halt est
   plein : ne pas lui inventer un multiplicateur.
2. **La dernière barre du vendredi, `[16,17) ET`** : elle ferme la semaine : médiane **2,55 / 8,49 / 4,64 /
   8,62 / 5,69 / 6,76 / 3,54 bp** (EUR / GBP_JPY / USD_JPY / AUD / USD_CHF / GBP_USD / XAU) contre
   **1,60 / 2,91 / 1,67 / 2,50 / 2,57 / 1,90 / 2,17** la même heure lundi-jeudi. Ce n'est pas un
   multiplicateur d'heure, c'est **un multiplicateur de jour** : le modèle doit lire `(symbole, jour, heure)`,
   pas `(symbole, heure)`. La ligne `[16,17)` du tableau est d'ailleurs la moyenne de ces deux populations
   (1/5 de vendredis suffit à la porter de ~1,0 à 1,2-2,0) : d'où l'intérêt de la décomposer.

### 2.3 Stabilité (obligatoire : un multiplicateur instable ne se livre pas)

Multiplicateur recalculé séparément sur 2021-2023 (IS) et 2024-2026 (OOS), 7 symboles :

- `[17,18) ET` : **stable** partout (EUR 2,46 → 1,78 ; GBP_JPY 4,11 → 4,62 ; USD_JPY 2,70 → 2,97 ;
  AUD 2,39 → 1,88 ; USD_CHF 6,04 → 4,50 ; GBP_USD 3,06 → 3,41). Le signe et l'ordre de grandeur tiennent.
- `[16,17) ET` : stable sur 6 symboles sur 7 (EUR 1,32 → 1,14 ; GBP_JPY 1,95 → 2,10 ; USD_JPY 1,53 → 1,54 ;
  AUD 1,42 → 1,31 ; USD_CHF 1,59 → 1,55 ; XAU 1,52 → 1,37).
- **Seule exception : GBP_USD `[16,17)` 2,61 → 1,30** (÷2). Le multiplicateur GBP_USD de cette tranche est
  **instable** : le livrer avec la valeur pooled mais **marqué `UNSTABLE`** dans la table, et ne jamais
  laisser une stratégie survivre UNIQUEMENT grâce à lui (c'est le critère du 45e : un conditionnement dont
  un seul symbole porte le gain est un choix de variable, pas un phénomène).
- Les tranches `[00,16)` sont stables et **sous** 1,00 partout (0,89-1,06) : le modèle sur-coste de 2-5 % les
  stratégies intraday de Londres, et sous-coste de 0 à 11 % la nuit. C'est petit : mais c'est du bon sens
  contraire (un modèle d'exécution ne doit pas être optimiste), donc pas un motif d'exception.

---

## 3. Ce qu'il faut construire

### 3.1 API (dans `trading-backtest`, à côté de `RealCostModel`)

```java
/** Multiplicateur de spread pour un instant de FILL. Retourne 1.0 si l'instant n'est pas couvert. */
public interface SpreadSchedule {
    double multiplierAt(String symbol, java.time.Instant fillTime);
    boolean covers(String symbol);
}

public final class HourlySpreadSchedule implements SpreadSchedule { /* table du §2.2 */ }
```

Puis dans `RealCostModel` :

```java
public static double halfSpread(String symbol, java.time.Instant fillTime)   // nouvelle surcharge
public static BacktestExecutionCost costFor(String symbol, double refPrice)  // INCHANGÉ (compat)
```

Règles non négociables :

1. **Pas de repli silencieux.** Symbole non mesuré → `IllegalArgumentException` (comportement actuel de
   `halfSpread`, à conserver). Instant non couvert (week-end, trou de données) → multiplicateur `1.0` **et un
   compteur** `scheduleFallbacks` exposé dans le résultat : un fallback qui ne se voit pas est un bug.
2. **L'heure est lue en `America/New_York`**, jamais en UTC (63e : une mesure à heure UTC fixe fabrique un
   faux « hiver vs été » ; 59e : la clôture suit l'heure d'été).
3. **La constante reste la référence de niveau** : `halfSpread(sym, t) = halfSpread(sym) × m(sym, t)`. On
   corrige la structure sans jeter la mesure du 1er octobre.
4. **Le dimanche soir et le samedi n'ont pas de barres** : le moteur les saute déjà ; ne rien ajouter.

### 3.2 Point d'insertion dans le moteur : le vrai travail

`BacktestEngine` porte **un seul scalaire** par instance (`slippageFixed`, `slippagePct`) et l'applique dans
`applySlippage(String symbol, double price, Order.Side side, double quantity)`
(`trading-backtest/.../BacktestEngine.java:649`) : **la méthode n'a pas d'instant**. Trois conséquences :

- ajouter un paramètre `Instant fillTime` à `applySlippage` et le propager depuis les **deux** sites d'appel
  (entrée ~l.402-415, sortie ~l.518-530) ;
- `slippagePct` (slippage de SL) est calculé à partir du `refPrice` du 1er octobre : il devient
  `half(sym, t) / price(t)`, sinon le SL garde silencieusement l'ancien modèle ;
- `BacktestExecutionCost` est un `record` de 5 scalaires : la voie la moins invasive est **`SpreadSchedule`
  optionnel sur `BacktestEngine`** (`withSpreadSchedule(...)`), `slippageFixed` restant le repli quand il est
  absent. Ne pas réécrire `BacktestExecutionCost`.

### 3.3 Un seul chiffre à publier à côté de chaque résultat

`costMultiplierMoyen = mean over fills of ( m(entrée) + m(sortie) ) / 2`.

C'est ce chiffre qui rend la correction vérifiable, et c'est lui qui manque partout aujourd'hui.

---

## 4. Effet attendu sur les 5 stratégies déployées

Audit de référence : `docs/deployed-set-audit-2026-10-16.md` (PF réel FULL/IS/OOS1/OOS2 ; **le spread vaut
93 à 100 % du coût** pour consecbar et ltrsi3, donc ce sont ces deux-là que le modèle horaire déplace).

| Stratégie | Symbole | Coût = spread | Remplissages observés dans la fenêtre paper | Multiplicateur du modèle |
|---|---|---|---|---|
| consecbar | GBP_JPY | 93 % | entrée **2026-10-22 17:00:53 UTC = 13:00 ET** ; entrée **2026-10-23 21:00:57 UTC = 17:00 ET** | 0,95× puis **4,33×** |
| vwpreversion | USD_CHF | : | entrée **2026-10-23 14:00:56 UTC = 10:00 ET** | 1,02× |
| ltrsi3 | EUR_USD | 100 % | entrées à 00:00 / 07:00 / 12:00 UTC = 20:00 / 03:00 / 08:00 ET | 1,00 / 1,00 / 0,99× |
| monthweekphase | USD_JPY | : | aucun fill | à mesurer |
| compmomentum | USD_JPY | : | aucun fill | à mesurer |

**Pronostic falsifiable, à vérifier lundi :**

- **ltrsi3 et vwpreversion ne bougent pas** (|ΔPF| < 0,03) : toutes leurs jambes tombent en `[19,24)` ou
  `[08,12)` ET, où le multiplicateur vaut 0,98-1,02. Si l'une des deux se met à bouger de 10 %, **c'est le
  câblage qui est faux**, pas le marché.
- **consecbar est la seule exposée** : une jambe sur deux dans la fenêtre est tombée à **17:00 ET**, où
  GBP_JPY paie **4,33×**. Son PF 0,80 (FULL) ne peut que **baisser** ; l'ampleur dépend de la part de ses
  fills en `[17,18)` ET, qui est précisément ce que lundi doit publier.
- **Aucune des 5 ne peut passer de FAIL à PASS** : leurs déficits sont de 20 à 35 points de PF, la correction
  déplace le coût de ±2 % à +330 % **dans le mauvais sens**. Le modèle horaire est une correction de
  **justesse**, pas un sauvetage. Si un PASS apparaît, il vient d'un bug.

---

## 5. Critères d'acceptation (vérifiables, dans cet ordre)

1. **Test unitaire de la table.** `HourlySpreadSchedule.multiplierAt` rend, à 0,02 près, les valeurs du §2.2
   pour les 7 symboles aux instants 10:00, 16:30 et 17:30 ET. Un test nomme explicitement le cas
   `XAU_USD @ 17:30 ET` → **1,0 avec `covers=false`** (la tranche n'existe pas pour l'or).
2. **Test DST.** Un même instant UTC (21:00) tombe en `[16,17)` ET en juillet et en `[17,18)` ET en janvier :
   le test asserte que les multiplicateurs **diffèrent** sur ces deux dates. C'est le test qui empêche la
   réintroduction du piège du 59e.
3. **Le câblage mord (test de non-inactivité).** Une stratégie de contrôle qui n'entre qu'en `[08,12)` ET voit
   son net bouger de **< 5 %** ; une stratégie qui n'entre qu'en `[17,18)` ET voit son coût **au moins
   doubler**. Deux nets identiques au centime = le schedule n'est pas branché (pitfall « un filtre
   silencieusement inactif »).
4. **Publication par stratégie.** `RunCostModelReal` imprime, par stratégie : l'histogramme des heures de fill
   (24 cases) et `costMultiplierMoyen`. Contrôle : sur un échantillon de 10 fills, le multiplicateur
   recalculé à la main depuis les timestamps doit tomber à **5 %** de celui du modèle.
5. **Re-cotage du set déployé.** PF FULL / IS / OOS1 / OOS2 avant/après pour les 5 stratégies, avec la
   colonne `costMultiplierMoyen`. Aucun changement de verdict non expliqué par le signe du multiplicateur.
6. **Aucune régression** sur les 49 rapports `wfa-*.json` existants : relancer 3 d'entre eux et vérifier que
   le PF ne bouge **que** si leurs fills tombent dans les tranches ≠ 1,00.

---

## 6. Limites assumées (à écrire dans le rapport de lundi, pas à cacher)

- Les multiplicateurs sont mesurés **2021-2026** ; les backtests vont jusqu'en 2010 (FX) et 2006 (or). Les
  appliquer en arrière est une **hypothèse** : défendable (le 65e retrouve la même forme sur 2005-2026 pour
  les crosses CHF) mais non mesurée pour les majeures. La publier comme telle.
- La mesure est une **médiane par heure** : elle ne dit rien de la dispersion intra-heure. Une entrée à
  17:59 ET vaut probablement plus cher qu'une entrée à 17:01 (l'élargissement du 64e est confiné aux
  15 dernières minutes), et ce modèle ne le capture pas. Deuxième itération, pas première.
- Le swap n'est **pas** touché par cette spec (rollovers corrigés le 15 oct, commit `98b66573`).
- Le spread du **week-end** (barres de carry) reste hors modèle : le moteur ne les trade pas.
