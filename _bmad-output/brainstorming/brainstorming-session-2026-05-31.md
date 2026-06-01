---
stepsCompleted: [1, 2, 3, 4]
inputDocuments: []
session_topic: 'Intégrer StrategyQuant (CLI + exports) dans le pipeline Trading Bridge pour générer, filtrer et convertir des stratégies de bout en bout, avec les deux outils qui échangent des données/commandes'
session_goals: 'Produire une liste d''idées d''intégration (watchers, jobs CLI, API, dossiers partagés, bidirectionnel TB↔SQ, etc.)'
selected_approach: 'ai-recommended'
techniques_used: ['Morphological Analysis', 'Cross-Pollination', 'Reverse Brainstorming']
ideas_generated: 78
session_active: false
workflow_completed: true
facilitation_notes: 'Martin exécute SQ nativement sur Mac — pas besoin d''agent Windows. Priorité : hot folder + sqcli wrapper + boucle bidirectionnelle indicateurs externes.'
context_file: ''
---

# Brainstorming Session Results

**Facilitateur :** Martin Fournier  
**Date :** 2026-05-31  
**Contrainte utilisateur :** StrategyQuant X sur **Mac** (sqcli local, pas d''agent Windows)

## Session Overview

**Sujet :** Intégrer SQ (CLI + exports) dans le pipeline Trading Bridge — génération, filtrage, conversion, communication bidirectionnelle.

**Objectif atteint :** ~78 idées organisées en 7 thèmes + plan d''action MVP Mac.

### Contexte projet

- **Trading Bridge** : `trading-parser`, `trading-genetics`, control plane (8080), `RunBacktest`, `sqimported`
- **StrategyQuant X** : `sqcli` (Build 127+), exports XML/CSV, `.sqx` non parsé
- **Flux actuel** : export manuel XML → parse → backtest Java

---

## Phase 3 — Reverse Brainstorming (#56–#78)

*Comment saboter l''intégration SQ↔TB — et l''idée de mitigation*

| Échec | Idée mitigation |
|-------|-----------------|
| sqcli introuvable (PATH, `.exe` vs binaire Mac) | **#56 SqCliLocator** — config `sq.home` dans `.env` / `application.properties` ; détection auto Mac vs Windows |
| Un seul moteur SQ / licence → jobs concurrents corrompent le databank | **#57 SqJobMutex** — file d''attente SQLite + lock fichier ; un seul sqcli actif |
| Chemins avec espaces (sqcli refuse) | **#58 PathSanitizer** — symlink ou copy vers `~/sq-bridge/staging/` sans espaces |
| Mac sleep interrompt un build SQ de 6h | **#59 CaffeinateWrapper** — `caffeinate -i sqcli …` ou alerte TUI si job long |
| Export XML partiel / tronqué | **#60 XmlValidator** — XSD/shallow DOM check avant parse ; rejet + DLQ |
| Parser TB ne couvre pas un block SQ → crash silencieux | **#61 ParseCoverageReport** — % blocks supportés par stratégie ; gate avant backtest |
| Décalage timezone SQ vs UTC TB (story 2-10) | **#62 UtcNormalizationContract** — test golden timestamp sur 1 stratégie exportée Mac |
| Data SQ ≠ data OANDA TB → faux écarts backtest | **#63 DataProvenanceTag** — manifest indique source bars ; warning si mismatch |
| sqcli cold start 2–3 min à chaque job | **#64 SqCliDaemon** — garder sqcli en mode interactif (engine background) ; TB envoie commandes via stdin/fichier |
| SQ Custom Project n''appelle pas TB → polling seulement | **#65 ShellHookInCustomProject** — script `curl localhost:8080/events/sq-done` en fin de workflow SQ |
| Databank export race (SQ écrit pendant export) | **#66 ExportAfterQuiesce** — sqcli wait + retry si fichier XML size change |
| 500 XML identiques re-parsés chaque nuit | **#67 ContentHashDedup** — skip si SHA-256 déjà dans event store |
| Stratégie promote en prod casse après upgrade SQ Build | **#68 SqBuildPin** — lockfile version SQ dans manifest |
| Indicateurs externes CSV mal formatés → SQ crash | **#69 ExtIndicatorSchemaTest** — unit test TB génère CSV sample ; sqcli dry-run import |
| Secrets / chemins absolus Mac dans XML SQ | **#70 PathAgnosticExport** — doc + lint : pas de `Users/martinfou/...` hardcodé |
| `.sqx` exporté par erreur → TB ignore | **#71 InboxTypeRouter** — `.xml` → parser, `.sqx` → log « non supporté », `.csv` → trades importer |
| Perte de traçabilité « qui a lancé quoi » | **#72 JobAuditLog** — chaque job sqcli loggé dans event store avec exit code + durée |
| Feedback TB→SQ jamais utilisé (boucle morte) | **#73 FitnessCsvAutoPublish** — après backtest batch, auto-génère CSV + sqcli import si `--sq-feedback` |
| TUI/runtime down pendant drop hot folder | **#74 OfflineInboxDrain** — CLI standalone `SqInboxProcessor` sans runtime pour dev local Mac |
| Sur-confiance backtest TB vs SQ Retester | **#75 CrossValidationSample** — 1×/semaine N stratégies comparées SQ export trades vs TB |
| Repo encombré de milliers de Java generated | **#76 PromoteOnlyCodegen** — codegen (2-9) seulement top-K, pas tout le databank |
| sqx-tool Python dans install SQ non versionné | **#77 SqTemplateInRepo** — templates projet SQ copiés dans `sq-templates/` git, pas dans install SQ |
| Oublier que SQ Mac = pas Wine | **#78 NativeMacPipeline** — tout le MVP assume ProcessBuilder local ; abandon idée agent Windows (#18) |

