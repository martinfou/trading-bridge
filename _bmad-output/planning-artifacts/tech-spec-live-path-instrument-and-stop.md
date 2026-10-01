# Tech-Spec — Chemin live : instrument résolu depuis la config + stop obligatoire

> **Track BMad** : BMad Method (Phase 3 → 4). Bug qui touche **l'argent** et le chemin d'exécution live.
> **Créé** : 2026-10-01. **Source** : audit du 2026-10-01 (fee/swaps + backtest vs paper), décisions D28–D31 du `prd-dev-paper-live.md`.
> **Périmètre** : deux défauts vérifiés du chemin live. **Hors périmètre** : le modèle de coûts (déjà spécifié, Epics 40/41).

---

## 1. Contexte

Deux défauts trouvés le 2026-10-01 en auditant le chemin live, tous deux **invisibles en test unitaire** et
**non détectés depuis leur introduction** :

| # | Défaut | Effet réel |
|---|--------|-----------|
| 1 | L'instrument est résolu depuis le **nom d'affichage** de la stratégie | `vwpreversion` trade GBP_JPY alors que sa config dit USD_CHF |
| 2 | `ltrsi3` émet ses entrées **sans aucun stop** | pas de stop chez le courtier, et le budget de risque n'a pas de dénominateur |

Les deux violent des lignes déjà écrites dans `docs/TRADING-GUARDRAILS.md` (couche 3), document qui
précise lui-même que ses lignes sont « des promesses à vérifier, pas un état constaté ». Ces deux
défauts sont la démonstration de cette phrase.

---

## 2. Défaut 1 — l'instrument vient du NOM D'AFFICHAGE

**Mécanisme (vérifié)**

- `LiveStrategyRunner.toOandaSymbol(Strategy s)` — `LiveStrategyRunner.java:1616-1646` : fait
  `s.name().toUpperCase()` puis une chaîne de `name.contains(...)`, et **retombe sur
  `return "GBP_JPY"`** en fin de méthode.
- `VWPReversionStrategy()` — `VWPReversionStrategy.java:33` : `this("🔁 VWAP Reversion", "USD/CHF")`.
- `"VWAP REVERSION"` **ne contient pas** `"VWPREVERSION"` (le test de la table). La correspondance
  échoue, le défaut `GBP_JPY` s'applique.
- `config/live-config.json` porte pourtant `vwpreversion.instrument = USD_CHF`.

**Preuve observée** (log de démarrage du conteneur `trading-live`, 2026-10-01) :

```
━━━ Starting strategy: 🔁 VWAP Reversion (instrument: GBP_JPY) ━━━
```

**Conséquences**

1. Une stratégie écrite et backtestée pour un instrument à **0,83** trade un instrument à **209**.
   Tout seuil exprimé en valeur absolue de prix, toute bande VWAP et tout écart de stop sont à la
   mauvaise échelle.
2. `STRATEGY_PAIR` (docker-compose) est **inerte** pour ce runner : la variable est morte. L'opérateur
   croit régler la paire, il ne règle rien.
3. La promesse de la couche 3 des guardrails (« taille plafonnée selon `live-config.json` ») est
   **violée en fait** : la config par stratégie ne décrit pas l'instrument réellement tradé, donc le
   risque dimensionné n'est pas celui du config.

---

## 3. Défaut 2 — `ltrsi3` trade sans stop

**Mécanisme (vérifié)**

`LtRSI3Momentum.evaluateEntry()` (`trading-strategies/.../longterm/LtRSI3Momentum.java`, ~105-135) :

- calcule `stopLoss = entryPrice - atr * SL_MULT` et `takeProfit = entryPrice + atr * TP_MULT`,
- puis émet `new Order(symbol, Order.Side.BUY, Order.Type.MARKET, units, entryPrice)` — **sans**
  `withStopLoss(...)` ni `withTakeProfit(...)` : ce sont des champs internes à la classe.

**Conséquences**

