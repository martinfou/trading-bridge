---

## status: accepted

date: '2026-05-24'
deciders: Martin Fournier
consulted:

- Winston (System Architect)
- John (Product Manager)
- Amelia (Senior Software Engineer)
- Mary (Business Analyst)
- Sally (UX Designer)
inputDocuments:
- _bmad-output/brainstorming/brainstorming-session-2026-05-24-1430.md
- _bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md
- _bmad-output/project-context.md
supersedes: []
related:
- ADR-13-01
- ADR-13-02
- ADR-13-06
epic: 13
title: Distributed Platform — Hub central, nœuds d'exécution, historique unifié

# Architecture Decision Record — Plateforme distribuée (Epic 13 extension)

*Décisions formelles pour exécuter Trading Bridge sur une ou plusieurs machines (backtest / paper / live), connectées à un serveur central de monitoring et d'historique.*

## Contexte

### Besoin

Martin Fournier souhaite :

- Exécuter le moteur de trading sur **une ou plusieurs machines** (backtest, paper, live séparés ou colocalisés).
- Connecter tous les nœuds à **un serveur central** pour supervision et historique unifié.
- Déployer en mode **tout-en-un** (1 PC) ou **distribué** (1 PC par mode) sans changer le contrat applicatif.

### État actuel (post stories 13.x en cours)


| Composant         | Statut                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------- |
| `trading-runtime` | Module existant : `ControlPlaneServer` (Javalin), `RunManager`, `SqliteEventStore`, `RunEventHub` |
| `RunEvent` v1     | Schéma versionné dans `trading-backtest`                                                          |
| `StoredRunEvent`  | Séquence monotone par run, pagination `afterSequence`                                             |
| Dashboard         | Python adjacent (`dashboard/`) — client thin, hors reactor Maven                                  |
| Multi-nœud        | Non implémenté                                                                                    |


### Session de référence

Brainstorming party mode : `_bmad-output/brainstorming/brainstorming-session-2026-05-24-1430.md`

- 48 questions de cadrage
- Analyse morphologique (6 paramètres × 3 combos)
- 38 scénarios d'échec (reverse brainstorming)
- Convergence agents : hub passif, exécution locale, rollout phased

---

## Principes (extension Epic 13 §2)

Les principes immuables de `architecture-epic-13-platform-runtime.md` restent valides. Ce document **précise** leur application en déploiement distribué :

1. **Java engine = source de vérité d'exécution** — sur le **nœud worker**, pas sur le hub.
2. **Hub = source de vérité du journal** — historique append-only agrégé, pas des ordres en vol.
3. **Clients thin** — dashboard / TUI projettent l'état ; ils ne calculent pas PnL ni ne routent ordres.
4. **Boring tech** — HTTP JSON, SQLite ; pas de Kubernetes, consensus distribué, ni microservices au MVP.
5. **Phased rollout** — mono-nœud fiable avant multi-PC.

---

## Vue d'ensemble

### Phase A — Mono-nœud (Sprint 13)

```
┌─────────────────────────────────────────────────────────┐
│  PC unique                                               │
│  ┌─────────────────┐    ┌──────────────────────────────┐  │
│  │ Worker          │───►│ trading-runtime              │  │
│  │ BacktestEngine  │    │ ControlPlaneServer (HTTP/WS) │  │
│  │ / AutoTrader    │    │ RunManager + SqliteEventStore│  │
│  └────────┬────────┘    └──────────────┬───────────────┘  │
│           │                            │                   │
│           ▼                            ▼                   │
│     OANDA/IBKR (paper/live)     Dashboard Python (client)  │
└─────────────────────────────────────────────────────────┘
```

### Phase B — Distribué (Sprint 15+)

```
                    ┌──────────────────────────────┐
                    │  Hub — Control Plane         │
                    │  trading-runtime             │
                    │  EventStore central (SQLite) │
                    │  NodeRegistry                │
                    └──────────────┬───────────────┘
                                   │ HTTP JSON
           ┌───────────────────────┼───────────────────────┐
           ▼                       ▼                       ▼
    ┌─────────────┐         ┌─────────────┐         ┌─────────────┐
    │ PC Backtest │         │ PC Paper    │         │ PC Live     │
    │ Worker      │         │ Worker      │         │ Worker      │
    │ + Outbox    │         │ + Outbox    │         │ + Outbox    │
    └─────────────┘         └──────┬──────┘         └──────┬──────┘
                                   └──────────► OANDA/IBKR ◄─┘
```