---

## Inventaire complet par thème

### Thème A — Pont sqcli (orchestration)

#1 SqCliBridge, #4 CommandScript Registry, #12 Headless Sidecar, #13 mvn sq:run, #17 Health Probe, #56 SqCliLocator, #57 SqJobMutex, #64 SqCliDaemon, #72 JobAuditLog, #78 NativeMacPipeline

### Thème B — Hot folder & ingest (SQ→TB)

#2 HotFolder Ingest, #10 Manifest Pipeline, #32 DLQ, #33 Incremental Sync, #35 Lakehouse, #48 Cross-Dock, #60 XmlValidator, #66 ExportAfterQuiesce, #67 ContentHashDedup, #71 InboxTypeRouter, #74 OfflineInboxDrain

### Thème C — Runtime & control plane

#3 Runtime Job sq-generate, #19 Queue Decoupling, #36 StrategyBorn Event, #38 Outbox Pattern, #40 CQRS Commands, #65 ShellHook Custom Project

### Thème D — Entonnoir qualité (parse → backtest → promote)

#5 Databank Watcher, #6 Two-Stage Funnel, #15 Parse Gate, #16 Cross-Validation, #28 Champion/Challenger, #46 SqLint, #50 Editorial Workflow, #61 ParseCoverage, #75 CrossValidation Sample, #76 PromoteOnly Codegen

### Thème E — Bidirectionnel TB→SQ (feedback loop)

#8 External Indicator Loop, #9 Feedback Rankings, #26 Feature Store, #29 Hyperparam via Retester, #69 ExtIndicator SchemaTest, #73 FitnessCsv AutoPublish

### Thème F — CI/CD, versioning, ops

#14 Catalogue Sync, #21 Pipeline as Code, #22 Promote Gates, #23 Matrix Build, #43 Lockfile, #68 SqBuild Pin, #58 PathSanitizer, #59 Caffeinate Wrapper, #62 UTC Contract, #63 Data Provenance

### Thème G — Architecture long terme