1. **Aucun stop chez le courtier.** Rien ne protège la position si le processus meurt.
2. **Le budget de risque perd son dénominateur.** `riskSizedUnits` dimensionne à partir de la distance
   au stop ; sans stop, il ne peut pas viser les 1,5 % du config et retombe sur le petit plafond sans
   risque. La taille réellement envoyée n'est donc pas celle que la policy annonce.
3. Seule la vérification **in-process** protège la position : un crash, un redémarrage ou un gap n'est
   pas couvert. C'est exactement le scénario que la couche 1 des guardrails existe pour empêcher.

---

## 4. Décisions (D28–D31)

| # | Décision | Origine |
|---|----------|---------|
| **D28** | La resolution d'instrument est **corrigée par le mécanisme**, pas par un correctif de chaîne. | choix de Martin (Q2) |
| **D29** | Après correction, `vwpreversion` est **re-backtestée sur USD_CHF et GBP_JPY** et la paire est choisie **sur preuve**, pas par défaut. | choix de Martin (Q2) |
| **D30** | `ltrsi3` reçoit son **stop ATR sur l'ordre d'entrée** (le stop vit chez le courtier) ; la **cible reste gérée par la stratégie** ; une **garde** refuse toute entrée sans stop. | choix de Martin (Q3) |
| **D31** | Une **seconde porte** est écrite pour les edges mono-instrument (OOS PF stable, DD ≤ 10 %, faible nombre de trades admis si le mécanisme est calendaire ou de session) ; la période paper de 30 jours fournit l'échantillon manquant. | choix de Martin (Q4) |

---

## 5. User Stories (valeur métier)

| # | En tant que... | Je veux... | Pourquoi |
|---|----------------|-----------|----------|
| **US-1** | trader | qu'une stratégie trade **l'instrument que sa config annonce**, ou refuse de démarrer | Aujourd'hui la config décrit une paire que le runner n'utilise pas : le risque dimensionné n'est pas celui du config, et je l'ignore. |
| **US-2** | trader | qu'**aucune entrée ne parte sans stop**, ni en live ni en backtest | Un stop est à la fois la protection de la position et le dénominateur du budget de risque. Sans lui, la taille annoncée est fictive. |
| **US-3** | opérateur | qu'un écart entre la config et le comportement réel **arrête le démarrage** au lieu de produire un log | Le log existait déjà (`instrument: GBP_JPY`) et personne ne l'a lu pendant des semaines. Un log n'est pas un garde-fou. |

---

## 6. Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **1.1** | L'instrument vient d'une source explicite | S | — | `toOandaSymbol` cesse d'être la source de vérité. Ordre de résolution : (1) `live-config.json` → `strategies.<id>.instrument` ; (2) `STRATEGY_PAIR` si l'opérateur la pose **explicitement** (elle gagne, mais elle est journalisée) ; (3) échec explicite. La table par nom d'affichage est supprimée ou réduite à un dernier recours **avec WARN bruyant**. |
| **1.2** | Garde de démarrage « config vs réel » | S | 1.1 | Au démarrage de chaque runner, comparer l'instrument résolu à `live-config.json` et **refuser de démarrer** en cas d'incohérence, avec une ligne qui nomme la stratégie, l'attendu et le résolu. Le log de démarrage (`:837`) doit aussi imprimer **la source** de la résolution. |
| **1.3** | Stop ATR sur les entrées `ltrsi3` | XS | — | Ajouter `withStopLoss(stopLoss)` aux deux branches de `evaluateEntry()` (`LtRSI3Momentum.java`). **Ne pas** attacher `takeProfit` (D30 : la cible reste à la stratégie). |
| **1.4** | Garde « pas de stop ⇒ pas d'entrée » | S | 1.3 | Toute entrée sans stop est **refusée et journalisée**, avec un compteur d'observabilité. Miroir dans le moteur de backtest pour que les deux chemins restent d'accord. |
| **1.5** | Tests | S | 1.1–1.4 | Voir §8. |
| **1.6** | Documentation des invariants | XS | 1.1–1.4 | `docs/TRADING-GUARDRAILS.md` : les deux nouveaux invariants en couche 1/3, plus une ligne nommant la **classe de défaut** (« doc vs code » : la config décrit une chose, le runner en fait une autre). |

