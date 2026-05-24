---

## stepsCompleted: [1, 2, 3, 4]
inputDocuments: ['_bmad-output/project-context.md']
session_topic: 'Plateforme unifiée backtest → paper → production, avec TUI moderne (style Claude) et dashboard web Laravel pour le statut live'
session_goals: 'Vision produit et architecture : parcours dev/trader sans friction entre les 3 modes ; UX TUI conversationnelle ; intégration TUI ↔ Laravel ↔ moteur Java ; roadmap actionnable'
selected_approach: 'ai-recommended + party-mode'
techniques_used: ['First Principles Thinking', 'Cross-Pollination', 'Morphological Analysis']
ideas_generated: 52
context_file: '_bmad-output/project-context.md'
facilitation_mode: 'party-mode-autonomous'

# Brainstorming Session Results

**Facilitator:** Martin Fournier  
**Date:** 2026-05-23  
**Mode:** Party Mode (Winston, Sally, John, Amelia, Mary, Paige) — décisions prises par l'orchestrateur

## Session Overview

**Topic:** Plateforme unifiée backtest → paper → production + TUI Claude-style + Laravel live  
**Goals:** Vision produit, architecture, UX, roadmap

### Vision consolidée

```
                    ┌─────────────────────────────────┐
                    │     Java Engine (source de      │
                    │     vérité : Strategy, fills,   │
                    │     état, événements)           │
                    └────────────┬────────────────────┘
                                 │ REST + WebSocket / JSONL
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
        ┌──────────┐      ┌──────────┐      ┌──────────┐
        │ TUI      │      │ Laravel  │      │ CLI      │
        │ atelier  │      │ contrôle │      │ (Epic 12)│
        │ créer    │      │ surveiller│     │ CI/dev   │
        └──────────┘      └──────────┘      └──────────┘
```

---

## Party Mode — Phase 1 : First Principles

### 🏗️ Winston (Architecte)

**Vérités fondamentales :** savoir où on en est, agir vite (kill switch), faire confiance aux chiffres.

**Hypothèses à abandonner :** CLI-only, apps séparées par mode, Python dashboard comme 2e source de vérité, live = backtest + vraies données.

**5 principes immuables :**

1. Une stratégie, un artefact (bytecode Java versionné)
2. Un moteur, trois runtimes (BacktestExecutor, PaperExecutor, LiveExecutor)
3. État observable, actions impératives (API, pas magie)
4. Séparation lecture/écriture — Java possède l'état
5. Boring tech — REST/WS, event log append-only

**Décisions :** Control Plane HTTP Java ; Laravel thin dashboard ; TUI client de l'API ; event log SQLite/PG.

### 🎨 Sally (UX)

**Émotions par transition :** idée (excitation/peur) → backtest (curiosité) → paper (prudence) → live (concentration froide).

**TUI = atelier** (streaming, `/list`, `/paper`, `/promote`). **Web = salle de contrôle** (3 secondes pour savoir si tout va bien).

**5 principes UX :** zéro surprise entre modes ; confiance progressive ; contexte toujours visible ; streaming ; TUI crée, web surveille.

### 📋 John (PM)

**JTBD :** *« Idée → backtest → paper → prod sans réécrire ni douter que les chiffres signifient la même chose. »*

**3 moments de vérité :** premier backtest ; bascule paper ; jour du live.

**MVP 2 semaines :** Epic 12 CLI + golden backtest + 1 stratégie prop en paper. **Hors MVP :** TUI Claude, Laravel live, multi-broker prod.

**Verdict :** Epic 12 = premier produit vendable. TUI/Laravel = amplificateurs après le socle.

### 💻 Amelia (Dev)

**Artefact minimal :** `Strategy` + `StrategyCatalog` entry — même factory partout.

**Frontière :** JSONL RunEvent stream (`RUN_STARTED`, `BAR`, `FILL`, `RUN_END`…) — TUI et Laravel = clients.

**Stories proposées :**

- **12-4** RunContext + backtest via catalog
- **12-5** RunEvent stream v1 + `--json`
- **12-6** Paper runner stub (même stream, pas de réseau)

---

## Party Mode — Phase 2 : Cross-Pollination

### 📊 Mary — Patterns volés (21 idées)

