# Paper Trading IBKR Futures - Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Trader des futures CME (MES, MNQ) en **paper mode** via IBKR Gateway cette semaine (2026-08-24 → 2026-08-28), avec zéro exposition réelle.

**Architecture:** Le runtime a déjà `ExecutionLabel.PAPER_IBKR` (port 7497) et `BrokerAccountRegistry` câblé. Le travail = (1) consolider les 3 audits (IBKR / futures / runtime), (2) corriger les P0, (3) brancher le client IBKR v1045.01 sur les contrats futures réels, (4) valider avec 1 contrat MES en paper, (5) check-list go-live paper.

**Tech Stack:** Java 21+ (mise), Maven 4.x, IBKR TWS API v1045.01 (protobuf, déjà restauré dans `com.ib.client`), IB Gateway local `~/ibgateway` (port 7497 paper / 7496 live), futures CME micro: MES $5/pt, MNQ $2/pt, M2K $5/pt, EMD $100/pt.

---

## Contexte - état actuel (vérifié 2026-08-24)

**Déjà en place:**
- `ExecutionLabel.PAPER_IBKR` + `isIbkrBroker()` (trading-runtime)
- `BrokerAccountRegistry.ibkrConnection(accountId, paper=true)` → port 7497
- `IbkrConnectionConfig.DEFAULT_PAPER_PORT = 7497`
- Client officiel IBKR v1045.01 restauré dans `com.ib.client` (203 classes protobuf, protobuf-java 4.29.5)
- IB Gateway installé à `~/ibgateway`
- Moteur futures sur master: `FuturesRegistry`, `MarginTracker`, `CmeFuturesCalendar`, `CmeAutoRolloverManager`, `IbkrBracketOrderRouter`, `IbkrContractResolver`, `IbkrAccountCache`, `IbkrHeartbeatWatchdog`, `IbkrHistoricalDataDownloader`
- `IbkrBroker` (trading-broker, 129 l) implémente `Broker`

**Bugs connus à corriger (vus en lecture directe, avant audits):**
- `IbkrBroker.cancelOrder()` est un **fake**: il émet un événement CANCELLED sans envoyer d'annulation au broker → kill switch sans effet réel
- `IbkrBroker.submitOrder()` assume un **fill synchrone** (`order.fill()` + `result.fillPrice()`) alors que IBKR est asynchrone (ordre travaillé → callback fill)
- `toIbkrSymbol()` naïf: supprime `_`,`/`,`-` → pas de résolution de contrat futures (MES doit avoir contract month + exchange + currency)
- `IbkrBroker` ne gère que MARKET orders → bracket orders / stop-loss futures impossibles
- `BrokerAccountRegistry` par défaut = OANDA (syntheticDefault) → il faut un fichier `broker-accounts.local.json` avec provider IBKR
- Code custom `com.martinfou.trading.data.ibkr.*` (8 fichiers, 398 l) écrit contre l'**ancienne API** → à migrer v1045

---

## Phase A — Consolider les 3 audits (dépendance)

**Objectif:** Intégrer les findings des audits `deleg_e5b6bec1` (worktrees `/tmp/tb-audit-{a,b,c}`, branches `feature/ibkr-audit-{a,b,c}`).

**Task A.1: Récupérer les rapports AUDIT-*.md**
- Files: `/tmp/tb-audit-a/AUDIT-IBKR.md`, `/tmp/tb-audit-b/AUDIT-FUTURES.md`, `/tmp/tb-audit-c/AUDIT-RUNTIME.md`
- Copier dans `docs/audits/2026-08-24-{ibkr,futures,runtime}.md` sur la branche principale
- Extraire la liste consolidée P0/P1/P2 dans `docs/audits/2026-08-24-consolidated.md`