---

## 7. Détails d'implémentation

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| 1.1 | `trading-strategies/.../LiveStrategyRunner.java:1616-1646` (modifier), `config/live-config.json` (source), `docker-compose.yml` (env) | — Les 5 stratégies déployées résolvent l'instrument annoncé par la config<br/>— Poser `STRATEGY_PAIR` change la paire **et** est journalisé<br/>— Plus aucun `return "GBP_JPY"` silencieux en fin de résolution | `LiveStrategyRunnerTest` : table des 5 stratégies × instrument attendu ; cas `VWPReversionStrategy` → USD_CHF |
| 1.2 | `LiveStrategyRunner.java` (chemin de démarrage, autour de `:837`) | — Un couple stratégie/instrument incohérent **empêche le démarrage**<br/>— Le message nomme stratégie, attendu, résolu, source<br/>— Cas conforme : démarrage normal inchangé | Test : config volontairement incohérente → exception/arrêt, message exact |
| 1.3 | `trading-strategies/.../longterm/LtRSI3Momentum.java` (~105-135) | — Les entrées BUY **et** SELL portent un stop non nul<br/>— La cible n'est pas modifiée (D30) | Test : ordre produit → `stopLoss() != null` et distance = `atr × SL_MULT` |
| 1.4 | `LiveStrategyRunner.java` (chemin d'ordre), `trading-backtest/.../BacktestEngine.java` (miroir) | — Une entrée sans stop est refusée, journalisée, comptée<br/>— Le backtest applique la même règle | Test : stratégie factice sans stop → 0 ordre envoyé, compteur à 1 ; backtest idem |
| 1.5 | `trading-strategies/src/test/...`, `trading-backtest/src/test/...` | — Les tests échouent sur le code d'avant (preuve que le bug était réel)<br/>— Suite complète verte | `./mvnw -B -DskipITs -Dspotbugs.skip=true test` |
| 1.6 | `docs/TRADING-GUARDRAILS.md` | — Les deux invariants y figurent en couche 1/3<br/>— La classe de défaut « doc vs code » est nommée | Relecture |

---

## 8. Stratégie de test

1. **Repro d'abord (rouge)** : un test par défaut, écrit pour échouer sur le code actuel.
   - `VWPReversionStrategy` résolu en USD_CHF (échoue aujourd'hui : GBP_JPY).
   - `LtRSI3Momentum` entrée → stop non nul (échoue aujourd'hui : nul).
2. **Test de la garde 1.2** : config incohérente → refus de démarrer.
3. **Test de la garde 1.4** : entrée sans stop → refusée, en live **et** en backtest.
4. **Non-régression** : `consecbar` reste sur GBP_JPY (sa config et sa paire réelle coïncident déjà),
   et les autres stratégies ne changent pas d'instrument.
5. **Gate** : `./scripts/pre-deploy-gate.sh` complet (build + 12 modules + smoke test OANDA lecture
   seule). Jamais dans un pipe vers `tail` (le `$?` observé devient celui de `tail`).

---

## 9. Cas limites à couvrir

- Une stratégie absente de `live-config.json` (aujourd'hui repli sur un risque par défaut) : que fait
  la garde 1.2 ? Proposition : refus de démarrer, pas de repli silencieux — un repli silencieux est le
  mécanisme même du défaut 1.
- `STRATEGY_PAIR` posée **et** différente de la config : qui gagne ? D28 dit « la config est le
  défaut, l'explicite gagne », donc l'env gagne mais doit être journalisé et **inscrit dans l'état**.
- Une stratégie multi-instruments par construction (`all` dans `docker-compose.yml`) : la garde doit
  traiter ce cas comme une liste, pas comme un couple.
- Après D29 (`vwpreversion` sur USD_CHF) : la position ouverte éventuelle sur GBP_JPY au moment du
  changement doit être **traitée explicitement** (fermée ou adoptée), jamais orpheline — c'est la
  leçon des décisions D20–D27.

---

## 10. Séquencement

1. `1.1` + `1.2` (instrument, même chemin de code).
2. `1.3` + `1.4` (stop, même chemin de code).
3. `1.5` tests tout au long, `1.6` en fin.
4. **Revue indépendante** (`scripts/agy-review.sh`, 2 passes) avant merge.
5. Déploiement sur `-014` **après** accord explicite de Martin.
6. Ensuite seulement : Epics 40/41 (modèle de coûts) puis la comparabilité backtest/paper.

---

## 11. Hors périmètre (rattaché aux artefacts existants, pas à cette spec)

| Sujet | Où il vit déjà |
|-------|----------------|
| Modèle de coûts (spread par paire, commission 0, swaps variables) | `epics-and-stories-fees-swaps-realism.md` — **Epic 40.1 non implémenté** (aucun `spreadPips` dans le code). L'audit du 2026-10-01 fournit les valeurs mesurées. |
| Re-baseline + validation contre le broker | même fichier — **Epic 41**. L'audit du 2026-10-01 **est** la comparaison courtier de la story 41.2 (spreads et swaps mesurés, tolérance 10-15 %). |
| Comparabilité backtest/paper (données manquantes) | nouveau : données FX H1 s'arrêtent au 2026-05-20, fenêtre live en sept-oct 2026 → aucune barre commune. À porter comme story quand 40/41 seront faits. |
| Seconde porte mono-instrument (D31) | `docs/lt-strategy-playbook.md` §4.3 (seuils de passage) + skill `simons` (Quality Gate). |

---

## 12. Révision 2 — après Party Mode (2026-10-01)

Trois agents (Winston architecte, Amelia dev, John PM) ont revu la v1 en lecture seule sur le code réel.
**Ils ont trouvé des erreurs dans cette spec, dont une qui aurait arrêté trois des cinq conteneurs en
production.** Les affirmations ci-dessous ont été **revérifiées à la main dans le code** avant d'être
inscrites ici. Les sections 6 et 7 restent la référence de l'intention ; les amendements qui suivent les
corrigent et les complètent.

### 12.1 Ce qui était faux dans la v1

| # | Erreur de la v1 | Réalité vérifiée | Conséquence |
|---|---|---|---|
| **E1** | « Si une stratégie est absente de `live-config.json`, refuser de démarrer » (§9) | `live-config.json` ne contient que **9 clés** : `vwpreversion`, `consecbar` et 7 `2_*`. **`compmomentum`, `monthweekphase`, `ltrsi3` et `nfpweek` sont ABSENTS.** | La garde 1.2 aurait **arrêté 3 des 5 conteneurs** (comp-momentum, month-week, lt-rsi3), dont `ltrsi3`, sujet même de D30. La garde ne peut pas être activée avant que la config soit complétée. |
| **E2** | « La config porte l'instrument, il suffit de le lire » | `loadConfig()` **ne lit jamais** le champ `instrument` (`LiveStrategyRunner.java:418-464` ; le mot `instrument` n'apparaît dans le fichier qu'en log/état, lignes 680, 837, 1663). | Il y a **trois** sources concurrentes : le champ `symbol` de la classe (jamais mis à jour, l'instanciation est réflexive **sans argument**, `:333` et `:343`), le champ `instrument` de la config (jamais lu), et `toOandaSymbol()` dérivé du nom (le seul utilisé, et faux pour une stratégie). |
| **E3** | « La table de noms est cassée » | Elle résout correctement **4 des 5** stratégies déployées. Seule `vwpreversion` casse (`VWPREVERSION` vs le nom affiché `VWAP Reversion`). | Le vrai défaut systémique est la **multiplicité des sources**, pas la table. Le correctif porte sur le mécanisme, pas sur la chaîne. |
| **E4** | Le `symbol` de classe est décoratif | **Faux pour `ltrsi3`** : `LtRSI3Momentum.onBar()` commence par `if (!bar.symbol().equals(symbol)) return;` (`LtRSI3Momentum.java:64`) avec le défaut de classe `"EUR_USD"` (`:56`). | Le correctif 1.1 peut créer une **panne silencieuse neuve** : si `ltrsi3` résout une autre paire que son champ interne, **100 % des barres sont filtrées et la stratégie trade zéro, sans un log**. C'est pire que le défaut actuel. |

### 12.2 Ce qui manquait (et qui devient des stories)

| # | Story ajoutée | Effort | Pourquoi |
|---|---------------|--------|----------|
| **1.7** | **Le stop part DANS l'ordre** au lieu d'un second appel | M | `executeTrade` place l'ordre marché nu (`LiveStrategyRunner.java:1239`) puis attache le stop par `addStopLoss` **après** le fill (`:1254-1270`), en `warn`-and-continue si l'appel échoue. D30 dit « le stop vit chez le courtier » : tant que c'est un second appel, un crash dans la fenêtre fill→attach laisse une position **nue**, et un échec d'attache ne fait que journaliser. OANDA accepte `stopLossOnFill` dans le corps de l'ordre. À corriger dans le même geste que 1.3, sinon D30 n'est pas honoré. ⚠️ Le chemin `setStopLossOnFill` porte en plus un bug de format `%.5f` sur les paires JPY (3 décimales attendues). |
| **1.8** | **Réconcilier le `symbol` de classe avec l'instrument résolu** | S | Sans ça, E4 transforme un correctif en panne muette. Le pattern maison existe déjà : `Strategy.syncPosition` (`Strategy.java:53-66`) pose des champs par réflexion. Option propre : méthode `default` sur l'interface `Strategy` (rétrocompatible, ~50 implémentations intactes), à surcharger par les stratégies qui filtrent sur `bar.symbol()`. |
| **1.9** | **Compléter `live-config.json`** (prérequis de 1.2) | S | Ajouter `compmomentum`, `monthweekphase`, `ltrsi3`, `nfpweek` avec instrument + `computedRiskPct` + `backtestMetrics`. **Avant** l'activation de la garde, sinon E1. |
| **1.10** | **`resumeState()` doit confronter l'instrument sauvegardé au résolu** | S | `resumeState()` restaure `activeTrades` avec leur `symbol` embarqué (`:2224`) **sans jamais comparer** à l'instrument nouvellement résolu, et le fichier d'état est clé par nom court seul (`:250`). C'est la position orpheline de D20-D27 qui se rejoue, dès que la paire de `vwpreversion` change (D29). Refuser d'adopter, ou exiger une adoption explicite tracée. |

### 12.3 Amendements aux stories existantes

- **1.1** — effort réel **M, pas S**. Le chemin le plus simple, dans l'ordre : (a) résoudre par la clé
  `strategyShortName` (déjà un champ, `:130`) contre `strategies.<clé>.instrument` ; (b) ne pas supprimer
  la table d'un coup, mais **remplacer le `return "GBP_JPY"` silencieux** (`:1645`) par un échec bruyant ;
  (c) réconcilier le `symbol` de classe (story 1.8) ; (d) compléter la config (story 1.9) **avant**
  d'activer la garde. Effort M parce que (c) et (d) sont des prérequis, pas des détails.
- **1.4** — la garde doit vivre **une seule fois dans `trading-core`** (prédicat partagé appelé par le
  point d'entrée des deux moteurs), **pas** en « runner + miroir dans `BacktestEngine` ». Un miroir, c'est
  deux implémentations de la même règle, donc deux occasions de diverger : exactement ce que la garde est
  censée empêcher. ⚠️ Et c'est un **changement de sémantique**, pas un garde-fou neutre : aujourd'hui une
  entrée sans stop est **acceptée** et plafonnée à 2 000 unités avec un `warn` (`:527-532`). Une garde
  générale casserait les stratégies existantes sans stop (harnais, quantité fixe) → à scoper, et le
  miroir backtest devient un **opt-in**.

### 12.4 Ce que la revue sort du périmètre (et pourquoi)

| Sujet | Décision de séquencement | Raison |
|---|---|---|
| **D29** (re-backtest USD_CHF vs GBP_JPY, choix sur preuve) | **hors de cette spec** | C'est de la recherche, pas un correctif. À ne pas mettre sur le chemin critique du déploiement. |
| Miroir backtest de la garde | **reporté** | Il protège le pipeline, pas le courtier, et devient utile quand les coûts rendront backtest et paper comparables. |
| **D31** (seconde porte) | **après Epic 40.1** | ⚠️ Argument décisif de la revue : la porte actuelle mesure un **PF qui exclut les coûts** (`Epic 40.1` non implémenté, aucun `spreadPips` dans le code). Écrire une seconde porte maintenant revient à calibrer un seuil sur une métrique fantaisiste. La porte peut être écrite, mais **son seuil doit être exprimé coûts inclus**, donc après 40.1. |
| `STRATEGY_PAIR` | **supprimer**, pas conserver en repli | Variable morte (0 occurrence en Java), et le compose l'injecte **toujours** avec un défaut (`${STRATEGY_PAIR:-USD_CHF}`), donc le runner ne peut pas distinguer « posée par l'opérateur » de « défaut compose ». La conserver recrée le problème à deux boutons que l'US-3 dénonce. Corollaire : passer les défauts à vide dans `docker-compose.yml`. |

### 12.5 Ce que ça change au séquencement (§10)

1. `1.9` (compléter la config) et `1.8` (réconcilier le `symbol`) — **prérequis**.
2. `1.1` + `1.2` (instrument + garde de démarrage).
3. `1.3` + `1.7` (stop sur l'ordre, et dans l'ordre) + `1.4` scopée (garde unique).
4. `1.10` (instrument sauvegardé vs résolu) — avant tout changement de paire.
5. `1.5` tests, `1.6` docs.
6. Revue indépendante `scripts/agy-review.sh`, puis **déploiement seulement sur accord de Martin**.

### 12.6 Le point qui dépasse cette spec

La revue a raison sur un point qu'il faut garder visible : **corriger ces deux défauts ne débloque aucune
stratégie rentable.** Les candidats sont bloqués à la **porte de backtest**, pas sur le chemin live, et
cette porte mesure un PF **sans coûts**. La valeur de ces correctifs est l'**intégrité de la mesure** :
sans eux, les 30 jours de paper ne produisent aucun signal sur l'edge réellement backtesté. Le levier de
la question « est-ce que ça peut gagner de l'argent » reste **Epic 40.1** (le modèle de coûts).

### 12.7 Deux compléments oubliés en v2

**C1 — Double pilotage du stop (story 1.3 / 1.7).** Une fois le stop attaché à l'ordre, **deux** chemins
peuvent fermer la position : le SL du courtier, et le contrôle interne de la stratégie
(`LtRSI3Momentum.java:139-141` et `:146-149`, qui teste `stopLoss`/`takeProfit` dans `onBar()`). Le runner
réconcilie via `updatePositions(oandaSymbol)` (`LiveStrategyRunner.java:886`), chemin éprouvé pour les
autres stratégies mais **neuf pour `ltrsi3`**. La story doit dire explicitement quel chemin gagne et ce que
devient l'autre : soit le contrôle interne devient un **no-op** dès que le courtier détient le stop, soit la
réconciliation est étendue et testée sur ce cas. Ne pas laisser les deux se disputer la sortie.

**C2 — Checklist opérationnelle avant tout changement de paire (D29 / 1.10).** Le changement de paire de
`vwpreversion` ne se fait pas au clic : **avant**, énumérer au courtier les positions ouvertes **et** les
ordres pendants sur l'**ancienne** paire pour cette stratégie, les rapprocher de l'état sauvegardé, et
fermer ou adopter **explicitement** (décision écrite, comme D16/D26) ; **après**, vérifier au courtier que
l'ancienne paire est à plat et qu'aucun état de la stratégie ne décrit encore une position dessus. Côté
lecture : `GET /v3/accounts/-014/openPositions` et `/pendingOrders`, filtrés sur l'instrument sortant.
`updatePositions(oandaSymbol)` ne surveille **que** le symbole résolu : une position GBP_JPY laissée
ouverte après le re-pointage vers USD_CHF resterait ouverte et **non surveillée**.

### 12.8 Lot 1 livré et vérifié (commit `8cb3e909`)

Stories **1.9, 1.8, 1.1, 1.2** implémentées et poussées sur `feature/live-path-instrument-and-stop`.
**Vérification faite à la main par l'orchestrateur, pas déclarée par l'agent qui a écrit le code** :
`./mvnw -B -DskipITs -Dspotbugs.skip=true -pl trading-strategies -am -Dtest=LiveStrategyRunnerInstrumentTest,StrategySymbolReconciliationTest -Dsurefire.failIfNoSpecifiedTests=false test`
→ **Tests run: 13, Failures: 0, Errors: 0 — BUILD SUCCESS**.

Les assertions sont les bonnes : `vwpreversion` résolu en **USD_CHF** avec la **source** `strategies.vwpreversion.instrument` ; `consecbar` reste **GBP_JPY** (non-régression) ; les 4 stratégies nouvellement configurées résolvent leur paire ; une config absente ou incohérente **refuse le démarrage** en nommant la stratégie et la paire attendue ; et les 3 stratégies qui filtrent sur `bar.symbol()` (**E4**) cessent de filtrer après réconciliation — c'est le test qui empêche le correctif de devenir une panne muette.

Fichiers : `config/live-config.json`, `trading-core/.../Strategy.java` (+ méthode `default`
`reconcileInstrument`), `LiveStrategyRunner.java` (`resolveInstrument` + `verifyInstrumentConsistency`),
et les 3 stratégies à filtre (`CompositeMomentumRankingStrategy`, `MonthWeekPhaseStrategy`,
`LtRSI3Momentum`, `symbol` passé de `final` à non-`final`).

**Somme des `computedRiskPct` déployés = 2,3 %** (0,6 + 0,75 + 0,4 + 0,25 + 0,3) ≤ 3 % : le plafond de
compte est tenu en attendant D24.

### 12.9 Deux réserves à traiter en revue, pas à oublier

| # | Réserve | Détail |
|---|---------|--------|
| **R1** | Les `computedRiskPct` des 4 stratégies ajoutées reposent sur des métriques **non validées** | Les `backtestMetrics` de `compmomentum`, `monthweekphase`, `ltrsi3` et `nfpweek` n'ont **pas** de walk-forward ; leur champ `source` le dit honnêtement (« docker-compose note; no full walk-forward report », « event strategy, no backtest (pre-fix, suspect) »). Or `computedRiskPct` **dérive** de ces métriques (`_riskFormula`). La valeur est donc petite et plafonnée, mais sa provenance est faible : à re-dériver quand Epic 41 aura produit de vrais chiffres. |
| **R2** | `toOandaSymbol()` dégrade en `null` sur les chemins état/moniteur | Jackson `put(String,String)` est null-safe, donc on écrit `"instrument": null` dans le fichier d'état et dans le moniteur au lieu de lever. **Rien ne lit encore ce champ** (1680/1715 lisent celui de la **config**, via `hasNonNull`). Mais la story **1.10** va comparer l'instrument **sauvegardé** au résolu : elle doit traiter un `null` JSON comme « inconnu », jamais comme une incohérence. C'est exactement la classe de bug de `lastBarTime` (un `null` explicite lu `has()` + `asText()` → « null » → `Instant.parse` lève, et tout le restore est perdu). |