**Claude Code [#1-3]**

- [#1] **Contexte de session persistant** — stratégie + dataset + run ID visible comme le working directory Claude
- [#2] **Streaming token-by-token** — métriques backtest qui arrivent en direct, pas d'écran muet
- [#3] **Slash commands composables** — `/backtest`, `/promote`, `/kill` avec autocomplete

**GitHub Actions [#4-6]**

- [#4] **Pipeline promote** — backtest = CI, paper = staging, live = prod avec gates
- [#5] **Checks obligatoires** — golden backtest vert avant merge en paper
- [#6] **Artefacts immuables** — JAR + config hashés, jamais rebuild silencieux

**Linear [#7-9]**

- [#7] **Statut par stratégie** — Backlog / Backtesting / Paper / Live avec badge couleur
- [#8] **Inbox d'alertes** — triage drawdown, disconnect, fill anomaly
- [#9] **Cycles hebdo** — revue paper vs backtest comme sprint review

**Bloomberg [#10-12]**

- [#10] **Multi-pane layout** — positions + chart + news macro dans Laravel
- [#11] **Keyboard-first TUI** — raccourcis pour kill, flatten, status
- [#12] **Cross-instrument watch** — corrélation visible avant d'ajouter une stratégie

**Vercel [#13-15]**

- [#13] **Preview deployment = paper** — chaque branche git → run paper éphémère
- [#14] **URL par run** — lien Laravel partageable pour un backtest
- [#15] **Rollback one-click** — revenir à la version stratégie précédente en prod

**Docker/K8s [#16-18]**

- [#16] **Image stratégie** — artefact versionné promu entre environnements
- [#17] **Health probes** — heartbeat JSONL ; Laravel rouge si stale >30s
- [#18] **Resource limits** — cap risque par stratégie comme CPU limits

**Aviation [#19-21]**

- [#19] **Preflight checklist** — données, broker, checks avant go-live
- [#20] **Stall warning** — alerte si métriques in-sample vs out-of-sample divergent
- [#21] **Black box recorder** — event log replayable après incident

### 📚 Paige — Concept Blending (9 idées)

[#UX-1] Instruments de vol (4 jauges : risque, latence, dérive, santé stream)  
[#Arch-2] Pipeline recette → plat (barres → signaux → fills en JSONL)  
[#Biz-3] Checks PR avant prod (✅/❌ sur dashboard)  
[#Edge-4] Détecteur contexte périmé (data/version/calendar drift)  
[#Wild-5] Chef d'orchestre multi-stratégies (allocation capital)  
[#UX-6] Briefing pré-session preflight  
[#Arch-7] Working context partagé TUI ↔ Java ↔ Laravel  
[#Biz-8] Rapport « PR de stratégie » exportable  
[#Edge-9] Stall warning sur-optimisation  

### 🎨 Sally — Micro-interactions UX (10)

TUI : barre statut htop ; preview inline `/promote` ; blocs pliables Warp ; sparklines btop ; mode focus run.  
Laravel : timeline alertes TradingView ; complications Apple Watch ; webhooks ; split live/replay ; badge stale data pulsant.

**Désaccord Winston :** TUI pas en Rust pour MVP — **Java JLine3** ou thin xterm.js, même bus d'événements que Laravel.

---

## Party Mode — Phase 3 : Morphological Analysis

Grille Mode × Surface × Action × Données → 31 idées clés :


| #     | Idée                    | Combinaison                                          |
| ----- | ----------------------- | ---------------------------------------------------- |
| 1     | Golden Gate             | backtest × CLI × test × PnL                          |
| 2     | Promote Diff            | paper × TUI × deploy × config                        |
| 3     | Kill from Web           | live × Laravel × stop × positions                    |
| 4     | Slack Compare           | paper × notifications × compare × PnL                |
| 5     | Rollback Remote         | live × Laravel × rollback × config                   |
| 6     | Stream Replay           | backtest × TUI × monitor × logs                      |
| 7     | Stale Pulse             | live × Laravel × monitor × alerts                    |
| 8     | Preflight Block         | live × TUI × deploy × config                         |
| 9     | PR Report               | backtest × API × compare × PnL                       |
| 10    | Preview Paper           | paper × API × deploy × config                        |
| 11    | Catalog Spark           | backtest × TUI × create × PnL                        |
| 12    | Event Tail              | live × CLI × monitor × logs                          |
| 13    | Macro Gate              | live × Laravel × deploy × bars                       |
| 14    | Multi-Run Focus         | backtest × TUI × test × logs                         |
| 15    | Webhook Alert           | live × notifications × monitor × alerts              |
| 16    | Context Sync            | all × all surfaces × config                          |
| 17    | Drawdown Inbox          | paper × Laravel × monitor × PnL                      |
| 18    | Branch Paper            | paper × API × create × config                        |
| 19    | Audit Trail             | live × Laravel × monitor × logs                      |
| 20    | Flatten All             | live × TUI × stop × positions                        |
| 21-25 | …                       | combinaisons monitor/compare cross-mode              |
| 26    | **Rollback + TUI diff** | live × Laravel rollback + TUI montre diff paramètres |
| 27    | **Compare via Slack**   | paper × notifications × compare backtest vs paper    |
| 28    | **Kill + confirm TUI**  | live × TUI stop avec double confirmation web         |
| 29    | **Deploy from CI**      | backtest × API × deploy après golden vert            |
| 30    | **Replay in dashboard** | backtest × Laravel × monitor replay JSONL            |
| 31    | **Config drift alert**  | live × notifications × config mismatch               |


---

## Décisions verrouillées (Orchestrateur)

Le roundtable était unanime sur le socle ; débat TUI Rust vs Java → **décision MVP : Java-first**.

### Architecture NOW

1. **Java engine = source de vérité** — état, ordres, événements
2. **RunEvent JSONL v1** — contrat unique TUI + Laravel + CLI
3. **StrategyCatalog** — une porte d'entrée, trois executors
4. **Control Plane HTTP** (Javalin ou Spring Boot minimal) — phase post-Epic 12
5. **Golden backtest = contrat de confiance** — gate CI obligatoire

### Reporter (Sprint 13+)

- TUI Claude-style complet (panels, replay)
- Laravel dashboard production
- Live OANDA writes
- Rust TUI (revisit if Java UX insufficient)
- Multi-stratégie prod, auth multi-user

### Roadmap proposée


| Sprint        | Focus         | Livrable                                                    |
| ------------- | ------------- | ----------------------------------------------------------- |
| **12 (now)**  | Consolidation | CLI unifié, StrategyCatalog, golden backtest ✅              |
| **12.4-12.6** | Pipeline      | RunContext, JSONL events, paper stub                        |
| **13**        | Control Plane | HTTP API + event bus + health probes                        |
| **14**        | TUI v1        | JLine3 client : list, backtest stream, /promote             |
| **15**        | Laravel v1    | Positions, PnL, alertes, kill switch (read-only + commands) |
| **16**        | Live          | LiveExecutor OANDA, preflight, rollback                     |


---

## Top 10 — Idées prioritaires


| Rang | Idée                        | Pourquoi                                         |
| ---- | --------------------------- | ------------------------------------------------ |
| 1    | **RunEvent JSONL v1**       | Débloque TUI + Laravel + CI avec un seul contrat |
| 2    | **Golden backtest gate**    | Confiance avant tout UI                          |
| 3    | **StrategyCatalog unifié**  | Une stratégie, trois modes                       |
| 4    | **Checks PR promote**       | Gouvernance backtest → paper → live              |
| 5    | **Working context partagé** | Fin du « quel run ? » entre surfaces             |
| 6    | **Preflight checklist**     | Réduit erreur humaine go-live                    |
| 7    | **TUI streaming backtest**  | Expérience Claude Code sur le cœur métier        |
| 8    | **Laravel complications**   | PnL/exposition/connexion en 2 secondes           |
| 9    | **Stale data pulse**        | Monitoring honnête (pas de faux vert)            |
| 10   | **Rollback one-click**      | Sécurité psychologique en prod                   |


---

## Prochaines actions (Martin — sans décision requise)

1. **Finir Story 12.3** — unified CLI + StrategyCatalog
2. **Créer stories 12.4, 12.5, 12.6** — spec depuis Amelia's ACs
3. **Epic 13 PRD** — Control Plane + JSONL + paper runner
4. **Epic 14/15 esquisses** — TUI + Laravel (Sally UX + Winston arch)

---

## Creative Facilitation Narrative

Session autonome en party mode : 6 agents, 3 techniques, 52 idées. Consensus fort sur « moteur Java d'abord, surfaces ensuite ». Tension productive Sally vs Winston sur stack TUI — résolu en faveur Java pour vélocité MVP. John a imposé la discipline : pas de dashboard avant paper stable 7 jours.