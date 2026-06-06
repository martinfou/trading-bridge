---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "file:///home/martinfou/dev/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md"
  - "file:///home/martinfou/dev/src/trading-bridge/_bmad-output/project-context.md"
workflowType: 'architecture'
project_name: 'Trading Bridge'
user_name: 'Martin Fournier'
date: '2026-06-06'
lastStep: 8
status: 'complete'
completedAt: '2026-06-06'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
- **REQ-AG-01 (Orchestrator Ingestion & Metrics):** Ingest macro events, news sentiment, and seasonality metrics to yield a structured strategy outlook. Target $\ge 85\%$ regime classification accuracy and $\le 15$s latency.
- **REQ-AG-03 (Module Placement):** All orchestration logic and LangChain4j clients must reside in the `trading-intelligence` module. Shared execution DTOs must live in `trading-core` to preserve acyclic compilation.
- **REQ-AG-05 (ReAct Prompting Blueprint):** Orchestrate dynamic tools (Macro, Sentiment, Seasonality) and produce trigger conditions. Math calculations and comfort-level rules are computed programmatically in Java rather than inside the system prompt.
- **REQ-AG-06 (Structured Target Schema):** Separate `WeeklyStrategyOutlookRaw` (LLM-facing output) from `WeeklyStrategyOutlook` (final DTO record with derived `ComfortLevel`).
- **TOOL-01 / TOOL-02 / TOOL-03:** Ingest clean, non-lookahead CPI calendar actuals, sentiment, and seasonality data relative to the simulation execution timestamp.

**Non-Functional Requirements:**
- **Performance & Timeouts:** Strict 40s execution thread limit, ReAct loops restricted to 4 turns (parallel tool calls count as 1 turn). Individual tool timeouts set to 3.0s with a 1-second retry delay.
- **Rate-limit & Cost Safety:** Cost ceiling of $0.50 USD per execution run.
- **Local Fallback:** Support for local Ollama weights for developer testing, while high-reasoning cloud APIs are required for valid backtesting.

**Scale & Complexity:**
- Primary domain: API & Backend (Java 21, LangChain4j, Jackson)
- Complexity level: High (ReAct looping, time-boundary isolation, structured JSON mappings)
- Estimated architectural components: 4 components (Orchestration Service, ReAct Tool Registry, Post-Processing & Validation Engine, Safe Fallback Controller)

### Technical Constraints & Dependencies
- Must align with Java 21 records and omit Lombok or Spring.
- Acyclic module graph: `trading-intelligence` must never be a dependency of `trading-core` or `trading-strategies`. Target records must reside in `trading-core` (`com.martinfou.trading.core.agent`).
- Tool calls must mask future macro events (`actual` field) if their timestamp is ahead of the execution/simulation timestamp.

### Cross-Cutting Concerns Identified
- **Time Boundary Isolation:** Preventing future lookahead leakage during backtesting runs.
- **Jackson Enum Case Insensitivity:** Tolerating capitalization mismatches from LLM enum outputs.
- **Asynchronous Loop Timeout Management:** Guaranteeing execution time for fallback logging and metrics write before thread interruption.
- **Telemetry & Alerting:** Logging stack traces via SLF4J and tracking bypass counts via the `agentic_fallback_failures_total` counter.

## Starter Template Evaluation

### Primary Technology Domain
API & Backend (Java 21, LangChain4j, Jackson) intégré directement au sein du monorepo existant `trading-bridge`.

### Starter Options Considered
1. **Création d'un microservice autonome (Spring Boot / Quarkus externe)** :
   - *Avantages* : Isolation stricte de la JVM et des dépendances LLM, cycle de déploiement indépendant.
   - *Inconvénients* : Latence réseau significative (REST/gRPC) pénalisante pour les backtests à haute vitesse, complexité de déploiement additionnelle, duplication des structures de données de trading (`Bar`, `Order`, etc.).
2. **Module Maven intégré au monorepo existant (Module `trading-intelligence`)** [Recommandé] :
   - *Avantages* : Exécution en mémoire (JVM partagée) éliminant toute latence réseau pour les backtests, réutilisation directe des modèles de domaine via le graphe acyclique, intégration transparente avec le Maven Wrapper (`./mvnw`) et le processus de build global.
   - *Inconvénients* : Nécessité de gérer proprement l'isolation des dépendances tierces (comme LangChain4j) au niveau du module pour ne pas polluer les autres modules.

