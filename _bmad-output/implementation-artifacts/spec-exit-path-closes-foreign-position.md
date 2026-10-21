# Story — Un runner ne ferme jamais une position qu'il ne détient pas

**Ouverte le** 2026-10-21 · **Origine** D67/D68 (retrait de la lane news du 12 octobre) ·
**Chemin touché** chemin des ordres (`executeTrade`, `brokerOpenUnits`) → revue **Elliot** + porte `agy` ×2

## 1. Ce qui a été mesuré, pas supposé

Sur le compte paper `101-002-4729622-014`, cinq positions ouvertes par `ltrsi3` ont été fermées par les
stratégies news de la lane du 12 octobre :

| Transaction | Date (UTC) | Fermée par | Position ouverte par | P&L |
|---|---|---|---|---|
| 380 | 2026-10-15 08:00:44 | `newsoct12cpi` | `ltrsi3_EURUSD` | -0,31 |
| 405 | 2026-10-16 06:00:01 | `newsoct12ppi` | `ltrsi3_EURUSD` | -1,62 |
| 430 | 2026-10-19 06:00:26 | `newsoct12ppi` | `ltrsi3_EURUSD` | -1,28 |
| 439 | 2026-10-20 05:00:51 | `newsoct12ppi` | `ltrsi3_EURUSD` | -0,61 |
| 455 | 2026-10-21 12:00:08 | `newsoct12ppi` | `ltrsi3_EURUSD` | -1,36 |

Total réalisé **-5,19 CAD**. Dans chaque cas, les unités de l'ordre de fermeture correspondaient exactement à
la position de l'autre stratégie (par exemple -2900 unités à la transaction 455). Côté `ltrsi3`, la seule
trace est, douze secondes plus tard :

```
WARN  LiveStrategyRunner - ⚠️ Trade ID 452 for strategy ltrsi3 is no longer open at OANDA. Triggering async reconciliation.
INFO  LiveStrategyRunner - Reconciled trade ID 452 from OANDA. Realized PnL: $-1.3581
```

La sortie de `ltrsi3` n'a donc jamais été jouée : elle est devenue une simple constatation.

## 2. Mécanisme, lu dans le code

`trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java`, `executeTrade`,
branche `isClose` (lignes 1205-1235) :

1. `trackedOpenUnits(oandaSymbol, side)` : si le runner suit la position, la taille de fermeture est la
   sienne. Correct.
2. Sinon, **repli sur `brokerOpenUnits(oandaSymbol, side)`**, c'est-à-dire sur *tout ce que le courtier détient
   sur la paire*, et fermeture de la totalité. C'est ce repli qui a fermé les positions de `ltrsi3` : le
   runner news n'avait rien de suivi, le courtier détenait 2900 unités appartenant à `ltrsi3`, et il les a
   fermées avec son propre tag.
3. Si le courtier ne détient rien, le runner refuse et journalise (95 lignes par 24 h pendant la lane).

Le repli a été ajouté par `dc9509a7` pour éviter une fermeture **partielle** d'une position perdue par le
runner (fermer 1 000 unités sur 48 000 laisse le reste sans gestion). Le raisonnement était juste sur le
volume et faux sur la propriété : la bonne question n'est pas « combien d'unités fermer » mais « ces unités
sont-elles les miennes ».

## 3. Correction demandée

**Une fermeture ne réduit que des unités que ce runner a ouvertes.** Trois niveaux, du plus sûr au plus
permissif :

1. **Propriété explicite (recommandé).** Un trade OANDA porte `openingTransactionID`. Le chemin de fermeture
   résout cette transaction, lit `clientExtensions.tag` de son `MARKET_ORDER`, et ne ferme que si le tag
   appartient au runner (`<strategyShortName>_<SYMBOL>`). Le tag ne survit pas sur le trade lui-même (mesuré :
   `GET /v3/accounts/{id}/trades/{id}` renvoie `clientExtensions: null`), mais il est toujours sur l'ordre
   d'ouverture, et `openingTransactionID` fait le pont en un saut.
2. **Repli sans suivi, sans propriétaire résolvable : refuser.** Journaliser en `ERROR` avec les unités en
   face, ne rien envoyer, et **alerter** (une position non gérée n'est pas un détail de log).
3. **Interdiction générale** : supprimer `brokerOpenUnits` du chemin de fermeture. Un runner qui ne suit pas
   une position n'a pas le droit de la fermer, quel que soit le volume. Le filet de la position devient le
   SL/TP attaché au courtier, ce qui est le comportement voulu.

Le message « would leave an untracked position open » disparaît avec le repli : la position en face n'est
pas non suivie, elle appartient à quelqu'un d'autre.

## 4. Critères d'acceptation

- [ ] Une stratégie A ne peut pas réduire une position dont l'ordre d'ouverture porte le tag de B. Vérifié par
      un test qui construit l'état exact mesuré : A ne suit rien, le courtier détient la position de B, A émet
      une sortie → **aucun ordre n'est envoyé**.
- [ ] Cas de reprise : une position ouverte par le même runner mais perdue au redémarrage (état `/tmp` vidé)
      reste fermable, parce que son ordre d'ouverture porte le bon tag. Sans ça, le correctif transforme une
      perte d'état en position impossible à clôturer.
- [ ] Tag illisible, transaction introuvable, API en erreur : refus **et** alerte, jamais une fermeture
      « au bénéfice du doute ».
- [ ] Le refus sur absence totale de position reste tel quel (aucune régression sur les 95 lignes de bruit,
      elles doivent devenir rares, pas silencieuses).
- [ ] Test de mutation : retirer le contrôle de propriété fait échouer le test (une fermeture de position
      étrangère redevient possible) ; un test qui passe sans garde est de la décoration.

## 5. Instance déjà vivante du même défaut, hors lane news

Le repli n'est pas un vestige de la lane : deux conteneurs de la fenêtre tradent **la même paire**,
`USD_JPY`, sur le même compte `-014` :

| Conteneur | Stratégie | Paire |
|---|---|---|
| `trading-comp-momentum` | `compmomentum` | USD_JPY |
| `trading-month-week` | `monthweekphase` | USD_JPY |

Les deux sont à zéro entrée depuis leur déploiement, donc l'exposition est théorique aujourd'hui, mais le
premier qui ouvre une position peut se la faire fermer par l'autre. Ce correctif couvre ce cas sans
configuration supplémentaire : il ne dépend pas du nom des stratégies, seulement de la propriété des unités.

## 6. Hors périmètre

- Aucune modification du sizing, du plafond de risque ou de la fenêtre d'observation en cours.
- Aucun ordre réel sur `-005`.
- Pas de changement des quatre conteneurs de la fenêtre pendant la fenêtre (D56, D58) : le correctif se
  déploie à la rotation, sauf décision contraire de Martin.
- La lane news du 12 octobre n'est pas concernée : elle est retirée (D67/D68).

## 6. Risque et forme du changement

Le comportement change sur le chemin des ordres, donc : revue **Elliot** en plus des deux passes `agy`,
déploiement séparé avec état sauvegardé puis restauré (procédure `trading-bridge-live-operations` §5), et
vérification après déploiement que le compte est à plat ou que les positions ouvertes sont bien expliquées par
leur tag d'ouverture.
