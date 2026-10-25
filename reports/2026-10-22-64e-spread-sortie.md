# 64e — LE SPREAD DE SORTIE, ENFIN MESURÉ (jeudi 22 oct 2026)

**Idée du jour.** La pièce manquante signalée trois fois (fade 59e, lane D47, rollover 62e) : toutes les
mesures du fade reposaient sur des barres **BID seules**, donc le coût de sortie ne pouvait qu'être **borné**.
OANDA v20 sert des bougies `price=BA` (bid **et** ask) sur H1 depuis 2005 et M5 depuis 2010 → la mesure est
possible sans acheter de données.

**Données téléchargées** (`data/oanda-ba/`, scripts `scripts/oanda-ba-fetch.py`) :
H1 BA 2005-01 → 2026-10 pour GBP_CHF (138 167 barres), EUR_CHF (138 133), AUD_CHF (138 327) ;
M5 BA 2022-01 → 2026-10 pour GBP_CHF et AUD_CHF (250 vendredis chacun).

**Identité utilisée** (tout le run en découle). L'edge est mesuré sur des barres BID ; une position SHORT
vend au bid à t0 et rachète à l'ASK à t1 :

```
realise = bid(t0) - ask(t1) = [bid(t0) - bid(t1)] - spread(t1)
=> NET = fade_bid - SPREAD PLEIN A LA SORTIE
```

Le coût d'un aller-retour pour une sortie au bid est donc **le spread entier du moment de sortie**, pas un
demi-spread « moyen ». C'est exactement le chiffre qui manquait.

---

## A. Sortir à la clôture n'est pas tradeable (mesuré, 1 131 vendredis par croix)

| Croix | fade bid 13:00→clôture | spread à la sortie | NET | t | ères négatives |
|---|---|---|---|---|---|
| GBP_CHF | +5,65 bp | **12,10 bp** | **−6,45** | −5,78 | 5/5 |
| EUR_CHF | +4,13 bp | **7,97 bp** | **−3,83** | −5,21 | 5/5 |
| AUD_CHF | +9,01 bp | **18,06 bp** | **−9,05** | −6,92 | 5/5 |

Le coût supposé par le 55e/60e était 2,278 / 1,702 / 2,584 bp « A/R » → le spread réellement payé à la
clôture vaut **5,3× / 4,7× / 7,0×** cette hypothèse. Le net est négatif dans **les 5 ères** (2005-09,
2010-13, 2014-17, 2018-21, 2022-26) et dans **IS comme OOS** pour les trois croix. Le verdict ne dépend
d'aucun choix de fenêtre.

Ratio spread de sortie / spread de la fenêtre tenue : moyenne **3,44 / 3,09 / 3,54** ; **91 % / 83 % / 93 %**
des vendredis dépassent 2×, **53 % / 42 % / 54 %** dépassent 3×.

## B. Où le spread s'ouvre (M5, GBP_CHF, 250 vendredis, minutes relatives à la vraie clôture)

```
-125..-70 : 2,02-2,28 bp   (plat)
 -60      : 2,45
 -40      : 2,51
 -30      : 2,92
 -20      : 3,07
 -15      : 3,50
 -10      : 4,22
  -5      : 9,74
clôture   : 12,10
```

L'élargissement est **confiné aux 15 dernières minutes** ; il triple entre H−15 et H−5. Toute sortie à
H−10 ou avant paie un spread de 2 à 4 bp ; toute sortie dans les 5 dernières minutes paie 9 à 12 bp.

## C. 🔴 LE CONTRÔLE DÉCISIF — le fade de la dernière heure est le SPREAD, pas le PRIX

Une baisse genuine déplace le **mid** ET le bid. Un élargissement de spread ne déplace que le **bid**.
Mesure des deux, même fenêtre, mêmes vendredis :

| Instrument | fenêtre | BID | MID | t(mid) |
|---|---|---|---|---|
| GBP_CHF (M5, 250 vendredis, 2022-26) | H−60 → clôture | **−3,86 bp** (t −9,73) | **−0,16 bp** | **−0,45** |
| AUD_CHF (M5, 250 vendredis, 2022-26) | H−60 → clôture | **−6,28 bp** (t −12,46) | **−0,40 bp** | **−0,89** |