### Selected Starter: Monorepo Maven existant ("Trading Bridge")

**Rationale for Selection:**
L'intégration directe dans le monorepo via le module `trading-intelligence` est la solution optimale. Elle permet de conserver une architecture simple, évite les coûts d'infrastructure et de réseau, et garantit que le moteur de backtest (`trading-backtest`) puisse appeler l'orchestrateur de marché avec des performances maximales lors des simulations historiques.

**Initialization Command:**
S'agissant d'un projet existant (brownfield), aucune commande d'initialisation de template externe n'est requise. Les outils de build et de dépendances sont déjà en place. La compilation et validation du projet complet s'effectuent via :
```bash
./mvnw clean install
```

**Architectural Decisions Provided by Starter:**

**Language & Runtime:**
- Java 21 (JDK 21)
- LangChain4j (version stable 1.15.1 ou version BOM équivalente)
- Jackson 2.17.2 pour la sérialisation/désérialisation JSON insensible à la casse

**Styling Solution:**
- Sans objet (module pur Backend, pas d'interface utilisateur directe dans `trading-intelligence`).

**Build Tooling:**
- Maven 3.9.6 / 4.x géré par le Maven Wrapper `./mvnw` récemment configuré.

**Testing Framework:**
- JUnit 5.11.0 pour les tests unitaires et d'intégration, complété par des mocks pour isoler les appels vers les LLM lors des builds automatisés (CI).

**Code Organization:**
- Logique d'orchestration et clients LLM : `com.martinfou.trading.intelligence.agent` (dans le module `trading-intelligence`).
- DTOs de sortie stables et partagés : `com.martinfou.trading.core.agent` (dans le module `trading-core`).

**Development Experience:**
- Exécution en local via l'intégration au CLI `RunBacktest` existant ou au panneau de contrôle Java (`ControlPlaneMain`).

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- **Choix du LLM Principal** : Utilisation de **DeepSeek** via le connecteur compatible OpenAI de LangChain4j (`langchain4j-open-ai`), configuré avec l'URL de base `https://api.deepseek.com` et les modèles `deepseek-chat` / `deepseek-reasoner`.
- **Modèle de Développement Local** : Support d'Ollama (`langchain4j-ollama`) exécutant un modèle local (ex: `deepseek-r1` ou équivalent) pour le développement offline et les tests unitaires gratuits.
- **Gestion des Données de Simulation** : Les outils d'ingestion (Macro et Sentiment) appliquent un filtre strict basé sur le timestamp de simulation courant pour masquer les données futures (prévention absolue du *lookahead bias*).
- **Persistance des Outlooks** : Stockage sous forme de fichiers JSON plats dans `data/agentic-outlooks/{annee-semaine}.json` pour une portabilité optimale entre les runs de backtest hors ligne et le control plane en ligne.
- **Apprentissage Continu (Experience Store)** : Un mécanisme d'archivage des erreurs (post-mortem) génère des fiches "Leçons Apprises" au format JSON. Ces leçons sont récupérées de manière sémantique ou contextuelle et injectées dans le prompt de DeepSeek (*few-shot learning*) pour améliorer ses décisions au fil du temps.

**Important Decisions (Shape Architecture):**
- **Résilience & Fallback Conservateur** : En cas de timeout LLM (limite globale de 40s), d'erreur d'API ou de parsing, le système génère un Outlook neutre ("FLAT/NEUTRAL") avec un niveau de confort à 0, et incrémente la métrique d'alerte `agentic_fallback_failures_total`.

**Deferred Decisions (Post-MVP):**
- **Stockage SQL Relationnel** : L'utilisation de SQLite pour indexer les outlooks est différée. Le stockage de fichiers JSON plats est privilégié pour sa simplicité et sa facilité d'inspection.

### Data Architecture
- **Choix** : Fichiers JSON plats nommés selon la semaine (ex: `outlook-2026-W23.json`) dans `data/agentic-outlooks/`. Les leçons apprises sont stockées dans `data/experience-store/{lecon-id}.json`.
- **Rationale** : Facilité d'édition manuelle par l'utilisateur, traçabilité sous Git, et lecture instantanée par le moteur de backtest.

### Authentication & Security
- **Choix** : Gestion des accès via les variables d'environnement `DEEPSEEK_API_KEY` et `OLLAMA_HOST`. Aucun secret n'est écrit dans le code source ou les fichiers de configuration versionnés.

### API & Communication Patterns
- **Choix** : Service Java synchrone `AgenticStrategistService` hébergé dans `trading-intelligence`. Il est directement instanciable par le CLI de backtest (`RunBacktest`) et exposé via les contrôleurs du plan de contrôle (`ControlPlaneMain`) pour le trading en temps réel.

### Frontend Architecture
- **Choix** : Sans objet (intégration Backend uniquement).

### Infrastructure & Deployment
- **Production / Backtesting Officiel** : API hébergée DeepSeek (requiert une clé valide et une connexion Internet).
- **Développement local** : Modèle local via Ollama (`http://localhost:11434`).

### Decision Impact Analysis

**Implementation Sequence:**
1. Ajout de `langchain4j-open-ai` et `langchain4j-ollama` dans la section `<dependencyManagement>` du POM parent et dans le POM de `trading-intelligence`.
2. Création de `AgenticModelFactory` pour instancier dynamiquement le modèle (DeepSeek ou Ollama) selon les variables d'environnement.
3. Développement du service d'orchestration `AgenticStrategistService` exécutant la boucle ReAct (max 4 itérations).
4. Implémentation du parseur résilient de `WeeklyStrategyOutlookRaw` vers le DTO final dans `trading-core`.
5. Implémentation du RAG d'expérience (`ExperienceStoreService`) pour charger les leçons apprises et les injecter sous forme d'exemples dans le prompt.

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

**Critical Conflict Points Identified:**
5 zones de conflits potentiels identifiées où les agents d'IA pourraient faire des choix divergents (nommage, structure de packages, gestion des formats JSON, politique de dates et boucle de résilience).

### Naming Patterns

**Code Naming Conventions:**
- **Services** : Le service central doit se nommer `AgenticStrategistService`. La factory de modèles : `AgenticModelFactory`. Le service de gestion de mémoire : `ExperienceStoreService`.
- **Modèles & DTOs** :
  - DTO de retour brut LLM (classe ou record Java) : `WeeklyStrategyOutlookRaw` (reflet fidèle du schéma JSON renvoyé par DeepSeek).
  - Record métier final stable : `WeeklyStrategyOutlook`.
- **Outils d'Agent (Tools)** : Les classes d'outils doivent se terminer par `Tools` (ex: `MacroTools`, `SentimentTools`, `SeasonalityTools`). Les méthodes annotées `@Tool` doivent utiliser le camelCase (ex: `getCpiCalendarEvents(Instant cutoff)`).

**API & Endpoints Naming:**
- Endpoint du Control Plane pour déclencher la génération : `POST /api/agentic-strategist/run` (prend en paramètre optionnel la date de simulation).

### Structure Patterns

**Project Organization:**
- **Code métier (Module `trading-intelligence`)** :
  - Logique d'orchestration & Factory : `com.martinfou.trading.intelligence.agent`
  - Outils connecteurs : `com.martinfou.trading.intelligence.agent.tools`
  - Service mémoire d'expérience : `com.martinfou.trading.intelligence.agent.memory`
- **Modèles de Données partagés (Module `trading-core`)** :
  - Records et Enums : `com.martinfou.trading.core.agent` (ex: `WeeklyStrategyOutlook`, `MarketRegime`, `WeeklyStrategyOutlookRaw`).
- **Tests unitaires et d'intégration** :
  - Placer dans `/src/test/java/com/martinfou/trading/intelligence/agent/` au sein du module concerné.

**File Structure Patterns:**
- Les fichiers d'Outlook générés en production ou backtest : `data/agentic-outlooks/outlook-{annee}-W{semaine}.json`.
- Les fiches leçons apprises : `data/experience-store/lesson-{timestamp-hash}.json`.

### Format Patterns

**Data Exchange Formats (JSON & Serialization):**
- **Jackson Configuration** : La désérialisation doit tolérer les écarts de casse sur les enums de manière insensible (`MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS` activé dans Jackson).
- **Date/Time Standard** : Toutes les dates de simulation et les timestamps internes ou d'outils doivent impérativement utiliser `java.time.Instant` (représentation UTC). Pas de `LocalDateTime.now()` ni de formats locaux.
- **Boolean** : Représentation native `true` / `false` en JSON.

### Communication Patterns

**Event & Logging System:**
- Utilisation stricte de **SLF4J** pour le logging.
- Format des messages de warning lors des fallbacks :
  `[AgenticStrategist] LLM failure detected. Falling back to conservative outlook. Reason: {}`
- Incrémentation du compteur Prometheus/Micrometer local : `agentic_fallback_failures_total`.

### Process Patterns

**Error Handling & Fallback:**
- **Timeout & Retries** : Les outils d'ingestion lèvent une exception en cas de timeout (>3.0s). Le service intercepte et retente une fois après 1.0s. Si l'erreur persiste, elle est propagée au service d'orchestration global.
- **Constructeur Fallback** : Le record `WeeklyStrategyOutlook` doit posséder une factory de fallback : `WeeklyStrategyOutlook.createNeutralFallback(Instant timestamp, String failureReason)`.

**Simulations and Temporal Isolation (Prevention of Lookahead Bias):**
- Chaque outil d'agent (`MacroTools`, `SentimentTools`, `SeasonalityTools`) **doit obligatoirement** recevoir un paramètre `Instant cutoffTimestamp` représentant l'instant de simulation courant.
- Aucune donnée macroéconomique (`actual` field) ou sentiment postérieure à ce `cutoffTimestamp` ne doit être retournée à l'agent.

### Enforcement Guidelines

**All AI Agents MUST:**
- Utiliser uniquement des variables d'environnement pour configurer l'accès à DeepSeek ou Ollama.
- Ne jamais rajouter de dépendance Spring ou Lombok (les records natifs Java 21 et le constructeur standard sont obligatoires).
- Valider la compilation complète via `./mvnw clean install` de tous les modules affectés avant toute validation.

### Pattern Examples

**Good Example:**
```java
public record WeeklyStrategyOutlook(
    Instant timestamp,
    MarketRegime regime,
    double sentimentScore,
    int comfortLevel,
    String reasoning
) {
    public static WeeklyStrategyOutlook createNeutralFallback(Instant timestamp, String reason) {
        return new WeeklyStrategyOutlook(timestamp, MarketRegime.FLAT_NEUTRAL, 0.0, 0, "Fallback: " + reason);
    }
}
```

**Anti-Pattern:**
```java
// À ÉVITER : Utilisation de Lombok, absence de paramètre de coupure temporelle sur l'outil
@Data
public class SentimentTools {
    public double getSentiment() { 
        return Database.loadCurrentSentiment(); // ERREUR : Pas d'isolation temporelle, lookahead bias garanti en backtest
    }
}
```

## Project Structure & Boundaries

### Complete Project Directory Structure

L'intégration de la couche intelligente et des DTOs associés respecte l'arborescence Maven standard du projet :

```
trading-bridge/
├── trading-core/
│   └── src/
│       └── main/
│           └── java/
│               └── com/
│                   └── martinfou/
│                       └── trading/
│                           └── core/
│                               └── agent/
│                                   ├── WeeklyStrategyOutlook.java       # Record final partagé
│                                   ├── MarketDirection.java             # Enum (BULLISH, BEARISH, NEUTRAL)
│                                   ├── MarketRegime.java                # Enum (HIGH_VOL_TREND, etc.)
│                                   ├── ComfortLevel.java                # Enum (HIGH, MEDIUM, LOW)
│                                   ├── TradeTriggerCondition.java       # Record (Trigger individuel)
│                                   └── RiskFactors.java                 # Record (Facteurs de friction)
├── trading-intelligence/
│   ├── pom.xml                                                          # Dépendances LangChain4j ajoutées
│   └── src/
│       ├── main/
│       │   ├── java/
│       │   │   └── com/
│       │   │       └── martinfou/
│       │   │           └── trading/
│       │   │               └── intelligence/
│       │   │                   └── agent/
│       │   │                       ├── WeeklyStrategyOutlookRaw.java     # Record brut miroir de l'LLM
│       │   │                       ├── SentimentData.java                # DTO interne pour l'outil de sentiment
│       │   │                       ├── SeasonalityData.java              # DTO interne pour l'outil de saisonnalité
│       │   │                       ├── AgenticModelFactory.java          # Factory DeepSeek / Ollama
│       │   │                       ├── AgenticStrategistService.java     # Service d'orchestration ReAct
│       │   │                       ├── tools/
│       │   │                       │   ├── MacroTools.java               # Outil d'extraction macroéconomique (ForexFactory RSS)
│       │   │                       │   ├── SentimentTools.java           # Outil d'extraction de sentiment retail
│       │   │                       │   └── SeasonalityTools.java         # Outil de saisonnalité historique
│       │   │                       └── memory/
│       │   │                           ├── ExperienceStoreService.java   # Gestion de la mémoire et des leçons JSON
│       │   │                           └── ExperienceLesson.java         # Record représentant une leçon apprise
│       │   └── resources/
│       │       └── prompts/
│       │           └── agentic-strategist-system.txt                     # Prompt système (avec instructions ReAct)
│       └── test/
│           └── java/
│               └── com/
│                   └── martinfou/
│                       └── trading/
│                           └── intelligence/
│                               └── agent/
│                                   ├── AgenticStrategistServiceTest.java # Tests d'intégration et mocks LLM
│                                   └── tools/
│                                       └── ToolTemporalIsolationTest.java # Test de non-lookahead bias
```

### Architectural Boundaries

**API Boundaries:**
- **Point d'entrée externe** : L'API HTTP du control plane (`POST /api/agentic-strategist/run`) accepte un payload JSON optionnel `{ "cutoffTimestamp": "2026-06-06T12:00:00Z" }` pour simuler ou exécuter l'agent.
- **Sortie** : L'API retourne le DTO structuré complet `WeeklyStrategyOutlook` au format JSON.

**Component Boundaries:**
- `AgenticStrategistService` encapsule l'intégralité de la boucle ReAct de LangChain4j. Les autres modules de Trading Bridge ne peuvent pas appeler les composants LLM directement.
- Les outils (`MacroTools`, etc.) servent de barrière de sécurité temporelle en filtrant activement les résultats retournés au modèle selon le paramètre `cutoffTimestamp`.

**Service Boundaries:**
- `ExperienceStoreService` gère de façon isolée la lecture/écriture des fichiers JSON plats de leçons. Il ne dépend pas d'une base de données relationnelle lourde.

**Data Boundaries:**
- Les modèles LLM ne lisent que des structures Java typées (`List<Event>`, `SentimentData`, `SeasonalityData`) fournies par les outils. Ils n'ont pas d'accès direct en lecture/écriture aux tables de base de données de Trading Bridge ou aux instances d'API brokers.

### Requirements to Structure Mapping

**Feature/Epic Mapping:**
- **REQ-AG-01 & REQ-AG-05 (Orchestration & ReAct)** : Implémenté dans `AgenticStrategistService` et `agentic-strategist-system.txt`.
- **REQ-AG-03 (Isolation des modules)** : Le découpage entre `trading-core/src/.../agent/` et `trading-intelligence/src/.../agent/` garantit la compilation acyclique.
- **REQ-AG-06 (Target Schema & Conversions)** : Le mapping programmé Java et le calcul du niveau de confort se font dans `AgenticStrategistService` lors de la conversion de `WeeklyStrategyOutlookRaw` vers `WeeklyStrategyOutlook`.
- **TOOL-01 / TOOL-02 / TOOL-03 (Outils temporels)** : Implémentés dans `com.martinfou.trading.intelligence.agent.tools.*`.
- **Apprentissage Continu (Memory Store)** : Implémenté dans `com.martinfou.trading.intelligence.agent.memory.*`.

### Integration Points

**Internal Communication:**
Le CLI `RunBacktest` (dans `trading-examples`) ou la boucle principale de backtest (`BacktestEngine` dans `trading-backtest`) instancie `AgenticStrategistService` (ou appelle le Control Plane) pour récupérer les outlooks historiques correspondants à la période simulée et ajuster les filtres de trading.

**External Integrations:**
- Appels HTTPS vers l'API DeepSeek (`https://api.deepseek.com/v1`) via le client HTTP de LangChain4j sous-jacent.
- Appels HTTP locaux vers Ollama (`http://localhost:11434/api/chat`) si configuré.

**Data Flow:**
1. Le client (ex: backtester) appelle `AgenticStrategistService.run(Instant cutoff)`.
2. Le service interroge l'Experience Store pour récupérer les leçons adaptées et construit le prompt final.
3. Le modèle DeepSeek s'exécute, déclenchant des appels d'outils (`MacroTools`, etc.) via la boucle de rétroaction ReAct.
4. Les outils filtrent les données futures grâce au `cutoff` et renvoient les données du passé.
5. DeepSeek retourne le JSON brut correspondant à `WeeklyStrategyOutlookRaw`.
6. Le service valide le format via Jackson (insensible à la casse), calcule programmatiquement le `ComfortLevel` et produit le record final `WeeklyStrategyOutlook`.

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
Toutes les décisions techniques (Java 21, LangChain4j, DeepSeek API et Ollama) s'intègrent de manière parfaitement cohérente au sein du monorepo existant. L'utilisation du protocole compatible OpenAI pour DeepSeek permet d'employer le connecteur standard de LangChain4j sans ajouter de dépendances exotiques.

**Pattern Consistency:**
Les conventions de nommage (records camelCase, suffixes `Tools` et `Service`) s'alignent avec les standards établis pour Trading Bridge. Les formats d'échange (Jackson insensible à la casse et timestamps UTC `Instant`) éliminent les risques classiques de désérialisation incorrecte et de décalages horaires.

**Structure Alignment:**
La structure physique proposée (séparation stricte de `trading-core` et `trading-intelligence`) respecte scrupuleusement la règle fondamentale de l'architecture acyclique du projet : l'LLM reste confiné et le cœur de métier ne dépend pas de l'IA.

### Requirements Coverage Validation ✅

**Epic/Feature Coverage:**
- **REQ-AG-01 (Orchestrator & Latency)** : La boucle ReAct limitée à 4 turns et les timeouts stricts (3s par outil, 40s global) assurent que les contraintes de latence soient garanties par construction.
- **REQ-AG-03 (Module Placement)** : Le placement physique des fichiers résout entièrement le problème de couplage.
- **REQ-AG-05 (ReAct Prompting)** : Le prompt système est hébergé de façon centralisée dans les ressources du module d'intelligence.

**Functional Requirements Coverage:**
- **TOOL-01 / TOOL-02 / TOOL-03 (Biais d'anticipation)** : L'isolation temporelle est assurée par le passage systématique du paramètre `cutoffTimestamp` à tous les outils d'ingestion.
- **Experience Store (Apprentissage continu)** : La boucle de rétroaction post-mortem permet à l'agent de s'améliorer en continu à partir de ses erreurs passées.

**Non-Functional Requirements Coverage:**
- **Sécurité (REQ-AG-04)** : Les secrets transitent uniquement par variables d'environnement.
- **Bypass & Fallback** : Le mécanisme de repli neutre prévient tout blocage de trading live.

### Implementation Readiness Validation ✅

**Decision Completeness:**
Toutes les décisions clés (LLM, stockage JSON, fallback, variables d'environnement) ont été formellement actées avec leurs numéros de version.

**Structure Completeness:**
La structure d'arborescence cible définit précisément chaque classe, record, fichier de prompt et ressource de test.

**Pattern Completeness:**
Les règles d'isolation temporelle et de gestion des erreurs de désérialisation Jackson sont claires et documentées avec des exemples concrets de "Good Example" et "Anti-Pattern".

### Gap Analysis Results

- **Gaps Critiques** : Aucun. Tous les bloqueurs d'implémentation ont été résolus.
- **Gaps Importants** : Aucun. Les aspects de configuration et d'intégration aux tests unitaires ont été précisés.
- **Gaps Mineurs / Améliorations Futures** :
  - À l'avenir, si l'Experience Store contient des centaines de leçons, il sera judicieux de migrer le stockage des fichiers JSON plats vers la base de données relationnelle SQLite existante du control plane pour améliorer la vitesse de recherche indexée.

### Validation Issues Addressed
Toutes les questions soulevées lors de la conception (notamment l'intégration spécifique de DeepSeek compatible OpenAI, la pertinence du backtest sans lookahead bias, et la boucle de rétroaction de coaching RAG) ont été discutées et intégrées.

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status:** READY FOR IMPLEMENTATION

**Confidence Level:** High

**Key Strengths:**
1. **Couplage lâche et Acyclique** : Le cœur de métier (`trading-core`) n'a aucune dépendance vers le framework LLM (`trading-intelligence`).
2. **Robustesse Temporelle** : Les outils d'ingestion protègent mathématiquement le backtest contre le lookahead bias.
3. **Apprentissage Actif** : L'Experience Store permet d'ajuster dynamiquement le prompt sans coût de réentraînement.

**Areas for Future Enhancement:**
1. Migration du stockage des leçons de RAG JSON vers SQLite si le volume d'historique de leçons grandit de manière excessive.

### Implementation Handoff

**AI Agent Guidelines:**
- Follow all architectural decisions exactly as documented
- Use implementation patterns consistently across all components
- Respect project structure and boundaries
- Refer to this document for all architectural questions

**First Implementation Priority:**
Déclarer les dépendances de LangChain4j dans le fichier `trading-intelligence/pom.xml` et créer les records de base dans `trading-core`.
