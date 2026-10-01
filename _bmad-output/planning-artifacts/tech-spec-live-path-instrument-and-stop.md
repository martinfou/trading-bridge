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