Plein échantillon H1 (1 131 vendredis), jambe de la dernière heure seule (k=0) :

| Croix | k=0 BID | k=0 MID |
|---|---|---|
| GBP_CHF | +3,26 bp (t +12,6) | **+0,07 bp (t ≈ 0,0)** |
| EUR_CHF | +1,81 bp (t +10,8) | **−0,21 bp** |
| AUD_CHF | +5,91 bp (t +14,1) | **+0,71 bp** |

Et sur la jambe complète 13:00 → clôture : GBP_CHF bid **+5,65** contre mid **+0,77** → **86 % du « fade »
est l'ouverture du spread**. Le vendeur paie ~6 bp de spread pour encaisser 0,16 bp de dérive du mid.

C'est la falsification du **mécanisme** de la famille entière (37e → 63e) : la « prime de risque du
vendredi » mesurée sur des barres bid est l'apparition du spread à la clôture. Cela explique d'un coup
pourquoi chaque modèle de coût un peu honnête tuait la stratégie, et pourquoi le 62e a lu un « dip »
universel à 17:00 ET sur 8 séries.

## D. Le fade s'est aussi éteint dans l'ère courante (fade bid 13:00 → clôture)

| Croix | 2014-17 | 2018-21 | 2022-26 |
|---|---|---|---|
| GBP_CHF | +10,91 | +8,85 | **+1,62** |
| EUR_CHF | +7,98 | +6,12 | **+0,73** |
| AUD_CHF | +15,55 | +12,60 | **+8,24** |

Le plein-échantillon (+5,65 GBP / +4,13 EUR / +9,01 AUD) est porté par 2014-2021. Dans l'ère où l'on
déploierait, GBP_CHF et EUR_CHF sont **sous** le seuil de survie.

## E. La config qui se déploie demain a une heure d'avance

`feature/fade-permanent-2026-10-23` → `FridayCloseSchedule.CLOSE_TIME = 16:00 America/New_York`,
entrée `close−60` = **15:00 ET**, sortie `close−10` = **15:50 ET**.

Or la donnée dit que la dernière barre réelle du vendredi est **`[16:00, 17:00) ET`** (label h20 UTC en
EDT, h21 en EST, vérifié sur 1 131 vendredis) : la clôture FX est **17:00 ET**, pas 16:00. Le code a une
heure d'avance ; sa fenêtre est `[15:00, 15:50] ET`, soit **k=1..k=2** au lieu de k=0.

Fenêtres mesurées (M5, 250 vendredis 2022-2026, net = fade bid − spread de sortie mesuré) :

| Fenêtre | GBP_CHF net (t) | AUD_CHF net (t) |
|---|---|---|
| CODÉE `[H−120, H−70]` | **−2,40** (−9,01) | **−3,14** (−6,95) |
| VISÉE `[H−60, H−10]` | **−2,77** (−8,97) | **−3,36** (−7,92) |
| HEURE COMPLÈTE `[H−60, 0]` | **−5,87** (−15,23) | **−8,68** (−15,77) |
| D57 `13:00 → clôture` | **−8,12** (−4,28) | **−6,68** (−2,85) |

GBP_CHF détail : fade / spread de sortie / hit → CODÉE (−0,18 / 2,22 / 26 %), VISÉE (+0,93 / 3,70 / 28 %),
HEURE (+3,87 / 9,74 / 13 %), D57 (+1,62 / 9,74 / 34 %).

Les quatre fenêtres sont négatives sur **les deux instruments**, en IS (≤2022) comme en OOS (≥2023).
Corriger l'ancre (17:00 ET) est nécessaire mais **pas suffisant** : la fenêtre visée reste à −2,8/−3,4 bp.
Le fade brut de la fenêtre codée est même **négatif** (−0,18 GBP, −0,10 AUD) : elle achète l'heure d'avant.

## P0 — non-régression de la mesure