---

## Décisions

### ADR-13-07 — Rôle du hub central (observateur passif)


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** Un hub peut être conçu comme orchestrateur actif, source de vérité des positions, ou simple observateur. Centraliser l'exécution simplifie le monitoring mais crée un SPOF et des risques de double-fill broker.

**Décision :** Le hub central (`ControlPlaneServer` / `RunManager`) est un **observateur passif** au MVP :

- Ingère et persiste les événements de run.
- Expose API REST + WebSocket pour monitoring et replay.
- Permet start/stop run (contrôle minimal).
- **Ne route pas** les ordres vers OANDA/IBKR.
- **N'est pas** source de vérité des positions ou ordres ouverts en live.

**Conséquences :**

- ✅ Pas de SPOF sur l'exécution trading.
- ✅ Même modèle pour backtest, paper et live (nœuds homogènes).
- ✅ Aligné avec ADR-13-01 (Java engine = vérité) en précisant *où* vit le moteur.
- ⚠️ Pas de kill-switch central garanti au MVP — stop = commande vers le nœud (ADR-13-04 inchangé côté API).
- ⚠️ Réconciliation broker↔journal différée (ADR-13-11).

**Alternatives rejetées :**


| Alternative                      | Raison du rejet                                                      |
| -------------------------------- | -------------------------------------------------------------------- |
| Hub route tous les ordres        | Latence, SPOF, double-fill, verrous distribués — hors scope solo dev |
| Hub = source de vérité positions | Conflit avec partition réseau ; complexité état partagé              |


---

### ADR-13-08 — Autorité d'exécution et routage broker


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** En déploiement distribué, la question « qui envoie l'ordre au broker ? » détermine les risques de double-fill et la latence live.

**Décision :**

- Chaque **nœud worker** exécute la stratégie et envoie les ordres au broker **localement** via `trading-broker` (Epic 4).
- Un seul writer broker par `(compte, symbole, stratégie)` — garanti par **lease** hub en phase multi-nœud live (ADR-13-12, Sprint 16).
- Le hub ne possède pas de credentials broker en MVP.

**Conséquences :**

- ✅ Latence minimale pour le live.
- ✅ Le nœud continue de trader si le hub est down (mode dégradé, ADR-13-09).
- ⚠️ État broker et journal hub peuvent diverger temporairement → réconciliation périodique requise.

---

### ADR-13-09 — Comportement hub indisponible (mode dégradé autonome)


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** Arrêter l'exécution live à la première panne hub est inacceptable. Continuer sans limite crée des nœuds fantômes hors gouvernance.

**Décision :**

1. **Hub down :** le nœud continue l'exécution avec ses règles locales (risk, stops).
2. Les événements sont **bufferisés** dans une outbox SQLite locale (même pattern que `SqliteEventStore`).
3. À la reconnexion : **replay** vers le hub avec idempotence (ADR-13-10).
4. **Fail-closed :** si le buffer local dépasse 80 % de capacité → gel des nouvelles exécutions + alerte.
5. **TTL autonome (Sprint 16) :** arrêt gracieux forcé si aucun sync hub depuis N heures (configurable).

**Alternatives rejetées :**


| Alternative                      | Raison                        |
| -------------------------------- | ----------------------------- |
| Arrêt immédiat au timeout hub    | Inacceptable en live          |
| Continuation illimitée sans sync | Nœud fantôme, audit incomplet |


---