#7 Genetics vs SQ Router, #11 SqProject Templates, #27 Airflow DAG, #30 Strategy Registry, #34 Data Contract, #44 SQ-XML as IR, #45 Incremental Parse, #77 SqTemplate InRepo

*(Idées #20, #24, #25, #31, #37, #39, #41–#42, #47, #49, #51–#55 couvertes dans thèmes adjacents.)*

---

## Priorisation (Mac, objectif pipeline qui « parle »)

### Top 3 — impact élevé

1. **#2 + #10 + #74 HotFolder + Manifest + OfflineInbox** — MVP sans toucher au runtime ; dossier `data/sq-inbox/` → parse → backtest → `passed/`/`failed/`
2. **#1 + #56 + #57 SqCliBridge + Locator + Mutex** — module `trading-parser` ou petit module `sq-bridge` qui lance sqcli Mac avec config `SQ_HOME`
3. **#8 + #73 External Indicators + AutoPublish** — TB envoie des signaux à SQ ; SQ les utilise en Builder ; boucle fermée unique vs concurrence genetics

### Quick wins (cette semaine)

| Priorité | Action |
|----------|--------|
| QW1 | Créer `data/sq-inbox/{pending,passed,failed,dlq}` + script `scripts/sq-inbox-watch.sh` (fswatch → `mvn exec:java` parse+backtest) |
| QW2 | PoC `SqCliRunner` : `ProcessBuilder` → `$SQ_HOME/sqcli -data action=list` ; log exit code |
| QW3 | Documenter `sq.home` Mac dans `docs/contributing.md` (chemin install SQ, pas d''espaces) |
| QW4 | Un fichier `scripts/sq/commands.txt` versionné (`-data action=update`, `-databank action=list`) testé à la main |

### Breakthrough (moyen terme)

- **#6 Two-Stage Funnel** — SQ génère en masse, TB est l''autorité production
- **#36 + #65 Events** — Custom Project SQ pousse vers control plane ; TUI voit les runs live
- **#44 IR + #76** — XML comme IR ; codegen seulement au promote

---

## Plan d''action MVP (2 sprints légers)

### Sprint A — « Ils se parlent par fichiers » (1–2 jours)

1. Hot folder + manifest JSON (`strategy.manifest.json` : id, hash, sq-build, symbol, status)
2. `SqInboxProcessor` CLI : scan pending → `SqXmlParser` → `RunBacktest` → move + log
3. Test avec 3 stratégies existantes `sqimported` re-droppées dans inbox

**Succès :** drop XML → rapport backtest sans intervention manuelle.

### Sprint B — « TB pilote sqcli » (3–5 jours)

1. `SqCliRunner` + config `SQ_HOME` (Mac)
2. Job mutex (fichier lock ou queue SQLite)
3. Script `-run file=scripts/sq/nightly.txt` : update data → list databank → export top-N (commandes à valider sur votre build SQ)
4. Health check optionnel dans runtime `/health` si control plane actif

**Succès :** une commande `mvn … -Dsq.commands=nightly` enchaîne sqcli puis inbox processor.

### Sprint C — « Boucle fermée » (optionnel)

1. TB exporte CSV fitness post-backtest
2. `sqcli -extindicators action=import` (format doc SQ Build 142+)
3. Re-run Retester SQ sur candidats filtrés

**Succès :** une stratégie améliorée après un cycle TB→SQ→TB.

---

## Session Summary

**Réalisations :** 78 idées · 3 techniques · 7 thèmes · MVP Mac priorisé

**Insight clé :** Sur Mac, le chemin le plus court n''est pas un agent distant ni Docker — c''est **hot folder + sqcli wrapper + mutex**, puis **indicateurs externes** pour la vraie bidirectionnalité.

**Idée abandonnée :** #18 Windows Agent (non pertinent pour votre setup).

**Prochaine étape recommandée :** Sprint A (hot folder) — valeur immédiate, zero dépendance licence sqcli concurrente.