H1 et M5, mêmes vendredis, même calcul : **0/250 vendredis** d'écart supérieur à 5 bp, moyennes identiques
au centième (fade H1 +1,62 / M5 +1,62 sur 2022-2026). Le 59e (−3,35 bp en convention retour) est reproduit
par le même flux, au signe de convention près.

---

## Verdict

❌ **REJECT de la famille fade telle que déployée**, pour deux raisons indépendantes :

1. **La sortie à la clôture est une perte structurelle** (−3,8 à −9,0 bp, 5/5 ères, 3/3 croix) parce que
   le spread de clôture est 5 à 7× celui qui avait été supposé.
2. **Le mécanisme n'existe pas sur le mid** : dernière heure −0,16 bp (t −0,45) sur GBP_CHF et −0,40
   (t −0,89) sur AUD_CHF pour un bid à −3,9 et −6,3. Le « fade » est le spread qui s'ouvre.

La lane `FridayFadeAudChf` prévue pour le 23 oct est une expérience à **espérance négative** : elle paie
~2 à 6 bp de spread par semaine pour un mid qui ne bouge pas (~0,02 % de NAV/semaine, ~1 %/an au budget
de risque de 0,5 % avec un stop de 150 pips). Deux options honnêtes : ne pas déployer, ou déployer en
assumant explicitement que c'est un test de **chemin d'exécution** (fills, M5, DST), pas un test d'edge.

## Ce qui survit

- **AUD_CHF porte encore une dérive de mid** hors de la dernière heure : `13:00 → H−1` mid **+1,66 bp**
  (2022-26), et le plein-échantillon AUD_CHF mid +1,64 bp. Mais 1,7 bp ne paie pas 3,2 bp de spread :
  il faut soit une entrée moins chère (limite au mid, pas de spread), soit un horizon plus long.
- La **sortie H−10** est justifiée en soi (spread 4,2 vs 12,1) même si l'edge qu'elle protège est nul.

## Leçons

1. **Un edge mesuré sur des barres bid n'est pas un edge tant que le spread du même instant n'est pas
   mesuré.** Le mid est le seul juge : si le mid ne bouge pas, on ne collecte pas un mouvement, on subit
   un élargissement.
2. **Le label d'une barre n'est pas son instant de clôture.** h20 UTC = le *début* de `[16,17) ET` ; la
   clôture est 17:00 ET. Confondre les deux décale une fenêtre d'une heure entière, et une heure, ici,
   c'est 100 % de l'edge prétendu.
3. **Un spread de sortie se mesure sur la barre de sortie, pas en moyenne sur la fenêtre tenue** : 12,1
   contre 3,7 bp sur la même fenêtre, 3,4× d'écart, et c'est ce facteur qui inverse le signe.
4. **Un résultat robuste peut être robustement faux** : le net était négatif 5/5 ères, 3/3 croix, IS et
   OOS — la robustesse ne dit rien de la validité du mécanisme, elle dit que l'artefact est stable.

## Décision (2026-10-22, Martin) : la lane n'est PAS déployée

La lane `FridayFadeAudChf` prévue pour le 23 octobre **n'est pas déployée**. Décision prise sur la
mesure de ce rapport, pas sur une préférence :

- le net est négatif dans 5/5 ères et en IS comme OOS, pour les trois croix ;
- le mid ne bouge pas dans la dernière heure (-0,16 bp, t -0,45) : le fade est l'ouverture du spread ;
- même la fenêtre codée est négative (-2,40 GBP / -3,14 AUD), et son heure de sortie est fausse
  (close=16:00 NY au lieu de la barre 17:00 ET).

Conséquence concrète : aucune entrée n'est ajoutée à un playbook `docs/news-playbooks/week-*.md` pour
cette lane, et aucun service n'est ajouté au compose. Le cron « News Week » du vendredi 23 octobre ne
trouve aucun playbook pour la semaine du 26 : il rapporte et s'arrête, ce qui est le comportement voulu.

Condition pour rouvrir : une entrée au mid (ordre limite, donc pas de spread payé) ou un horizon plus
long, avec une sortie sur la barre de clôture réelle (17:00 ET) et le spread mesuré à cette barre.
