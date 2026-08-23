# Sprint Plan — Epic 46: IBKR Live Autopilot & Headless CME Micro-Futures Gateway Daemon

> **Phase** : Solutioning & Sprint Planning  
> **Module** : `trading-core`, `trading-broker`, `trading-runtime`, `trading-data`, `trading-strategies`  
> **Date** : 2026-08-23  
> **Durée estimée** : 1 Sprint (6 User Stories)

---

## 1. Objectifs du Sprint

1. **Livrer le Démon Autonome 24/5** : Mettre en place un conteneur headless intégrant IB Gateway et le runtime Java avec résilience aux reboots quotidiens (23:45 UTC).
2. **Implémenter le Routage d'Ordres Brackets Natifs CME** : Garantir que chaque ordre d'entrée est protégé par un Stop Loss et Profit Target natifs soumis directement sur la bourse CME via les groupes OCA d'IBKR.
3. **Sécuriser le Dimensionnement sur Petits Comptes** : Appliquer le bouclier de marge à 50% max et le dimensionnement à 1 micro-contrat sur 2 positions concurrentes max.
4. **Orchestrer le Déclenchement H1/D1 au Bar-Close** : Planifier l'évaluation automatique de l'ensemble multi-régime (Squeeze `MES`/`MNQ`, Pullback `M2K`, Macro Trend `MGC`) à `:00:05` post-clôture.

---

## 2. Décomposition des Tâches Techniques par Story

### Story 46.1: Headless IB Gateway Supervisor & Reconnection Sync
- [ ] Créer `Dockerfile.gateway` (base Alpine/Ubuntu avec IBC + IB Gateway headless).
- [ ] Développer `IbkrGatewaySupervisor.java` avec probe de liveness (30s) et reconnexion automatique.
- [ ] Développer `IbkrStateReconciler.java` synchronisant les positions/ordres IBKR avec SQLite post-reconnexion.

### Story 46.2: CME Native OCA Bracket Router & Hybrid Stop Engine
- [ ] Enrichir `IbkrBroker.java` pour supporter les types d'ordres brackets (`createBracket(parent, stopLoss, takeProfit)`).
- [ ] Implémenter la liaison OCA (`ocaGroup`, `ocaType`) sur les sockets TWS.
- [ ] Ajouter la méthode de mise à jour dynamique de stop au bar-close (`modifyStopLoss(orderId, newStopPrice)`).

### Story 46.3: Idempotent Order Tagging & Rejection Handling
- [ ] Mettre à jour `Order.java` pour générer un `orderTag` canonique déterministe (`SYM-STRAT-YYYYMMDD-HHMM-SIDE`).
- [ ] Ajouter une contrainte d'unicité et validation d'idempotence dans `SqliteTradeStore.java`.
- [ ] Intercepter les codes d'erreur IBKR (`201` Marge insuffisante, `110` Connexion perdue) dans `DefaultEWrapper.java`.

### Story 46.4: Autonomous CME Quarterly Rollover Engine
- [ ] Développer `CmeAutoRolloverManager.java` avec surveillance de volume 8 jours avant expiration.
- [ ] Générer et soumettre les ordres spread de calendrier `COMBO` via l'API IBKR.
- [ ] Réajuster le prix de revient et transférer les stops vers la nouvelle échéance.

### Story 46.5: Small-Account Margin Shield & Global Position Limiter
- [ ] Implémenter le filtre `SmallAccountMarginGuard.java` (règle des 50% max d'équité et 2 positions max).
- [ ] Valider le rejet automatique et non bloquant des signaux excédentaires.
- [ ] Ajouter des tests unitaires validant l'absence de liquidation sur scénarios de gap d'ouverture.

### Story 46.6: Deterministic H1/D1 Bar-Close Scheduler & Ensemble Runner
- [ ] Créer `BarCloseScheduler.java` déclenché à `:00:05` sur le fuseau `America/New_York`.
- [ ] Intégrer l'évaluation de l'ensemble : `LtBollingerSqueeze` (`MES`/`MNQ`), `LtPullbackEntry` (`M2K`), `LtCrossMomentum` (`MGC`).
- [ ] Valider l'exécution de bout en bout sur compte de simulation IBKR Paper (port `7497` / `4002`).

---

## 3. Critères de Réussite du Sprint (Definition of Done)

- [ ] 100% des tests unitaires et d'intégration passent (`mvn clean test`).
- [ ] Le démon tourne 24 heures consécutives sur simulateur IBKR sans déconnexion non rattrapée.
- [ ] Les ordres brackets soumis apparaissent bien sous forme d'OCA sur l'interface TWS / Gateway.
- [ ] Aucun signal n'est exécuté si la marge requise dépasse 50% de l'équité du compte.