**Task A.2: Travailler sur `origin/master` (base commune)**
- Créer `feature/paper-trading` depuis `origin/master` (33c636ab) dans le repo principal
- Rebaser les fixes P0 des worktrees audit si le code est identique, sinon re-implementer proprement sur la branche
- Validation: `git diff origin/master..feature/paper-trading --stat` montre les fichiers corrigés

---

## Phase B — Configuration du compte paper IBKR

**Objectif:** Le runtime sait se connecter à un compte IBKR paper. Il faut la config locale.

**Task B.1: Créer `data/runtime/broker-accounts.local.json`** (gitignored)
```json
{
  "accounts": [
    {
      "id": "paper",
      "provider": "IBKR",
      "accountIdEnv": "IBKR_ACCOUNT_ID",
      "hostEnv": "IBKR_GATEWAY_HOST",
      "portEnv": "IBKR_GATEWAY_PORT",
      "clientIdEnv": "IBKR_CLIENT_ID",
      "defaultPortPaper": 7497,
      "defaultPortLive": 7496
    }
  ]
}
```
- Ne PAS committer (fichier .local.json)
- Environnement requis: `IBKR_ACCOUNT_ID` = compte paper IBKR de Martin (ex: `DU1234567`), `IBKR_GATEWAY_PORT=7497`

**Task B.2: Test d'infrastructure Gateway**
- Vérifier que `~/ibgateway` démarre et écoute sur 7497: `ss -tlnp | grep 7497`
- Créer `scripts/ibkr-gateway-up.sh`: lance le Gateway en mode paper (headless, `--paper`)
- Validation: port 7497 ouvert, pas 7496

**Task B.3: Smoke test connexion (sans ordre)**
- Test unitaire `BrokerAccountRegistryTest.ibkrPaperConnection()`: charge `.local.json`, résout `ibkrConnection("paper", true)`, assert port 7497
- Test manuel: connecter le Gateway et vérifier `fetchAccountSummary()` retourne le solde du compte paper (via un petit main de test, PAS dans les tests CI qui tournent sans Gateway)

---

## Phase C — Fixes P0 (issus des audits, consolidés)

**Objectif:** Corriger ce qui casse l'exécution paper. Priorité absolue: kill switch réel, contrats futures résolus, pas de double exécution.

**Task C.1: Kill switch réel (P0)**
- Files:
  - Modify: `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java` (cancelOrder)
  - Test: `trading-broker/src/test/java/com/martinfou/trading/broker/IbkrBrokerTest.java`
- `cancelOrder(brokerOrderId)` doit appeler `client.cancelOrder(brokerOrderId)` (nouvelle méthode sur `IbkrGatewayClient`) et retourner le résultat réel du broker, pas un fake `OrderSubmitResult.filled()`
- TDD: écrire le test qui assert `client.cancelOrder` appelé quand `cancelOrder()` est invoqué
- Validation: `mvn -q -Dspotbugs.skip=true -pl trading-broker test -Dtest=IbkrBrokerTest`

**Task C.2: Résolution de contrat futures (P0)**
- Files:
  - Modify: `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java` (toIbkrSymbol → toIbkrContract)
  - Modify: `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrGatewayClient.java` (interface: `placeMarketOrder(String symbol,...)` → `placeMarketOrder(Contract,...)` ou accepter le mapping)
  - Modify: `trading-data/src/main/java/com/martinfou/trading/data/ibkr/TcpIbkrGatewayClient.java`
  - Use: `IbkrContractResolver` (sur master) pour mapper MES/MNQ/M2K/EMD → `Contract(symbol="MES", secType="FUT", exchange="CME", currency="USD", lastTradeDateOrContractMonth=<front month>)`
- Le front month vient de `CmeFuturesCalendar` / `CmeAutoRolloverManager`
- TDD: `IbkrBrokerTest` assert que MES → Contract CME avec contract month courant; test de rollover quand le front month change
- Validation: `mvn -q -Dspotbugs.skip=true -pl trading-broker,trading-data test -Dtest=IbkrBrokerTest`

