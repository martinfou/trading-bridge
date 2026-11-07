---
title: 'Refonte de la télémétrie exécutive du Trading Desk et synchronisation client-serveur des trades/stratégies IBKR'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_commit: 'ca381208900d61947232776fc01dde24bc3ad91a'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Sur la vue `Trading Desk` (`LiveTradingView.vue`), le bandeau supérieur de 8 cartes affiche des compteurs à `0` (`Live Running`, `Paper Running`, `Allocated Capital`, `Stale Heartbeats`, `Drawdown Paused`) et tronque la valeur `Broker Net Liq` (`C$111,346.0...`). De plus, la liste des trades IBKR est vide (`0 trades`) dès que la session quotidienne d'IB Gateway réinitialise `reqExecutions`, car `ControlPlaneServer` ne relit pas les journaux de fills et d'états persistés sur son système de fichiers (`data-futures/fills/*-paper.jsonl`, `data-futures/state/*-cursor.json`, `data/state/**/live-strategy-state-*.json`, `~/.local/share/hermes-ibkr/pnl_state.json`), et le client Desktop ne permet pas de configurer l'URL HTTP du serveur `ControlPlaneServer` en mode client-serveur.

**Approach:**
1. **Architecture 100 % Client-Serveur sans aucun nom d'hôte ni appel SSH codé en dur** :
   - Côté serveur (`ControlPlaneServer` / `LaneTelemetryCollector`), lire uniquement les répertoires locaux configurables du serveur (`data-futures/fills/`, `data-futures/state/`, `data/state/`, `pnl_state.json` et `events.db`) pour fusionner les trades/round-trips IBKR avec `reqExecutions` d'IB Gateway et exposer les stratégies actives des lanes dans `/api/control/summary`.
   - Côté client (`controlPlaneConfig.ts`, `App.vue`, `LiveTradingView.vue`), permettre à l'opérateur de configurer l'URL HTTP du Control Plane (ex: `http://localhost:8080` ou tout serveur distant / tunnel HTTP, persistée dans `localStorage`).
2. **Ruban exécutif de classe mondiale (6 cartes auto-adaptatives sans troncature)** dans `LiveTradingView.vue` : **Portfolio Net Liq (NAV)**, **Floating P&L & Exposure**, **Realized P&L (Tracked Trades)**, **Margin Util & Free Buffer**, **Active Strategies** et **Desk Health & PDT Guard**, suivi de la grille des stratégies actives et de la table des exécutions avec leurs tags de stratégies.

## Boundaries & Constraints

**Always:**
- Aucune dépendance à un hôte spécifique (aucun `srv108`, aucun appel `ssh` dans le code). Tout passe par les chemins locaux du `ControlPlaneServer` et le protocole HTTP client-serveur.
- Respecter strictement la règle anti-sentinelle IBKR (`Double.MAX_VALUE` / `1.7976931348623157e308`, `abs(v) > 1e290` ignoré sur `realizedPNL` et `commission`).
- Conserver la devise native de chaque exécution (`USD` pour les contrats CME `MGC` même si la NAV du compte IBKR est en `CAD`) et convertir dynamiquement dans la devise d'affichage sélectionnée (`selectedCurrency`) côté UI.

**Ask First:**
- Toute modification du format des fichiers d'état produits par `IbkrLiveRunner` ou `LiveStrategyRunner`.
- Tout changement de port ou de `clientId` IBKR réservé (`11`, `90`, `98`).