### ADR-13-10 — Synchronisation événements et idempotence


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** Replay réseau, sync partielle et concurrence SQLite peuvent créer des trous ou doublons dans le journal central (scénarios Winston #7, Amelia #1/#7).

**Décision :**

**Ordre d'opérations local (invariant) :**

```
persist(EventStore) → then broadcast(RunEventHub)
```

**Clés d'idempotence :**

- Chaque événement porte un `eventId` (UUID v4) dans le payload ou en métadonnée.
- Ingestion hub : contrainte `UNIQUE(run_id, event_id)`.
- ACK hub : `{ lastAckedSequence, acceptedCount }`.
- Purge outbox nœud : **uniquement** si `localSeq <= lastAckedSequence`.

**Transport (phased) :**


| Phase | Mécanisme                                          | Sprint                         |
| ----- | -------------------------------------------------- | ------------------------------ |
| A     | Pull `GET /api/runs/{runId}/events?afterSequence=` | 13–14                          |
| B     | Push `POST /api/runs/{runId}/events` (batch)       | 15                             |
| C     | WebSocket/SSE live (existant `/ws/runs/{runId}`)   | 13 (local), 15 (remote fan-in) |


**Tri causal :** la **séquence monotone** `(runId, sequence)` est la clé de tri primaire ; `timestamp` UTC est informatif, pas suffisant seul.

**Conséquences :**

- ✅ « Never lose a run » tenable si ACK atomique respecté.
- ⚠️ Push batch différé au Sprint 15 — mono-nœud n'en a pas besoin.

---

### ADR-13-11 — Contrat de persistance et promesse produit MVP


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** Trois promesses possibles : always available, single source of truth temps réel, never lose a run. Un solo developer ne peut en tenir qu'une au lancement.

**Décision :**

**Promesse non négociable MVP :** **« Ne jamais perdre un run »** — statut, événements et résultat retrouvables après crash ou redeploy.

**Sacrifices volontaires :**

- Hub peut être indisponible quelques minutes (runs continuent localement).
- Vérité temps réel différée pendant partition (réconciliation à la reconnexion).

**Contrat de persistance (UI et API) :**

- Snapshot config **immuable** au `RUN_STARTED` (`configSnapshot` + `configHash`).
- API expose gaps de séquence explicitement — jamais « complet » sans preuve.
- UI affiche fraîcheur : « Dernier event il y a X s » — jamais vert sans heartbeat récent.

**Chemin stockage :**

- DB hub : `data/runtime/events.db` (configurable via env/property, voir `RuntimeDataPaths`).
- Volume persistant obligatoire en container/deploy.

---

### ADR-13-12 — Stratégie de déploiement phased (mono-nœud d'abord)


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** La distribution masque les bugs de persistance locale et amplifie split-brain, sync partielle et ghost journal.

**Décision :**


| Sprint | Scope                                                           | Critère de sortie              |
| ------ | --------------------------------------------------------------- | ------------------------------ |
| **13** | Mono-nœud : persist→broadcast, config snapshot, chemins DB      | 0 run perdu sur 10 crash tests |
| **14** | Idempotence, operator events, evidence pack export              | Export JSONL audit par runId   |
| **15** | Module `trading-node`, push batch, NodeRegistry, 1er PC distant | 2 nœuds → 1 hub, replay OK     |
| **16** | Réconciliation broker, parité backtest/live UI, mTLS, run lease | Statut RECONCILED/DIVERGED     |


**Explicitement hors scope MVP :**

- Kubernetes, consensus distribué, NATS/gRPC
- Multi-région, OAuth auth
- Hub route ordres broker

---

### ADR-13-13 — Module `trading-node` et registre nœuds


|            |                     |
| ---------- | ------------------- |
| **Statut** | Accepté (Sprint 15) |
| **Date**   | 2026-05-24          |


**Contexte :** Amelia propose d'étendre `trading-runtime` seul au MVP ; un agent sync distinct est nécessaire pour multi-PC.

**Décision :**

- **Sprint 13–14 :** toute évolution dans `trading-runtime` — pas de nouveau module Maven.
- **Sprint 15 :** nouveau module `**trading-node`** :
  - `NodeIdentity`, `SyncClient`, `LocalOutboxStore`, `HeartbeatScheduler`, `NodeMain`
  - Dépend de : `trading-runtime` (contrats API), `trading-backtest` (events)
- **Hub :** `NodeRegistry` dans `trading-runtime` — `POST /api/nodes/{nodeId}/heartbeat`, liste nœuds pour dashboard.

**Conséquences :**

- ✅ Acyclic graph Maven préservé (`trading-node` → `trading-runtime` → …).
- ⚠️ Pas de module `trading-events` séparé en Sprint 15 — DTOs restent dans `trading-runtime` jusqu'à stabilisation API.

---

### ADR-13-14 — Modèle de données historique centralisé


|            |            |
| ---------- | ---------- |
| **Statut** | Accepté    |
| **Date**   | 2026-05-24 |


**Contexte :** L'historique unifié doit supporter audit, replay, comparaison backtest/live et export evidence pack.

**Décision :** Modèle logique suivant (implémentation SQLite hub) :

```
NodeRecord       nodeId, hostname, capabilities[], registeredAt, lastHeartbeat, status
RunRecord        runId, nodeId?, strategyId, symbol, mode, status, startedAt, completedAt,
                 configSnapshot (JSON immuable), configHash, lastEventSeq
StoredRunEvent   sequence (monotone/run), eventId (UUID), event (RunEvent v1)
SyncCheckpoint   nodeId, runId, lastAckedSeq, lastSyncedAt
OperatorAction   actionId, runId, actor, action, timestamp, reason
```

**Types d'événements prioritaires** (extension ADR-13-02) :


| Type                               | Usage                                                          |
| ---------------------------------- | -------------------------------------------------------------- |
| `RUN_STARTED`                      | Snapshot config, capital, dataSource                           |
| `ORDER_SUBMITTED` / `ORDER_FILLED` | Traçabilité + réconciliation (`brokerFillId`, `correlationId`) |
| `RUN_ENDED`                        | Métriques finales                                              |
| `ERROR`                            | Alertes                                                        |
| `OPERATOR_ACTION`                  | Stop, kill — audit                                             |
| `NODE_HEARTBEAT`                   | Monitoring réseau                                              |


**Rétention MVP :** illimitée par run en SQLite local ; politique tiered hot/warm/cold reportée Sprint 16.

---

## API extensions (Phase B — Sprint 15)


| Method | Path                            | Description                                        |
| ------ | ------------------------------- | -------------------------------------------------- |
| POST   | `/api/nodes/{nodeId}/heartbeat` | `{ capabilities, activeRuns, metrics }`            |
| GET    | `/api/nodes`                    | Liste nœuds + statut + lastSeen                    |
| POST   | `/api/runs/{runId}/events`      | Batch ingestion `{ events[], lastSequence }` → ACK |
| GET    | `/api/runs/{runId}/export`      | Evidence pack JSONL (Sprint 14)                    |


Endpoints existants (ADR Epic 13 §5) inchangés.

---

## Registre des risques (extrait)


| ID  | Risque                         | Mitigation                  | Sprint |
| --- | ------------------------------ | --------------------------- | ------ |
| R1  | Race persist/broadcast         | ADR-13-10 invariant         | 13     |
| R2  | DB éphémère au redeploy        | `RuntimeDataPaths` + volume | 13     |
| R3  | UI voyant vert mensonger       | Fraîcheur + heartbeat       | 13     |
| R4  | Sync partielle (gap permanent) | ACK `lastAckedSequence`     | 15     |
| R5  | Double fill replay             | `brokerFillId` idempotence  | 15     |
| R6  | Ghost journal                  | `BrokerReconciler`          | 16     |
| R7  | Split-brain live               | `RunLeaseManager`           | 16     |
| R8  | Rabbit hole K8s/consensus      | ADR-13-12 phased rollout    | —      |


---

## Conformité avec ADR Epic 13 existants


| ADR existant                       | Relation                                           |
| ---------------------------------- | -------------------------------------------------- |
| ADR-13-01 Control Plane Java       | ✅ Renforcé — hub reste Java, clients thin          |
| ADR-13-02 RunEvent contrat         | ✅ Étendu — types audit, `eventId`, `schemaVersion` |
| ADR-13-04 Laravel/Python dashboard | ✅ Inchangé — client du hub, pas de logique trading |
| ADR-13-05 Pipeline promote         | ✅ Compatible — gates avant paper/live multi-nœud   |


---

## Critères de succès

### Phase A (Sprint 13)

- `POST /api/runs` lance backtest ; events persistés avant broadcast WebSocket
- Crash control plane → redémarrage → run + journal intact
- Config snapshot immuable vérifiable par hash
- Golden backtest CI reste vert

### Phase B (Sprint 15)

- 2 PCs (backtest + hub) — events replayés sans gap non signalé
- Hub down 1 h → nœud bufferise → replay complet à reconnexion
- Dashboard « État du réseau » avec fraîcheur par nœud

---

## Références

- Brainstorming : `_bmad-output/brainstorming/brainstorming-session-2026-05-24-1430.md`
- Epic 13 base : `_bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md`
- Code : `trading-runtime/` — `ControlPlaneServer`, `RunManager`, `SqliteEventStore`, `StoredRunEvent`
- Project rules : `_bmad-output/project-context.md`, `AGENTS.md`

---

## Historique


| Date       | Version | Changement                                                               |
| ---------- | ------- | ------------------------------------------------------------------------ |
| 2026-05-24 | 1.0     | Création — ADR-13-07 à ADR-13-14 acceptés suite brainstorming party mode |