**Task C.3: Fill asynchrone (P0)**
- Files:
  - Modify: `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java`
  - Test: `trading-broker/src/test/java/com/martinfou/trading/broker/IbkrBrokerTest.java`
- `submitOrder()` ne doit plus faire `order.fill()` synchrone: il place l'ordre, retourne `OrderSubmitResult.accepted(orderId)` et attend le callback fill du client (via `addEventListener` / `OrderState`)
- Le `BrokerEvent.fill` est émis quand le client reçoit le fill asynchrone
- TDD: test avec un `StubIbkrGatewayClient` qui simule le fill après un délai; assert que le BrokerEvent.fill est émis
- Validation: `mvn -q -Dspotbugs.skip=true -pl trading-broker test -Dtest=IbkrBrokerTest`

**Task C.4: Anti-double-exécution (P0, selon AUDIT-RUNTIME)**
- Files:
  - Modify: `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java` (ou le garde identifié par l'audit)
  - Test: test existant + nouveau test de garde
- S'appliquer aux findings exacts de l'audit runtime (probablement: verrou par runId dans RunManager, idempotence de submission, réconciliation avant re-submit au restart)
- Validation: suite de tests runtime verte

**Task C.5: Restauration d'état au restart (P0, selon AUDIT-RUNTIME)**
- Files: selon AUDIT-RUNTIME (EventStore / RunManager / DailyReconciliationService)
- Au restart: rejouer les événements, reconstruire les positions, re-réconcilier avec le broker AVANT de placer un nouvel ordre
- Validation: test de restart simulé (créer un run, "tuer" le process, recharger, assert positions cohérentes)

---

## Phase D — Client IBKR v1045 + contrats

**Objectif:** Le client custom `com.martinfou.trading.data.ibkr.*` doit parler à l'API v1045 correctement.

**Task D.1: Migrer `TcpIbkrGatewayClient` vers v1045**
- Files:
  - Modify: `trading-data/src/main/java/com/martinfou/trading/data/ibkr/TcpIbkrGatewayClient.java`
  - Modify: `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrGatewayClient.java` (interface)
  - Test: `trading-data/src/test/java/.../TcpIbkrGatewayClientTest.java` (mock ou enregistrement de trame)
- Étendre `com.ib.client.DefaultEWrapper` (pas `implements EWrapper`), utiliser `Decimal` pour les quantités, `CommissionAndFeesReport` (pas `CommissionReport`), `error(int, long, int, String, String)` (nouvelle signature)
- `IbkrConfigurationTool` et `IbkrHistoricalDataDownloader` (sur master): remplacer `new DefaultEWrapper()` par `new com.ib.client.DefaultEWrapper()` et retirer le custom
- Validation: `mvn -q -Dspotbugs.skip=true install` complet + tests

**Task D.2: Pacing et market data farms (P1 selon audit)**
- Files: selon AUDIT-IBKR (`IbkrHeartbeatWatchdog`, gestion 2104/2106)
- Rate limiter: max 50 messages/s (pacing IBKR), gérer les erreurs 2104/2106 (market data farm connected/disconnected)
- Validation: tests unitaires du rate limiter

**Task D.3: Reconnexion pendant ordre travaillé (P1 selon audit)**
- Files: selon AUDIT-IBKR
- Si le Gateway reconnecte pendant un ordre travaillé: ne pas perdre l'ordre (requery open orders au reconnect: `reqAllOpenOrders`)
- Validation: test de scénario reconnect avec ordre en vol

---

## Phase E — Réconciliation paper

**Objectif:** Le runtime sait comparer son état local vs l'état du broker paper.

**Task E.1: Réconciliation positions/orders**
- Files:
  - Modify: `trading-runtime/src/main/java/com/martinfou/trading/runtime/DailyReconciliationService.java`
  - Test: `DailyReconciliationServiceTest.java`
- Comparer positions locales (EventStore) vs `client.fetchOpenPositions()` + `reqAllOpenOrders`
- Détecter: position fantôme, ordre manquant, fill non enregistré
- En paper: alert uniquement (pas de correction automatique)
- Validation: test avec positions divergentes → rapport d'écart

**Task E.2: Commandes de contrôle paper**
- Files: `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java` (vérifier les endpoints existants)
- Endpoints vérifiés: connect, disconnect, submit, cancel, kill, status, positions, reconcile
- En mode PAPER_IBKR: `kill` = cancel tous les ordres ouverts + flatten positions paper (via client)

---

## Phase F — Validation paper (smoke test réel)

**Objectif:** Prouver que le système place, remplit, enregistre et réconcilie un ordre futures réel en paper.

**Task F.1: Pré-vol Gateway**
- `~/ibgateway` démarré, connecté au compte paper
- `IBKR_ACCOUNT_ID`, `IBKR_GATEWAY_PORT=7497` exportés
- `mvn -q -Dspotbugs.skip=true install` vert sur `feature/paper-trading`

**Task F.2: Smoke test manuel 1 contrat MES**
- Lancer le runtime en `PAPER_IBKR` avec un run de test (stratégie simple ou commande manuelle)
- Séquence: connect → positions (0) → submit 1 MES market → attendre fill → getPositions (1) → cancel/flatten → reconcile (0)
- Vérifier dans le Gateway paper que l'ordre apparaît et se remplit
- Critère de succès: PnL paper affiché, positions cohérentes entre runtime et Gateway

**Task F.3: Test kill switch réel**
- Placer un ordre MES limite volontairement non rempli (prix éloigné)
- Déclencher `kill` → vérifier dans le Gateway que l'ordre est bien annulé (pas juste localement)
- Critère de succès: ordre annulé côté broker, positions inchangées

**Task F.4: Test restart**
- Placer un ordre fill, tuer le process, relancer, vérifier que la position est rechargée et réconciliée

---

## Phase G — Check-list go-live paper

**Objectif:** Documenter les conditions pour le premier run paper quotidien.

**Task G.1: Rédiger `docs/paper-trading-runbook.md`**
- Pré-vol: Gateway up, port 7497, compte paper, config .local.json, env vars
- Lancement: commande exacte du runtime PAPER_IBKR
- Surveillance: où regarder (logs, ControlPlane, réconciliation)
- Kill: comment annuler tout
- Limitations paper IBKR: pas de garantie de fill (fill simulé au dernier prix), pas de données de marché temps réel de qualité, heures de maintenance 17:00-18:00 ET

**Task G.2: Mettre à jour le skill `trading-bridge-backtesting`**
- Ajouter la section paper trading IBKR (setup, runbook, pièges)
- Mettre à jour `references/paper-trading-audit.md` avec les findings réels

---

## Ordre de build et dépendances

| Phase | Dépend de | Effort |
|-------|-----------|--------|
| A (consolidation audits) | audits finis | S |
| B (config paper) | A (parallèle OK) | S |
| C (fixes P0) | A | M |
| D (client v1045) | A | M |
| E (réconciliation) | C, D | S |
| F (validation paper) | B, C, D, E | M |
| G (runbook) | F | S |

**Critère de sortie (DoD):** un ordre MES 1 contrat placé en paper, rempli, réconcilié, annulé via kill switch, avec restart testé. Runbook écrit. Skill mis à jour.

## Règles de sécurité
- **PAS de compte live** (7496) : tout est câblé paper (7497)
- `IBKR_ACCOUNT_ID` doit être le compte **DU*** paper, jamais le compte réel
- Les audits ne se connectent pas au Gateway; les smoke tests F.2-F.4 sont manuels avec Martin
- Review avant merge (BMad): `delegate_task` reviewer sur `git diff develop...feature/paper-trading` avant merge dans master
- Commits CRISPE, push immédiat après chaque commit