**Never:**
- Coder en dur un nom de serveur (`srv108`) ou exécuter une commande `ssh` depuis Java ou TypeScript.
- Inventer un P&L à `0.0` à la place de la sentinelle IBKR `1.7976931348623157e308` sur une jambe d'ouverture seule.
- Tronquer les montants monétaires dans le bandeau supérieur (`stats-strip`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| IBKR Gateway `reqExecutions` vide après reset quotidien | `ibkrBroker.getRecentTrades()` retourne `[]`, mais les journaux locaux (`*-paper.jsonl` / `pnl_state.json` / `events.db`) contiennent les fills | Les round-trips et fills IBKR apparaissent dans `venue.trades` avec `entryPrice`, `exitPrice`, `realizedPnL` (`USD`) et `strategyTag` | Si aucun fichier journal n'existe localement, retourne uniquement les trades live `reqExecutions` sans erreur |
| Dédoublonnage `jsonl.*` vs `execId` natif IBKR | Le journal contient à la fois un `exec_id` natif (`0000e1a7...`) et un round-trip `jsonl.MGC.<timestamp>.01` à ±20s | Une seule entrée est conservée (l'exécution native enrichie de `entry_price` et `opened_at_utc`) | N/A |
| Stratégies actives dans les répertoires d'état locaux du serveur | Fichiers présents dans `data-futures/state/*-cursor.json` et `data/state/**/live-strategy-state-*.json` | `/api/control/summary` inclut les stratégies actives avec `strategyId`, `symbol`, `mode`, `lane`, `realizedPnL`, `tradesCount`, `inTrade` et `lastEventAt` | Si un fichier JSON est illisible, il est ignoré individuellement avec log `debug` |
| Connexion Client-Serveur configurable | L'utilisateur modifie l'URL du Control Plane dans l'UI (ex: `http://localhost:8080` ou serveur distant) | `controlPlaneUrl` est persisté dans `localStorage` et toutes les requêtes REST/WS ciblent ce serveur | En cas d'erreur réseau, la barre de statut affiche `Control Plane Disconnected` |
| Grands montants dans le ruban supérieur (`C$111,346.04`) | Fenêtre desktop standard (1280px–1600px) avec devise `CAD` ou `JPY` | Les 6 cartes exécutives s'adaptent sans coupure (`clamp` typographique et grille auto-fit) | N/A |

</frozen-after-approval>

## Code Map

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/LaneTelemetryCollector.java` -- Collecteur lisant sur le système de fichiers local du serveur (`data-futures/fills/*-paper.jsonl`, `data-futures/state/*-cursor.json`, `data/state/**/live-strategy-state-*.json`, `pnl_state.json`) et reconstruisant les trades IBKR et l'état des stratégies.
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java` -- Fusion des trades IBKR persistés dans `collectBrokerBalances()` et enrichissement de `/api/control/summary` avec les runs issus de `LaneTelemetryCollector`.
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/LaneTelemetryCollectorTest.java` -- Tests unitaires du parsing des journaux de fills/états, de la sentinelle `1.797e308` et du dédoublonnage à ±20s.
- `desktop/src/composables/controlPlaneConfig.ts` -- Persistance de `controlPlaneUrl` dans `localStorage` pour la connexion client-serveur.
- `desktop/src/types/control-plane.ts` -- Ajout des champs optionnels `lane`, `tradesCount` et `inTrade`.
- `desktop/src/views/LiveTradingView.vue` & `desktop/src/App.vue` -- Ruban exécutif à 6 cartes non tronquées, sélecteur d'URL Control Plane client-serveur, affichage des trades IBKR avec stratégies et flotte des stratégies actives.

## Tasks & Acceptance

**Execution:**
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/LaneTelemetryCollector.java` -- Implémenter la lecture locale des journaux de fills IBKR (`*-paper.jsonl`, `pnl_state.json`) et des fichiers d'état de stratégies (`data-futures/state/`, `data/state/`) sans aucune dépendance SSH/hôte -- Permet à n'importe quelle instance `ControlPlaneServer` d'exposer ses stratégies et son historique de trades IBKR.
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java` -- Intégrer `LaneTelemetryCollector` dans `/api/broker-accounts/balances` et `/api/control/summary` -- Restitue les trades IBKR avec leurs tags de stratégies et les runs actifs via l'API REST standard.
- [x] `trading-runtime/src/test/java/com/martinfou/trading/runtime/LaneTelemetryCollectorTest.java` -- Tester tous les cas de la matrice I/O (sentinelle `1.797e308`, appariement round-trip, dédoublonnage ±20s, états de stratégies) -- Garantit la fiabilité des calculs P&L et du rattachement aux stratégies.
- [x] `desktop/src/composables/controlPlaneConfig.ts`, `desktop/src/App.vue`, `desktop/src/types/control-plane.ts` & `desktop/src/views/LiveTradingView.vue` -- Persister `controlPlaneUrl` (éditable depuis la barre de statut / Trading Desk) et remplacer les 8 cartes locales par le ruban exécutif à 6 cartes sans troncature -- Permet au client Desktop de se connecter à n'importe quel serveur Control Plane et affiche clairement les KPIs, stratégies et trades IBKR.

**Acceptance Criteria:**
- Given `ControlPlaneServer` disposant de fichiers `*-paper.jsonl` ou `pnl_state.json` dans ses répertoires de données locaux, when `/api/broker-accounts/balances` est appelé, then les trades IBKR incluent `entryPrice`, `exitPrice`, `realizedPnL` (`USD`) et `strategyTag` sans aucun appel SSH ni nom d'hôte codé en dur.
- Given un utilisateur souhaitant connecter le Desktop à un `ControlPlaneServer` distant ou via port forwarding, when il modifie l'URL du Control Plane dans l'interface, then l'application persiste l'URL et interroge ce serveur en pur HTTP client-serveur.
- Given un montant `Portfolio Net Liq` de `C$111,346.04`, when la vue `Trading Desk` est affichée, then les 6 cartes exécutives affichent les montants complets sans troncature.

## Spec Change Log

## Verification

**Commands:**
- `mvn test -pl trading-runtime -Dtest=LaneTelemetryCollectorTest,BrokerAccountSummaryApiTest` -- expected: `BUILD SUCCESS`
- `npm --prefix desktop run build` -- expected: compilation TypeScript et bundle Vite réussis sans erreur

## Suggested Review Order

**Collecte locale des trades IBKR et états de stratégies (Serveur)**

- Point d'entrée fusionnant `pnl_state.json`, `*-paper.jsonl` et `reqExecutions` avec filtre anti-sentinelle et dédoublonnage ±20s.
  [`LaneTelemetryCollector.java:149`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/LaneTelemetryCollector.java#L149)

- Découverte locale des stratégies actives (`*-cursor.json` et `live-strategy-state-*.json`) avec agrégation P&L par stratégie.
  [`LaneTelemetryCollector.java:375`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/LaneTelemetryCollector.java#L375)

- Enrichissement de `/api/control/summary` et `/api/broker-accounts/balances` (avec repli hors-ligne IBKR).
  [`ControlPlaneServer.java:2115`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java#L2115)

**Ruban exécutif 6 cartes, attribution stratégies et client-serveur (Desktop UI)**

- Agrégateurs multi-devises, attribution par tag de stratégie et KPIs exécutifs sans troncature.
  [`LiveTradingView.vue:148`](../../desktop/src/views/LiveTradingView.vue#L148)

- Grille auto-adaptative à 6 cartes exécutives et section d'attribution des stratégies connectées.
  [`LiveTradingView.vue:1182`](../../desktop/src/views/LiveTradingView.vue#L1182)

- Persistance et normalisation du `controlPlaneUrl` dans `localStorage` avec éditeur dans la barre de statut.
  [`controlPlaneConfig.ts:6`](../../desktop/src/composables/controlPlaneConfig.ts#L6)

- Sélecteur d'URL du serveur Control Plane dans le pied de page global.
  [`App.vue:160`](../../desktop/src/App.vue#L160)

**Tests et types**

- Tests unitaires couvrant la sentinelle `1.797e308`, le dédoublonnage ±20s et la lecture des états de lanes.
  [`LaneTelemetryCollectorTest.java:25`](../../trading-runtime/src/test/java/com/martinfou/trading/runtime/LaneTelemetryCollectorTest.java#L25)

- Types TypeScript enrichis pour la télémétrie multi-venues et les exécutions brokers.
  [`control-plane.ts:95`](../../desktop/src/types/control-plane.ts#L95)

