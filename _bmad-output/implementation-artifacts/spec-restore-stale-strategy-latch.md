# Story — Un état restauré ne peut pas ranimer un verrou que le runner ne détient plus

**Ouverte le** 2026-10-22 · **Origine** déploiement `trader` du 22 oct (état figé de `consecbar`, mesuré
en production) · **Chemin touché** restauration d'état (`LiveStrategyRunner.restoreState`) et porte de
déploiement (`scripts/deploy-paper.sh`) → revue **Elliot** + porte `agy` ×2

## 1. Ce qui a été mesuré, pas supposé

Le 22 octobre, le déploiement de `trader` a chargé sur master le correctif d'export d'état (`ae1849e2`,
mergé le 18 oct) qui met fin aux `Failed to save state`. Après recréation du conteneur
(`2026-10-22T14:43:52Z`, démarré `14:44:06Z`) :

| Constat | Mesure |
|---|---|
| Sauvegardes d'état après redémarrage | `0` échec, fichiers réécrits toutes les 60 s (`14:44:10`, `14:51:52`) |
| État restauré | `consecbar` 13 entrées / `-57,22`, `vwpreversion` 15 entrées / `-59,26` : intact |
| Verrou de `consecbar` restauré | `strat_inTrade: true`, `strat_tradeDirection: "BUY"`, `activeTrades: []` |
| Compte courtier | `0` trade ouvert, `lastTransactionID` 476 |

Le verrou est resté vrai pendant les trois sauvegardes suivantes : ce n'est pas un instantané transitoire.

## 2. Mécanisme, lu dans le code

`LiveStrategyRunner` (master), bloc de restauration autour de la ligne 2471 :

```java
boolean it = root.get("strat_inTrade").asBoolean();
JsonNode dirNode = root.get("strat_tradeDirection");
Order.Side dir = null;
if (dirNode != null && dirNode.isTextual()) { dir = Order.Side.valueOf(dirNode.asText()); }
if (dir == null && it) { it = false; }   // <- ne couvre QUE l'absence de direction
m.invoke(strategy, td, ltd, it, dir, cd);
```

`strat_inTrade: true` **avec** une direction textuelle passe donc tel quel : la stratégie est restaurée
en trade alors que `activeTrades` est vide. Or `ConsecutiveBarExhaustionStrategy.onBar` (ligne 42)
commence par `if (inTrade) { ... }` et ne revient jamais à la branche d'entrée : la stratégie attend une
sortie qu'elle ne peut pas prendre, et n'entre plus jamais.

Le seul chemin qui remet le verrou à zéro est `syncStrategyFlatIfNoOpenPosition` (ligne 2171), appelé
uniquement quand un trade suivi est **retiré** (`2014`, `2115`, `2150`). Avec un livre vide, aucun retrait
n'a lieu : le verrou ne se répare jamais tout seul.

Le commentaire de `restoreState` dit pourtant la règle : « The runner's book decides whether a trade is
open ». Ici, c'est la stratégie qui décide, et elle se trompe.

## 3. Correction demandée

**Le livre du runner a le dernier mot sur le verrou de la stratégie, à la restauration.**

1. Après lecture des champs de la stratégie, si `activeTrades` ne contient aucune entrée (ou aucune entrée
   sur l'instrument résolu), restaurer la stratégie **plate** : `it = false`, `dir = null`, en journalisant
   la correction (`WARN`, avec l'instrument et le verrou lu dans le fichier).
2. Ne pas se contenter de `dir == null` : une direction présente avec un livre vide est exactement le cas
   mesuré, et c'est le seul que le correctif actuel laisse passer.
3. Ne jamais inventer un côté : restaurer plat avec `dir = null`, jamais un `BUY` par défaut.
4. Le journal doit permettre de dater l'incident : instrument, `savedAt` du fichier, verrou lu, verrou
   écrit.

Non demandé, et volontairement hors périmètre : changer le format du fichier d'état, déplacer les fichiers
`/tmp` sous `/app/data`, ou modifier la logique d'entrée des stratégies.

## 4. Story 2 — la porte de déploiement doit lire le démarrage COURANT

Mesuré le 22 oct : la vérification a refusé un déploiement sain en annonçant
`lt-rsi3 claims 1 active trade(s) but the broker holds 0 — orphan state`. La ligne citée venait du journal
du **2026-10-09 10:07:57Z**, treize jours plus tôt. État réel au même instant : `activeTrades: 0`, fichier
d'état frais (`14:43:55Z`), courtier à 0.

Cause, `scripts/deploy-paper.sh:193` et `:206` :

```bash
line=$(docker logs "$cid" 2>&1 | grep -aoE 'Resumed state: [0-9]+ active trades' | tail -1 || true)
perm=$(docker logs "$cid" 2>&1 | grep -ac "Failed to write aggregated monitor" || true)
```

Les deux lisent **tout** l'historique du conteneur. Pour un service non déployé dans ce passage, c'est le
démarrage d'il y a treize jours qui répond, et le compte d'échecs de moniteur est cumulé depuis la création.

Correction demandée : borner les deux lectures au démarrage courant du conteneur
(`docker inspect -f '{{.State.StartedAt}}' "$cid"` puis `docker logs --since <cet instant>`), et dire dans
la sortie la fenêtre lue. Un service dont le démarrage courant n'a pas encore écrit sa ligne doit rester
« non certifiable » (comportement actuel, correct), jamais « orphelin ».

## 5. Critères d'acceptation

- **AC1** : un état de test `strat_inTrade: true`, `strat_tradeDirection: "BUY"`, `activeTrades: []` est
  restauré **plat**, et un `WARN` nomme l'instrument et la correction.
- **AC2** : un état `strat_inTrade: true`, direction `BUY`, avec **une** entrée dans `activeTrades` sur le
  même instrument conserve le verrou (aucune régression sur un vrai trade ouvert).
- **AC3** : un état `strat_inTrade: false` reste plat, sans message d'erreur.
- **AC4** : le test de AC1 **échoue** si la correction est retirée (preuve par mutation, pas par relecture).
- **AC5** : `scripts/deploy-paper.sh` en `--dry-run` affiche la fenêtre de journal lue, et un `Resumed
  state` antérieur au démarrage courant n'est plus retenu (test : conteneur avec deux démarrages, le
  second sans ligne).

## 6. Fichiers cibles

| Fichier | Nature |
|---|---|
| `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java` | modification (bloc de restauration, ~2471) |
| `trading-strategies/src/test/java/com/martinfou/trading/strategies/LiveStrategyRunnerStateExportTest.java` | ajout des cas AC1-AC3 |
| `scripts/deploy-paper.sh` | modification (lignes 193 et 206) |

## 7. Tests

```bash
./mvnw -B -DskipITs -Dspotbugs.skip=true -pl trading-strategies -am \
  -Dtest=LiveStrategyRunnerStateExportTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Preuve de puissance : réintroduire le comportement actuel (ne corriger que `dir == null`), relancer, le
test doit échouer sur AC1. Puis `scripts/deploy-paper.sh --dry-run` pour la Story 2.

## 8. Ce qui n'est PAS fait dans cette story

- Aucun changement de comportement pour un déploiement dont le service tourne et n'a pas redémarré.
- Aucune correction rétroactive des fichiers d'état existants : le cas `consecbar` du 22 oct a été corrigé
  à la main (fichier sauvegardé avant, verrou remis à plat, conteneur redémarré) et doit rester un cas
  documenté, pas une procédure courante.
