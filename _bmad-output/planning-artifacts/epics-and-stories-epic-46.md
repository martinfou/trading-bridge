# Epics & Stories — Epic 46: IBKR Live Autopilot & Headless CME Micro-Futures Gateway Daemon

> **Phase** : Solutioning & Hardened Epic Refinement  
> **Project** : Trading Bridge — Multi-Asset Trading Platform  
> **Périmètre** : `trading-core`, `trading-broker`, `trading-runtime`, `trading-data`, `trading-strategies`  
> **Objectif** : Livrer un moteur de trading live autonome 24/5 sur Interactive Brokers (IBKR), durci contre les coupures réseau, les reboots quotidiens, les flash gaps de week-end, les pièges de rollover et les liquidations de marge sur petits comptes (Micro-Futures CME `MES`, `MNQ`, `M2K`, `MGC`, `MCL`).

---

## 1. Vue d'Ensemble d'Epic 46

Epic 46 concrétise le pivot stratégique vers **Interactive Brokers** en fournissant un démon autonome (24/5 headless container) conçu spécifiquement pour le **Swing Trading sur barres H1 (horaires) et D1 (journalières)**.

L'architecture intègre les protections issues de l'analyse contradictoire **Blue Team / Red Team** :
- **Surveillance & Résilience** : Gestion des redémarrages quotidiens obligatoires d'IB Gateway (23:45 UTC), reconnexion exponentielle et réconciliation d'état SQLite.
- **Sécurité d'Exécution Matérielle** : Ordres de protection soumis directement sur les serveurs d'appariement du CME via des groupes **OCA (One-Cancels-All) natifs**, éliminant le risque de perte lors d'un crash logiciel.
- **Protection Petits Comptes** : Bouclier de marge à 50% max, dimensionnement à 1 micro-contrat et plafond strict de 2 positions concurrentes.
- **Autonomie Complète** : Détection automatique des bascules de volume trimestrielles et exécution des rolls de calendrier sans intervention humaine.

---

## 2. Inventaire des User Stories — Epic 46

| Story ID | Titre | Priorité | Effort | Description | Dépendances |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **`46-1`** | **Headless IB Gateway Supervisor, Heartbeat Watchdog & Reconnection Sync** | P0 | M | Conteneur Docker avec superviseur IBC, gestion du reboot quotidien 23:45 UTC, probe de liveness 30s et réconciliation d'état positions/ordres via SQLite. | — |
| **`46-2`** | **CME Native OCA Bracket Order Router & Hybrid Stop-Loss Engine** | P0 | L | Soumission d'ordres brackets natifs (Entry + Stop Market + Profit Target) à l'échange CME via l'API TWS Java avec ajustements dynamiques de trailing au bar-close. | 46.1 |
| **`46-3`** | **Idempotent Order Tagging, Partial-Fill Handling & Rejection Guard** | P0 | M | Tagging d'ordres déterministe (`orderTag`), blocage des doublons, agrégation atomique des fills partiels et gestion propre des rejets de marge (Error 201). | 46.2 |
| **`46-4`** | **Autonomous CME Quarterly Rollover & Calendar Spread Engine** | P1 | M | Détection du croisement de volume sur contrat suivant à $J-8$, exécution via ordres `COMBO` spread calendrier IBKR et mise à jour du prix de revient. | 46.2 |
| **`46-5`** | **Small-Account Margin Shield & Global Concurrent Position Limiter** | P0 | S | Dimensionnement conservateur 1 micro par signal, limite de 2 positions simultanées, verrouillage à 50% max d'utilisation de marge et paliers d'évolution. | 46.3 |
| **`46-6`** | **Deterministic H1/D1 Bar-Close Scheduler & Multi-Regime Ensemble Runner** | P0 | M | Planificateur précis à `:00:05` post-clôture de barre, récupération des barres IBKR et exécution de l'ensemble de 3 stratégies (`MES`/`MNQ`, `M2K`, `MGC`). | 46.1, 46.5 |

---

## 3. Détail des Spécifications Techniques

### Story 46.1: Headless IB Gateway Supervisor, Heartbeat Watchdog & Reconnection Sync
* **Objectif** : Garantir la disponibilité continue du démon 24/5 et la résilience aux redémarrages forcés d'IB Gateway.
* **Composants** : `com.martinfou.trading.broker.ibkr.IbkrGatewaySupervisor`, `IbkrLiveRunner.java`.
* **Critères d'Acceptation** :
  - **Étant donné** le redémarrage quotidien d'IB Gateway à 23:45 UTC,
  - **Quand** la socket TWS se déconnecte pendant la fenêtre de maintenance,
  - **Alors** le superviseur tente une reconnexion avec backoff exponentiel (5s, 10s, 30s) dès 00:05 UTC.
  - **Quand** la connexion est rétablie,
  - **Alors** `reqPositions()` et `reqOpenOrders()` sont invoqués et réconciliés avec les enregistrements SQLite locaux avant toute nouvelle prise de position.

---

### Story 46.2: CME Native OCA Bracket Order Router & Hybrid Stop-Loss Engine
* **Objectif** : Protéger le capital au niveau de la bourse CME contre les coupures réseau et gaps de marché.
* **Composants** : `com.martinfou.trading.broker.ibkr.IbkrBracketOrderRouter`, `com.martinfou.trading.core.Order`.
* **Critères d'Acceptation** :
  - **Étant donné** un signal d'achat sur `MES` à 5000.00 avec Stop à 4980.00 et TP à 5050.00,
  - **Quand** l'ordre d'entrée est soumis à IBKR,
  - **Alors** l'entrée est liée aux ordres Stop-Market et Limit Target via un identifiant de groupe `ocaGroup`.
  - **Quand** l'ordre d'entrée est exécuté par le CME,
  - **Alors** le Stop et le TP sont immédiatement activés au niveau de l'échange sans dépendance logicielle.
  - **À chaque clôture de barre H1/D1**, le moteur peut émettre un ajustement de prix du stop (trailing stop) via `placeOrder` avec le même `orderId`.

