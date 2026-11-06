# Guide Multi-Audiences (Humains & Agents LLM) — Trading Bridge

**Date de mise à jour :** 2026-11-05  
**Objectif :** Fournir un point d'entrée sur mesure, immédiatement actionnable, pour chaque profil d'**utilisateur humain** et chaque type d'**agent IA (LLM)** intervenant sur l'écosystème **Trading Bridge**.

---

## Navigation Rapide par Profil

| Catégorie | Profil / Persona | Accès Direct | Documents Clés Associés |
| :--- | :--- | :--- | :--- |
| **👤 Humain** | **1. Chercheur Quantitatif & Développeur de Stratégies (Quant)** | [Aller à la section §1](#1-humain--chercheur-quantitatif--développeur-de-stratégies-quant) | [`TRADING-GUARDRAILS.md`](./TRADING-GUARDRAILS.md), [`lt-strategy-playbook.md`](./lt-strategy-playbook.md), [`real-cost-model-hourly-spec.md`](./real-cost-model-hourly-spec.md), [`mes-cme-futures-realism-guide.md`](./mes-cme-futures-realism-guide.md) |
| **👤 Humain** | **2. Trader, Opérateur de Desk & Ingénieur SRE (Desk Ops)** | [Aller à la section §2](#2-humain--trader-opérateur-de-desk--ingénieur-sre-desk-ops) | [`deployment-guide.md`](./deployment-guide.md), [`operations-manifest.md`](./operations-manifest.md), [`reliability-engineering.md`](./reliability-engineering.md), [`paper-window-log.md`](./paper-window-log.md) |
| **👤 Humain** | **3. Ingénieur Logiciel & Architecte Système (Software Engineer)** | [Aller à la section §3](#3-humain--ingénieur-logiciel--architecte-système-software-engineer) | [`architecture-trading-bridge-java.md`](./architecture-trading-bridge-java.md), [`architecture-trading-bridge-desktop.md`](./architecture-trading-bridge-desktop.md), [`api-contracts-trading-bridge-java.md`](./api-contracts-trading-bridge-java.md), [`development-guide.md`](./development-guide.md) |
| **👤 Humain** | **4. Décideur Produit, Exécutif & Auditeur (Product / Executive)** | [Aller à la section §4](#4-humain--décideur-produit-exécutif--auditeur-product--executive) | [`project-overview.md`](./project-overview.md), [`VISION.md`](./VISION.md), [`sprint-plan.md`](./sprint-plan.md), [`deployed-set-audit-2026-10-16.md`](./deployed-set-audit-2026-10-16.md) |
| **🤖 LLM** | **5. Agent IA de Codage & d'Architecture (Coding Agent)** | [Aller à la section §5](#5-llm--agent-ia-de-codage--darchitecture-coding-agent) | [`_bmad-output/project-context.md`](../_bmad-output/project-context.md), [`source-tree-analysis.md`](./source-tree-analysis.md), [`data-models-trading-bridge-java.md`](./data-models-trading-bridge-java.md) |
| **🤖 LLM** | **6. Agent IA de Recherche Quantitative & Stratégie (Quant Agent)** | [Aller à la section §6](#6-llm--agent-ia-de-recherche-quantitative--stratégie-quant-agent) | [`llm-context-futures-and-realism.md`](./llm-context-futures-and-realism.md), [`sq-xml-format.md`](./sq-xml-format.md), [`strategy-home.md`](./strategy-home.md) |
| **🤖 LLM** | **7. Agent IA d'Opérations, Déploiement & SRE (Ops Agent)** | [Aller à la section §7](#7-llm--agent-ia-dopérations-déploiement--sre-ops-agent) | [`architecture-trading-bridge-scripts.md`](./architecture-trading-bridge-scripts.md), [`deploy-runbook.md`](./deploy-runbook.md), [`incident-severity-matrix.md`](./incident-severity-matrix.md) |

---

# PARTIE I — GUIDES POUR LES HUMAINS

---

## 1. 👤 Humain — Chercheur Quantitatif & Développeur de Stratégies (Quant)

### 1.1 Votre Environnement de Travail
Vous disposez de trois environnements complémentaires pour concevoir, tester et promouvoir des stratégies :
1. **Studio Évolutionnaire Natif (`/generator` dans l'application Desktop)** : Génère des stratégies en Java pur à partir de **24 blocs de construction orthogonaux** (mode Génétique ou Monte-Carlo), avec découpage strict In-Sample / Out-of-Sample aveugle et promotion en 1 clic vers le catalogue Java.
2. **Usine StrategyQuant X (`scripts/sqx_ctrl.py` & `scripts/sqx_auto_pipeline.py`)** : Pilote 11 projets institutionnels `.cfx` (7 CME Micro Futures + 4 OANDA FX), extrait les courbes d'équité binaires (`extract_sqx_backtest.py`), classe les candidats (`ELITE`, `QUALIFIED`, `EXPERIMENTAL`) et vérifie leur parité d'exécution dans le moteur Java.
3. **Studio Walk-Forward (`/wfa` dans Desktop ou `WalkForwardCli`)** : Vérifie la stabilité paramétrique sur fenêtres glissantes ou ancrées et calcule l'efficacité Walk-Forward (**WFE**).

### 1.2 Les 8 Garde-Fous Quantitatifs Non-Négociables (`TRADING-GUARDRAILS.md`)
1. **Données réelles uniquement** : Aucune série synthétique. Utilisez les fichiers `.bars` réels OANDA (`price=BA`/`M`), Dukascopy ou CME (`2014–2026`).
2. **Horizon multi-années obligatoire** : Minimum 3 ans (10 ans recommandés) couvrant plusieurs régimes macroéconomiques.
3. **Coûts de transaction réalistes systématiques** : Tout backtest doit activer `RealCostModel` (spreads horaires NY + pic de volatilité ATR) et `SwapCalculator` (triple rollover mercredi FX / vendredi Actions). Les backtests sans coûts sont interdits.
4. **Validation Out-of-Sample (OOS) aveugle** : Séparation stricte IS (`< 2023-01-01`) / OOS (`>= 2023-01-01`) ou WFA avec $\text{WFE} \ge 50\,\%$ ($\ge 60\,\%$ pour le verdict `ROBUST`).
5. **Anti-surapprentissage (*No Overfitting*)** : Maximum 4 à 5 paramètres ajustables, minimum **100 transactions** ($\ge 30$ en OOS), et blocs d'indicateurs orthogonaux.
6. **Critères du palier `ELITE`** : Linéarité $R^2 \ge 0,85$, Martin Ratio ($UPI$) $\ge 1,20$, Sharpe annualisé $\ge 0,70$, Profit Factor OOS $\ge 1,15$, Drawdown max $\le 20\,\%$.
7. **Fenêtre Paper de 2 semaines minimum** sur OANDA Practice (`-004`) ou IBKR Paper (`DUR967322`) avant toute allocation Live.
8. **Coupe-circuit individuel obligatoire** : Chaque stratégie déployée dans `config/live-config.json` reçoit un `maxDrawdownDollar` fixé à $1,5\times$ son Max Drawdown historique.

### 1.3 Leçons Empiriques Majeures (Études Simons 17e à 65e — À Connaître Absolument)
* **Piège des bougies BID-only à 17h00 ET (`Étude 64e`)** : Un « fade » apparent sur la dernière heure du vendredi (`16h00–17h00 ET`) observé sur des bougies BID-only est un **artefact d'élargissement symétrique du spread** ($\Delta \text{BID} = \Delta \text{MID} - 0,5\,\Delta \text{Spread}$) alors que le prix médian ($\text{MID}$) ne bouge pas. Validez toujours les stratégies intrajournalières sur données `price=BA` ou avec la table horaire de `RealCostModel` (où le spread de 17h00 ET est multiplié par $4,5\times$ à $6,0\times$).
* **Avantage structurel des Micro Futures CME (`MES`, `MNQ`, `MGC`) sur le Spot FX/CFD** : Les contrats à terme CME ne subissent **aucun frais de swap overnight courtier** (le portage est intégré dans la base trimestrielle) et offrent un spread de 1 tick en heures liquides, ce qui préserve l'espérance mathématique des stratégies H1/M30 de cassure (*breakout*) et de suivi de tendance.

---

## 2. 👤 Humain — Trader, Opérateur de Desk & Ingénieur SRE (Desk Ops)

### 2.1 Vos 4 Consoles de Supervision
1. **Trading Bridge Desktop (`/live-trading` & `/compare`)** : Gestion des comptes OANDA et IBKR, conversion multi-devises en direct (`CAD`, `USD`, `EUR`, `GBP`, `JPY`), jauge d'utilisation de marge (`> 50 %` alerte, `> 80 %` critique), suivi des positions/ordres en temps réel et audit **Alignement & Drift** entre exécution Live/Paper et Backtest.
2. **Console Web Laravel (`http://127.0.0.1:8000/control`)** : Vue SSR ultra-légère rafraîchie toutes les 5 secondes affichant la fraîcheur des événements, le drawdown journalier, les ruptures de séquence (*gaps*), les signaux de dérive et le bouton **Kill Switch** par stratégie.
3. **Terminal Interactif TUI (`./start-tui.sh`)** : Commandes rapides en SSH (`/status`, `/list`, `/events`, `/compare`, `/kill`, `/health`).
4. **Portail Externe Hermes Web (`https://hermes.martinfournier.com`)** : Reçoit les snapshots IBKR toutes les 5 minutes (`ibkr_snapshot_push.py`), les fills P&L horaires convertis en `CAD` (`ibkr_pnl_push.py`), le statut OANDA (`OperationsSnapshotPusher`) et la télémétrie d'évolution SQX (`sqx_telemetry_pusher.py`).

### 2.2 Topologie des 3 Couloirs Actifs en Production (Novembre 2026)
| Couloir | Conteneur Docker | Compte Courtier | Portefeuille Actif |
| :--- | :--- | :--- | :--- |
| **IBKR CME Futures Paper** | `trading-futures` | IBKR `DUR967322` (`127.0.0.1:4002`, `clientId=11`) | 3 stratégies `MGC` (Micro Or) M30 : `sq-mgc-m30-breakout-0-24542`, `1-29-102`, `0-32921` (`riskPct: 0.5%`). |
| **OANDA FX Paper** | `trading-live` (`TB_LANE=paper`) | OANDA Practice `-004` (`.env.paper`) | 4 stratégies `GBP_JPY` H1 : `sq-gbpjpy-h1-breakout-4-5-173`, `3-39-125`, `4-19-179`, `2-20-102` (`riskPct: 0.2%`). |
| **OANDA FX Dev / Rehab** | `trading-dev` (`TB_LANE=dev`) | OANDA Practice `-003` (`.env.dev`) | 5 stratégies en observation isolées : `vwpreversion`, `consecbar`, `ltrsi3`, `compmomentum`, `monthweekphase`. |

### 2.3 Runbook Quotidien & Commandes d'Urgence
```bash
# 1. Vérifier la santé globale avant déploiement OANDA Paper
./scripts/pre-deploy-gate.sh

# 2. Déployer une mise à jour sur le couloir OANDA Paper (avec sauvegarde d'état et réconciliation activeTrades)
./scripts/deploy-paper.sh --apply

# 3. Vérifier la passerelle IB Gateway 10.50 et le conteneur CME Futures
./scripts/ibkr-gateway-health.sh
docker logs --tail 50 trading-futures

# 4. Activer le Kill Switch d'urgence d'une stratégie en ligne de commande
curl -X POST http://localhost:8080/api/strategies/LondonOpenRangeBreakout/kill \
  -H "Content-Type: application/json" \
  -d '{"actor":"operator-cli","reason":"Arrêt manuel opérateur"}'

# 5. Vérifier l'intégrité SQLite et lancer le compactage si nécessaire
curl http://localhost:8080/api/diagnostics/integrity
./scripts/prune-db.sh
```

---

## 3. 👤 Humain — Ingénieur Logiciel & Architecte Système (Software Engineer)

### 3.1 Architecture Globale en 4 Parties
* **Partie 1 — `trading-bridge-java` (Java 21 / Maven 11 modules)** :
  * Respectez scrupuleusement le graphe acyclique (DAG) : `trading-core` est à la base (0 dépendance interne), `trading-runtime` orchestre le serveur Javalin (`70 endpoints`) et les 9 tables SQLite (via HikariCP en mode WAL), et `trading-tui` est un client HTTP pur sans dépendance vers le moteur.
* **Partie 2 — `trading-bridge-desktop` (Electron 33 + Vue 3.5 + TypeScript 5.7)** :
  * Le processus principal Electron (`desktop/electron/main.ts`) gère la détection de conflit sur le port `8080` et démarre le JAR Shaded Java. Le renderer Vue 3 (`9 vues`, `15 composants`) dialogue directement avec `http://localhost:8080/api/*` et `ws://localhost:8080/ws/runs/:runId`.
* **Partie 3 — `trading-bridge-dashboard` (PHP 8.3 + Laravel 13.8)** :
  * Proxy de contrôle SSR sans état métier (`ControlRoomController` + `ControlPlaneClient`).
* **Partie 4 — `trading-bridge-scripts` (Python 3 + Bash + Docker)** :
  * Démons d'automatisation SQX, télémétrie IBKR/OANDA, sondes de recherche et scripts de déploiement atomique.

### 3.2 Règles d'Ingénierie Essentielles
* **Temps canonique** : Utilisez `java.time.Instant` (UTC) partout en interne. Ne jamais utiliser `LocalDateTime.now()` dans la logique métier.
* **Files d'ordres des stratégies** : `getPendingOrders()` doit toujours retourner une copie et vider la file (`StrategyOrderQueues.drainPending()`).
* **Garde-fous d'exécution** : Tous les ordres passent par `OrderTripwire` (limites de quantité, notionnel $5\text{ M\$}$, anti-rafale 15 ordres/60s) et `SmallAccountMarginGuard` (plafond 50 % de marge par position et règle FINRA PDT).
* **Validation avant PR / Commit** :
  ```bash
  ./mvnw clean install
  cd desktop && npm run build
  cd ../dashboard && composer test
  ./scripts/agy-review.sh
  ```

---

## 4. 👤 Humain — Décideur Produit, Exécutif & Auditeur (Product / Executive)

### 4.1 Vision & Proposition de Valeur
**Trading Bridge** est une plateforme quantitative institutionnelle de bout en bout permettant de découvrir, valider scientifiquement, auditer et exécuter des portefeuilles de stratégies algorithmiques sur le **Forex (OANDA)**, les **Contrats à Terme CME (`MES`, `MNQ`, `MGC` via Interactive Brokers)** et les **Actions US**.

### 4.2 Gouvernance du Risque & Conformité d'Audit
La plateforme intègre nativement une défense en profondeur à **5 niveaux** :
1. **Réalisme pré-déploiement** : Modélisation empirique des spreads horaires (`RealCostModel`), des swaps de fin de semaine (`SwapCalculator`) et validation hors-échantillon (WFA & Monte-Carlo).
2. **Portes de Promotion (`PromoteGates`)** : Blocage logiciel empêchant toute stratégie ne respectant pas les seuils minimums (Sharpe, Profit Factor, Drawdown, WFE) d'accéder aux modes `PAPER` ou `LIVE`.
3. **Garde-Fous Pré-Trade (`OrderTripwire` & `SmallAccountMarginGuard`)** : Plafonnement du levier notionnel à $5\times$ la NAV, plafond de marge à 50 % par position et blocage des boucles d'ordres incontrôlées.
4. **Surveillance de Dérive Temps Réel (`DriftEngine`)** : Comparaison continue sur 9 métriques statistiques (incluant le test de Kolmogorov-Smirnov) entre les performances réelles et le backtest de référence.
5. **Traçabilité & Export d'Audit (`EvidencePackExporter` & `DueDiligenceHtmlExporter`)** : Chaque événement est journalisé de manière immuable dans SQLite et exportable sous forme de rapport HTML de Due Diligence ou d'archive de preuves NDJSON.

---

# PARTIE II — CONTEXTE & INSTRUCTIONS POUR LES AGENTS IA (LLM)

---

## 5. 🤖 LLM — Agent IA de Codage & d'Architecture (`Coding Agent`)

### 5.1 Protocole de Lecture Initiale (Économie de Contexte)
Avant toute modification de code, lisez dans cet ordre strict :
1. [`_bmad-output/project-context.md`](../_bmad-output/project-context.md) — **Source de vérité des règles d'implémentation et anti-patrons.**
2. [`docs/index.md`](./index.md) — **Routeur documentaire par partie.**
3. Uniquement les fichiers d'architecture/contrats correspondant à la partie touchée :
   * Backend Java : [`architecture-trading-bridge-java.md`](./architecture-trading-bridge-java.md), [`api-contracts-trading-bridge-java.md`](./api-contracts-trading-bridge-java.md), [`data-models-trading-bridge-java.md`](./data-models-trading-bridge-java.md).
   * Desktop Electron/Vue : [`architecture-trading-bridge-desktop.md`](./architecture-trading-bridge-desktop.md), [`component-inventory-trading-bridge-desktop.md`](./component-inventory-trading-bridge-desktop.md).
   * Dashboard Laravel : [`architecture-trading-bridge-dashboard.md`](./architecture-trading-bridge-dashboard.md), [`api-contracts-trading-bridge-dashboard.md`](./api-contracts-trading-bridge-dashboard.md).
   * Scripts / SQX / Docker : [`architecture-trading-bridge-scripts.md`](./architecture-trading-bridge-scripts.md), [`data-models-trading-bridge-scripts.md`](./data-models-trading-bridge-scripts.md).

### 5.2 Table des Invariants Techniques Absolus pour Agents de Codage
| Domaine | Invariant Obligatoire | Anti-Patron Interdit (Ne JAMAIS faire) |
| :--- | :--- | :--- |
| **DAG Maven** | `trading-core` n'a aucune dépendance interne ; `trading-tui` n'a aucune dépendance interne. | Importer `trading-backtest`, `trading-data` ou `trading-broker` dans `trading-core`. |
| **Temps & Fuseaux** | Utiliser `java.time.Instant` (UTC) sur `Bar`, `Order`, `Trade`, `RunEvent` et dans SQLite. | Utiliser `LocalDateTime.now()` ou supposer un fuseau local implicite. |
| **Accesseurs Java** | Style domaine fluide : `bar.close()`, `order.symbol()`, `trade.pnl()`. | Générer des getters/setters JavaBean (`getClose()`) ou ajouter Lombok / Spring. |
| **File d'Ordres `Strategy`** | `getPendingOrders()` doit copier et vider la file (`StrategyOrderQueues.drainPending()`). | Retourner la liste interne sans la vider (provoque des soumissions infinies d'ordres). |
| **Verrou de Réconciliation** | Toute route mutative sur `/api/runs*` et `/api/strategies*` est bloquée (`503`) tant que `!runManager.isReconciliationComplete()`. | Contourner le middleware `before` de `ControlPlaneServer`. |
| **Panier Multi-Actifs UI** | Vérifier `isCompatibleBasket` (`useStrategyCatalog.ts`) et appliquer `useCostPresets.ts`. | Mélanger des contrats entiers Futures (`1.0`) avec des lots fractionnaires Forex (`0.01`). |
| **Langue du Projet** | Code, identifiants, commits et `project-context.md` en **Anglais** ; documentation dans `docs/` en **Français**. | Mélanger des noms de variables/classes en français dans le code Java/TS/PHP/Python. |

---

## 6. 🤖 LLM — Agent IA de Recherche Quantitative & Stratégie (`Quant Agent`)

### 6.1 Invariants de Réalisme Financier pour Agents Quantitatifs
Lorsque vous générez, évaluez ou déboguez une stratégie de trading :
1. **Activez toujours les coûts réalistes** :
   * Sur le **Forex / Or Spot OANDA (`XAU_USD`)** : Utilisez `RealCostModel` (spreads horaires NY où la fenêtre `16h00–17h59 ET` multiplie le spread par $3,5\times$ à $6,0\times$) et `SwapCalculator` (triple swap le mercredi pour le FX, le vendredi pour les métaux/actions à `17h00 America/New_York`).
   * Sur les **Contrats à Terme CME (`MES`, `MNQ`, `MGC`)** : Utilisez les spécifications de `FuturesRegistry` (`MES` : $5\text{ \$/pt}$, tick $0,25$ ; `MNQ` : $2\text{ \$/pt}$, tick $0,25$ ; `MGC` : $10\text{ \$/pt}$, tick $0,10$), respectez la pause quotidienne Globex (`17h00–18h00 ET`) et l'absence de swap courtier overnight.
2. **Ne vous faites jamais piéger par les bougies BID-only** :
   * Toute stratégie entrant ou sortant entre `16h00 ET` et `18h00 ET` doit être vérifiée sur bougies `price=BA` (Bid/Ask réels) ou `MID`, jamais sur `BID` seul (cf. `docs/real-cost-model-hourly-spec.md` et `scripts/fade-mid-vs-bid.py`).
3. **Normalisation des archives StrategyQuant X (`.sqx`)** :
   * Les simulations brutes Dukascopy dans SQX utilisent un lot plein ($100\,000$ unités FX ou $100\text{ oz}$ sur `GC`). Appliquez toujours le facteur d'échelle `0.1` (`extract_sqx_backtest.py`) pour ramener les métriques à **1 contrat Micro (`MGC` 10 oz / 0,1 lot FX)** sur un capital de référence de `10 000 $`.
4. **Garde-Fou de l'Agent Stratège LLM (`AgenticStrategistService`)** :
   * Lors d'un backtest historique utilisant `trading-intelligence`, renseignez obligatoirement `cutoffTimestamp` sur `MacroTools`, `SentimentTools` et `SeasonalityTools` pour interdire toute fuite du futur (*look-ahead bias*), et respectez les plafonds de `GuardrailChatModel` (max 4 itérations ReAct, budget max `0,50 USD`).

---

## 7. 🤖 LLM — Agent IA d'Opérations, Déploiement & SRE (`Ops Agent`)

### 7.1 Règles de Sécurité Opérationnelle pour Agents d'Exécution
1. **Déploiement OANDA Paper** :
   * N'appelez **jamais** `scripts/deploy.sh` (retiré, renvoie code `2`).
   * Exécutez toujours `./scripts/pre-deploy-gate.sh`, puis `./scripts/deploy-paper.sh` (en mode dry-run d'abord, puis `--apply` après validation).
2. **Isolation des Identifiants & Couloirs (`Lanes`)** :
   * Le conteneur `trading-live` (`TB_LANE=paper`) utilise `.env.paper` (compte `-004`).
   * Le conteneur `trading-dev` (`TB_LANE=dev`) utilise `.env.dev` (compte `-003`).
   * Le conteneur `trading-futures` utilise IB Gateway sur `127.0.0.1:4002` (compte Paper `DUR967322`). Refusez systématiquement les ports Live IBKR (`4001`, `7496`) dans les scripts Paper.
3. **Séparation des `clientId` IBKR TWS/Gateway** :
   * `clientId = 11` : Réservé exclusivement au moteur d'exécution `IbkrLiveRunner` (`trading-futures`).
   * `clientId = 90` : Réservé au démon de snapshot `scripts/ibkr_snapshot_push.py` (toutes les 5 min).
   * `clientId = 98` : Réservé au démon d'import P&L `scripts/ibkr_pnl_push.py` (toutes les heures).
4. **Traitement des Sentinelles IBKR (`Double.MAX_VALUE` / `1.7976931348623157e308`)** :
   * IBKR TWS émet `Double.MAX_VALUE` sur `CommissionReport.realizedPNL` lors des fills d'ouverture et sur certains tags `accountSummary` non initialisés. Tout script ou connecteur doit filtrer `abs(val) > 1e300` et ne **jamais** persister ni afficher `0.0` à la place d'une donnée absente.
5. **Persistance des Journaux de Fills au Redémarrage** :
   * Vérifiez que `data-futures/state/ibkr-fills-journal.json` (pour IBKR) et `data/state/live-strategy-state-*.json` (pour OANDA) sont montés en volume hôte et que chaque ordre IBKR porte l'identifiant de sa stratégie dans `Execution.orderRef`.

