---
baseline_commit: c6f3253120f11caa85a6d3a16e191140f3dedcdf
---
# Story 47.1: Armer le cooldown quand la sortie vient du courtier

Status: done

## Revue indépendante (2026-10-02)

| Relecteur | Verdict | Ce qu'il a trouvé |
|---|---|---|
| `agy` passe 1 — invariant-first (`gemini-3.8-flash-high`) | APPROVED | 2 MAJEURS : lecture du drapeau et écriture du compteur au périmètre de la classe concrète, donc une stratégie qui hérite n'est ni remise à plat ni armée |
| `agy` passe 2 — adversariale (même modèle, prompt invariant + diff inliné) | **NEEDS_FIX** | 2 BLOCKERS (le même axe, dont la séquence complète : `inTrade` reste vrai à vie) + 3 MAJEURS (constante non statique, ordre des deux noms de compteur, stratégies à état `activeSide`) et 1 MINEUR (course d'une barre) |
| Elliot (relecture senior indépendante, `deepseek-v4-pro`) | APPROVED | A re-mesuré lui-même la couverture : 38 classes `COOLDOWN_BARS`, 39 compteurs, aucun manquant |
| `agy` passe 3 — confirmation sur le delta final | APPROVED | Constats 1 à 4 répondus ; reste ouvert le seul MINEUR de visibilité mémoire (résidu 7 ci-dessous) |

Traitement des constats : les deux BLOCKERS portaient sur le périmètre des champs dont l'invariant dépend —
`inTrade` et le compteur — et sont corrigés (lecture et écriture sur toute la hiérarchie). Le reste du
constat (périmètre de `setFieldValueOpt` pour `symbol` et les six autres champs) est mesuré, volontairement
non corrigé ici, et consigné au résidu 3.

## Story

En tant qu'opérateur du paper trading,
je veux qu'une stratégie sortie par le stop du courtier respecte le même cooldown que celui qu'elle s'impose quand elle sort elle-même,
afin que la fenêtre observe la stratégie validée par le backtest et non une variante qui ré-entre sur la barre suivante.

## Contexte mesuré (2026-10-02, compte `101-002-4729622-014`, fenêtre 1)

Transaction `225` : le stop du courtier sort `vwpreversion` de son long `USD_CHF` à 07:57:37 UTC (P&L réalisé −12.1386 CAD).
Transaction `229` : **la même stratégie ré-entre long `USD_CHF` 3,100 unités à 08:00:29 UTC**, soit la barre H1 suivante, même sens, avec un nouveau budget de risque de 0,6 %.

Le journal du conteneur donne la chaîne exacte :

```
07:58:28.889 [strat-vwpreversion] WARN  ⚠️ Trade ID 223 for strategy vwpreversion is no longer open at OANDA. Triggering async reconciliation.
07:58:28.946 [async-reconciliation-worker] INFO  Reconciliation complete. Removed trade ID 223 from activeTrades.
08:00:28.992 [strat-vwpreversion] INFO  📐 Risk sizing: 1000 → 3100 units | 0.6% of 1957.00
08:00:29.045 [strat-vwpreversion] INFO  ═══════ ENTRY USD_CHF BUY 0.03 lots @ 0.82738 (stop on fill: 0.82521) ═══════
```

La barre livrée à 08:00:28 avait un plus-bas de 0,827, sous le stop 0,82708 de la position sortie : la stratégie a donc bien traversé une barre où sa propre détection de stop était vraie. Elle est quand même entrée, parce qu'elle avait déjà été remise à plat par le runner, **sans cooldown**.

### La cause, dans le code

| Élément | Référence | Comportement |
|---|---|---|
| Le cooldown est armé par la stratégie seule | `trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/VWPReversionStrategy.java:90-94` | `closePosition()` fait `inTrade = false; cooldownBars = COOLDOWN_BARS;` |
| La sortie courtier ne passe jamais par là | idem, `onBar` attend la barre suivante pour voir `stopHit` | quand le courtier a déjà fermé, le signal de sortie local arrive après coup |
| Le runner remet la stratégie à plat par réflexion | `LiveStrategyRunner.java:2153-2163` (`syncStrategyFlatIfNoOpenPosition`) | appelle `strategy.syncPosition(null, 0.0, 0.0, 0.0)` |
| Ce que `syncPosition` écrit | `trading-core/src/main/java/com/martinfou/trading/core/Strategy.java:53-66` | `inTrade`, `positionSide`, `tradeDirection`, `positionUnits`, `units`, `stopLoss`, `takeProfit` — **jamais `cooldownBars`** |
| Trois chemins l'appellent | `LiveStrategyRunner.java:2002` (`evictFromActiveTrades`), `:2103` (reconciliation avec valeur courtier indisponible), `:2138` (`completeReconciliation`) | c'est `:2138` qui a joué ici |

Conclusion : le cooldown n'est armé que sur une sortie **décidée par la stratégie**. Sur une sortie **subie** (stop courtier, fermeture manuelle, TP courtier), la stratégie apprend qu'elle est plate mais garde `cooldownBars = 0`. Le cooldown est donc sauté exactement dans le cas pour lequel il existe : juste après une perte.

### Portée : 3 des 5 stratégies déployées

| Stratégie déployée | Cooldown | Référence |
|---|---|---|
| `consecbar` (`ConsecutiveBarExhaustionStrategy`) | `COOLDOWN_BARS = 8` | `creative/ConsecutiveBarExhaustionStrategy.java:21,56,86` |
| `vwpreversion` (`VWPReversionStrategy`) | `COOLDOWN_BARS = 10` | `creative/VWPReversionStrategy.java:18,70,93` |
| `ltrsi3` (`LtRSI3Momentum`) | `COOLDOWN_BARS = 3` | `longterm/LtRSI3Momentum.java:33,78,183` |
| `compmomentum`, `monthweekphase` | aucun cooldown | non concernées |

### Pourquoi c'est un problème de fidélité, pas de goût

En backtest, toute sortie passe par `closePosition()`, donc le cooldown est **toujours** armé. En live, seul le chemin « stop courtier » l'oublie. La fenêtre observe alors un comportement qui n'a jamais été backtesté : c'est un écart backtest-vers-live qui n'apparaît dans aucun rapport de drift, parce qu'il ne change pas les métriques, il change la séquence des entrées.

## Acceptance Criteria

1. **AC1 — Le cooldown est armé après une sortie subie**
   Quand le runner notifie une stratégie qu'elle est plate alors qu'elle se croyait en position (passage `inTrade` `true → false` par `syncPosition(null, …)`), la stratégie doit finir avec son cooldown armé à sa propre constante (`COOLDOWN_BARS`), identique à ce que produirait sa sortie locale.
   *Preuve attendue* : le scénario `vwpreversion` ci-dessus rejoué en test, la barre suivante ne produit aucune entrée.

2. **AC2 — Le correctif est au niveau partagé, pas copié par stratégie**
   La décision « la sortie vient de l'extérieur » se prend au niveau de l'interface `Strategy` / du runner. Une stratégie qui ignore le cooldown ne doit pas changer de comportement, et une stratégie future qui en déclare un doit en bénéficier sans modification du runner.
   *Interdit* : un `if (name.equals("vwpreversion"))` dans le runner, ou un correctif appliqué à `VWPReversionStrategy` uniquement.

3. **AC3 — La sortie de secours « zombie » reste acquise**
   La notification de plat existe pour empêcher une stratégie de rester bloquée `inTrade = true` après une sortie courtier (`TurnOfMonthFlow` a déjà connu ce mode d'échec). Le correctif ne doit pas la supprimer ni la rendre conditionnelle au cooldown : après le cooldown, la stratégie doit pouvoir entrer de nouveau.
   *Preuve attendue* : un test qui montre que la stratégie redevient capable d'entrer après `COOLDOWN_BARS` barres, sans redémarrage.

4. **AC4 — Idempotence : ne pas voler un cooldown déjà armé**
   Si la stratégie a armé son cooldown elle-même (sortie locale), puis que la reconciliation du runner confirme la fermeture, le cooldown ne doit pas être remis à zéro ni prolongé artificiellement. Le critère retenu est le changement d'état : n'armer que si le `inTrade` de la stratégie valait `true` **avant** la notification de plat (le getter `isInTrade` est déjà lu par réflexion par le runner, `LiveStrategyRunner.java:1848`).

5. **AC5 — Aucun test de cette story ne peut atteindre le courtier**
   Les tests restent sous `OrderTripwire` : zéro `MARKET_ORDER` sur le compte paper après exécution de la classe de tests.
   *Preuve attendue* : `lastTransactionID` et `openTradeCount` du compte `-014` inchangés avant/après la suite (à afficher dans la story), et `grep -c "Fetching last" <log surefire>` à 0.

6. **AC6 — La parité backtest est démontrée, pas affirmée**
   Le rejeu de la stratégie sur les mêmes barres doit produire les mêmes entrées/sorties qu'avant le correctif (en backtest, le cooldown est déjà armé sur toutes les sorties). Si un chiffre bouge, c'est que le correctif a touché le chemin backtest : à documenter avant de continuer.

## Tasks / Subtasks

- [ ] **Task 1 — Choisir et écrire le point d'armement partagé (AC1, AC2, AC4)**
  - [ ] Ajouter à `Strategy` un point d'entrée explicite (nom de travail : `onExternalFlat()` / `armCooldownAfterExternalExit()`) avec une implémentation par défaut qui n'impose rien aux stratégies sans cooldown.
  - [ ] Réutiliser l'idiome déjà en place (`Strategy.setFieldValueOpt`, `Strategy.java:84-98`) plutôt qu'un second mécanisme de réflexion.
  - [ ] Côté runner, appeler ce point d'entrée depuis `syncStrategyFlatIfNoOpenPosition` (`LiveStrategyRunner.java:2153-2163`), en s'appuyant sur le `isInTrade` lu avant l'appel pour AC4 — et non depuis chacun des 3 sites appelants, pour qu'un futur site ne puisse pas l'oublier.
  - [ ] Implémenter le point d'entrée dans les 3 stratégies concernées (`consecbar`, `vwpreversion`, `ltrsi3`), avec leur propre constante.
- [ ] **Task 2 — Tests unitaires (AC1, AC3, AC4, AC5)**
  - [ ] Test runner : dernier trade suivi disparu chez le courtier → stratégie plate **et** cooldown armé.
  - [ ] Test runner : stratégie déjà plate (sortie locale) → cooldown inchangé.
  - [ ] Test stratégie : aucune entrée pendant `COOLDOWN_BARS` barres après la notification, entrée possible après.
  - [ ] Test de non-régression : une stratégie sans champ `cooldownBars` ne lève pas et n'est pas bloquée.
- [ ] **Task 3 — Vérification de parité (AC6)**
  - [ ] Rejeu de `VWPReversionStrategy` (et `consecbar`) sur un jeu de barres figé, avant/après, comparaison du nombre d'entrées et du P&L.
- [ ] **Task 4 — Gate de revue et déploiement**
  - [ ] Revue indépendante : `agy` deux passes + **Elliot** (chemin d'ordre et sizing ⇒ revue senior supplémentaire, voir `trading-bridge-live-operations` §1).
  - [ ] `./scripts/pre-deploy-gate.sh` complet, sans pipe.
  - [ ] Déploiement via `scripts/deploy-paper.sh` (état `/tmp` préservé), puis vérification que le nouveau build tourne (horodatage de la classe, pas l'uptime du conteneur) et que le comportement corrigé apparaît dans les journaux.

## Dev Notes

- **La valeur du cooldown ne doit pas être devinée.** Ne pas écrire un `cooldownBars = 10` en dur dans le runner : chaque stratégie connaît sa constante, et une valeur recopiée au mauvais endroit devient une seconde source de vérité (même classe d'erreur que l'instrument résolu depuis le nom d'affichage).
- **Le piège de la réflexion sur un champ absent est déjà traité** : `setFieldValueOpt` avale l'absence du champ. Un `onExternalFlat()` par défaut qui ne fait rien est donc plus honnête qu'un `syncPosition` enrichi qui prétendrait armer un cooldown inexistant.
- **Ne pas confondre « plate » et « prête ».** `syncPosition(null, 0, 0, 0)` remet aussi `tradeDirection`, `stopLoss` et `takeProfit` à zéro ; l'armement du cooldown s'ajoute à cet état, il ne le remplace pas.
- **Le raisonnement « c'est une perte donc il faut attendre » n'est pas un argument de goût** : c'est la définition du cooldown dans les stratégies elles-mêmes, et la seule raison pour laquelle le backtest et le live différaient.

## Hors périmètre (constaté le même jour, à ouvrir séparément)

1. **Une clôture réussie est journalisée comme un échec.** `consecbar`, le 2026-10-02 à 08:00:18 :
   `❌ TRADE EXECUTION FAILED: GBP_JPY SELL @ 208.193 — Cannot invoke "com.fasterxml.jackson.databind.JsonNode.get(String)" because the return value of ... is null`
   alors que l'ordre **a rempli** (transaction `227`, P&L −12.2505). Un NPE d'analyse de réponse fait donc passer une sortie réussie pour un échec, et c'est la reconciliation qui rattrape l'état. Story à part : parsing défensif du chemin de clôture (classe déjà connue : `41-1-resilience-du-parsing-json-et-gestion-des-exceptions-oanda.md`).
2. **Le tag de stratégie ne survit pas jusqu'au trade.** Le `MARKET_ORDER` porte `clientExtensions.tag = vwpreversion_USDCHF`, mais le trade retourné par l'API a `clientExtensions: null` (vérifié sur le trade `230`). Toute attribution par transaction (application de bureau, cron de monitoring) voit donc un trade sans stratégie, alors que les ordres du chemin courtier (tags UUID) la portent. À traiter si l'attribution par trade doit être fiable.

## Décision Martin (D38, 2026-10-02)

Le correctif se déploie **sans interrompre la fenêtre 1** : ni la stratégie, ni le sizing, ni l'instrument,
ni la liste des services ne changent, seul un défaut de comportement est corrigé. L'horloge reste celle du
2026-10-01 18:31 EDT (fin prévue 2026-10-31) et la date de déploiement du correctif est inscrite dans
`docs/paper-window-log.md`, pour que la période avant/après reste lisible plutôt que mélangée.

## Notes d'implémentation (2026-10-02)

**Commit** : `81beb350` sur `feature/47-1-cooldown-after-broker-exit` (poussée). Non déployé.

**Écart assumé par rapport à la Task 1.** La story prévoyait d'implanter le point d'entrée dans les trois
stratégies déployées. L'implémentation le met dans le défaut de l'interface `Strategy`, ce qui satisfait
AC2 plus strictement : une stratégie future qui déclare un cooldown en bénéficie sans que personne pense à
lui, et aucune valeur n'est recopiée. Mesure qui a motivé ce choix : sur les 38 classes qui déclarent
`COOLDOWN_BARS`, 38 nomment leur compteur `cooldownBars` et **une** le nomme `cooldownCounter`
(`ATRExpansionMomentumStrategy`) — un point d'entrée par stratégie aurait laissé courir exactement cette
classe d'oubli. Le défaut résout donc les deux noms, et `onExternalClose()` reste surchargeable pour un
compteur nommé autrement ou un cooldown calculé.

**Forme du correctif** : `syncPosition` lit `inTrade` **avant** d'écrire et n'appelle `onExternalClose()`
que sur la transition vrai→faux (AC4). Le défaut lit la constante `COOLDOWN_BARS` de la stratégie en
remontant la hiérarchie, ignore une constante absente ou nulle, et `setFieldValueOpt` retourne désormais
s'il a écrit — c'est ce qui permet de distinguer « champ absent » de « champ écrit ».

**Preuves** :

| Preuve | Résultat |
|---|---|
| Nouveau `ExternalCloseCooldownTest` | 5 tests, dont la séquence vwpreversion complète (sortie courtier → 10 barres refusées → entrée) |
| `LtRSI3MomentumStopTest.reentersAfterBrokerStopReconciliation` | Contrat changé : silencieux pendant les 3 barres de cooldown, entrée sur la 4e (avant : ré-entrée sur la 2e barre) |
| Pouvoir du test | Worktree au commit parent, mêmes fichiers de test : **3 assertions sur 8 échouent**, dont `the bar right after a broker stop-out must NOT produce an entry (cooldown armed, bar 1 of 10)` à `expected: <true> but was: <false>` |
| Suite du module | `trading-strategies -am` : **91 tests, 0 échec**, BUILD SUCCESS |
| Contact courtier | `Fetching last` = 0 dans le log surefire ; compte `-014` **inchangé** (`lastTransactionID` 234 avant/après, balance 1957.0031) — et cela **avec 3 variables `OANDA_*` héritées du shell dans le JVM de test**, donc sous la condition la plus hostile |

**Résidus à ne pas oublier** :

1. **Le déploiement n'est pas fait.** La fenêtre tourne toujours sur le build du 2026-10-01, donc le correctif
   n'est actif nulle part. Le gate pré-déploiement reste à passer.
2. **AC6 (parité backtest) est un argument structurel, pas un rejeu.** Toute sortie backtest passe par
   `closePosition()`, donc le cooldown y était déjà armé ; un rejeu complet sur barres figées n'a pas été fait.
3. **Périmètre d'écriture de `setFieldValueOpt`, volontairement inchangé.** Les deux passes de revue ont
   qualifié de BLOCKER le fait que `setFieldValueOpt` ne regarde que la classe concrète. Mesure : le drapeau
   `inTrade` et le compteur sont maintenant traités sur toute la hiérarchie (c'est ce dont l'invariant a
   besoin), mais **37 classes héritent leur champ `symbol`** d'une classe de base (`AbstractPropStrategy`,
   `HarnessScriptedStrategy`, `NewsWeeklyStrategy`, `SeasonalityStrategy`) sans le redéclarer, donc
   `reconcileInstrument` ne les atteint pas. Élargir cette écriture changerait l'instrument effective de 37
   stratégies dans un commit sur les cooldowns : c'est un autre défaut, mesuré, qui mérite sa propre story.
4. **Ambiguïté des deux noms de compteur : aucune instance.** Aucune classe ne déclare `cooldownBars` ET
   `cooldownCounter` (mesuré) ; si cela arrivait, l'ordre de résolution choisirait arbitrairement et la classe
   devrait surcharger `onExternalClose()` — c'est écrit dans son javadoc.
5. **Une stratégie sans drapeau `inTrade` n'a rien à armer.** `AbstractPropStrategy` est la seule classe qui
   surcharge `syncPosition` et elle ne déclare pas `COOLDOWN_BARS` (mesuré) : le cas « état suivi par
   `activeSide` » n'a donc rien à armer aujourd'hui.
6. **Course d'une barre sur la durée du cooldown.** Si la réconciliation d'une sortie intra-barre arrive
   quelques secondes avant la clôture de cette même barre, c'est cette barre-là qui consomme la première unité
   du cooldown, donc un cooldown plus court d'une barre que le backtest. Fenêtre de quelques secondes par
   heure, conséquence d'une barre, aucune position en jeu : non corrigé ici, et le correctif demanderait que
   la notification transporte l'horodatage de la sortie.
7. **Visibilité mémoire.** Les écritures par réflexion depuis le fil de réconciliation n'ont pas de garantie
   formelle de visibilité côté fil de stratégie. C'est une propriété pré-existante de la notification de plat
   (l'écriture de `inTrade` du correctif C1 a exactement la même), pas quelque chose que ce commit ajoute ;
   la corriger vraiment veut dire router la notification par la file de la stratégie, ce qui est une
   conception, pas un correctif.

## References

- `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java:2002,2103,2138` — les trois appels à `syncStrategyFlatIfNoOpenPosition`
- `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java:2142-2163` — le commentaire C1 et l'appel `syncPosition(null, 0.0, 0.0, 0.0)`
- `trading-core/src/main/java/com/martinfou/trading/core/Strategy.java:53-66` — `syncPosition` par défaut (champs écrits par réflexion)
- `trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/VWPReversionStrategy.java:56-93` — `onBar` (détection de stop) et `closePosition` (seul armement du cooldown)
- `trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/ConsecutiveBarExhaustionStrategy.java:21,56,86`
- `trading-strategies/src/main/java/com/martinfou/trading/strategies/longterm/LtRSI3Momentum.java:33,78,183`
- `docs/paper-window-log.md` — fenêtre 1, `lastTransactionID` de départ `218` (les transactions `219`+ appartiennent à la fenêtre)