---

### Story 46.3: Idempotent Order Tagging, Partial-Fill Handling & Rejection Guard
* **Objectif** : Éliminer les doubles exécutions et fiabiliser la gestion des rejets et exécutions partielles.
* **Composants** : `com.martinfou.trading.broker.ibkr.IbkrOrderExecutionManager`, `trading-data/SqliteTradeStore.java`.
* **Critères d'Acceptation** :
  - **Étant donné** une stratégie émettant un ordre,
  - **Quand** l'identifiant déterministe `orderTag` (`SYM-STRAT-YYYYMMDD-HHMM`) existe déjà à l'état `PENDING` ou `FILLED`,
  - **Alors** la tentative d'envoi est bloquée avec log d'avertissement.
  - **Quand** IBKR renvoie une erreur de marge (`Error 201`),
  - **Alors** la stratégie passe temporairement en statut `MARGIN_PAUSED` sans boucle infinie de retry.

---

### Story 46.4: Autonomous CME Quarterly Rollover & Calendar Spread Engine
* **Objectif** : Automatiser le roulement trimestriel des contrats futures en minimisant les coûts d'écartement.
* **Composants** : `com.martinfou.trading.core.CmeAutoRolloverManager`, `FuturesRegistry.java`.
* **Critères d'Acceptation** :
  - **Étant donné** une position ouverte sur le contrat de front-month (`MESH26`) à $J-8$ de l'expiration,
  - **Quand** le volume horaire du contrat suivant (`MESM26`) dépasse le front-month sur 2 barres consécutives,
  - **Alors** le gestionnaire génère un ordre combiné `COMBO` (vente front-month + achat next-month).
  - **Quand** l'ordre spread est exécuté,
  - **Alors** la position interne est mise à jour avec le nouveau symbole et le prix de revient ajusté du spread.

---

### Story 46.5: Small-Account Margin Shield & Global Concurrent Position Limiter
* **Objectif** : Protéger les comptes à capital réduit (\$3k–\$10k) contre les appels de marge et le sur-levier.
* **Composants** : `com.martinfou.trading.core.MarginTracker`, `com.martinfou.trading.strategies.AtrFuturesPositionSizer`.
* **Critères d'Acceptation** :
  - **Étant donné** un compte avec \$5,000 d'équité détenant déjà 1 position `MES` (marge requise \$1,300),
  - **Quand** un signal d'achat se déclenche sur `MGC` (marge \$1,100),
  - **Alors** l'ordre est autorisé (2 positions actives, marge totale \$2,400 = 48% de l'équité).
  - **Quand** un 3ème signal se déclenche sur `MNQ` (marge \$2,000),
  - **Alors** l'ordre est automatiquement rejeté par le verrou des 2 positions max et du seuil de 50% de marge.

---

### Story 46.6: Deterministic H1/D1 Bar-Close Scheduler & Multi-Regime Ensemble Runner
* **Objectif** : Orchestrer l'évaluation précise des stratégies à la clôture de chaque barre horaire et journalière.
* **Composants** : `com.martinfou.trading.runtime.BarScheduler`, `LongTermStrategyCatalog.java`.
* **Critères d'Acceptation** :
  - **Étant donné** le planning de trading CME Globex,
  - **Quand** l'horloge système atteint `:00:05` (5 secondes après chaque heure pleine pendant les heures d'ouverture),
  - **Alors** le scheduler interroge IBKR pour la dernière barre clôturée.
  - **Alors** il évalue l'ensemble des 3 stratégies actives :
    1. `LtBollingerSqueeze` sur `MES` / `MNQ` (Breakout de compression H1)
    2. `LtPullbackEntry` sur `M2K` (Pullback de tendance H1)
    3. `LtCrossMomentum` sur `MGC` (Suivi de tendance macro D1)
  - **Alors** les ordres résultants sont acheminés vers le routeur de brackets.

---

## 4. Matrice de Sécurité Blue Team / Red Team Hardening

| Attaque Red Team | Gravité | Solution Blue Team Implémentée | Story |
| :--- | :---: | :--- | :---: |
| **Crash / Reboot Nocturne IB Gateway** | 🔴 HAUTE | Superviseur IBC + probe 30s + réconciliation SQLite automatique. | **46.1** |
| **Flash Gap de Week-End (Dimanche 18h)** | 🔴 HAUTE | Brackets Stop-Market natifs CME + bouclier de marge 50% max. | **46.2 / 46.5** |
| **Spam d'Ordres Doublons lors d'un Timeout** | 🔴 HAUTE | Clé d'idempotence `orderTag` unique stockée en base locale. | **46.3** |
| **Piège d'Illiquidité & Écart de Rollover** | 🟠 MOY | Détection de croisement de volume + ordre Spread `COMBO` à mi-marché. | **46.4** |
| **Appel de Marge / Liquidation Forcée** | 🟠 MOY | Limite stricte : 1 micro, max 2 positions, max 50% d'utilisation de marge. | **46.5** |
| **Décalage Horaire & Retard d'Évaluation** | 🟡 FAIBLE | Synchronisation NTP + déclenchement à `:00:05` en fuseau `America/New_York`. | **46.6** |
