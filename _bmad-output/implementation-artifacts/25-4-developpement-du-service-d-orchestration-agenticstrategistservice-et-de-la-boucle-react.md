# Story 25.4: Développement du service d'orchestration AgenticStrategistService et de la boucle ReAct

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

En tant que développeur,
Je veux implémenter le service d'orchestration central basé sur une boucle ReAct de LangChain4j,
afin que le LLM puisse invoquer séquentiellement les outils et formuler des perspectives de marché structurées.

## Acceptance Criteria

1. **Given** le fichier de prompt d'orchestration à l'emplacement [agentic-strategist-system.txt](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/resources/prompts/agentic-strategist-system.txt)
   **When** `AgenticStrategistService.run(String asset, Instant cutoff)` est appelé
   **Then** le service extrait le dernier prix de clôture de l'actif avant le cutoff en lisant les barres via [SeasonalityAnalyzer.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/SeasonalityAnalyzer.java)
   **And** il démarre un service d'intelligence artificielle LangChain4j en lui injectant les variables `targetAsset`, `currentAssetPrice` et `cutoffTimestamp`.

2. **Given** l'orchestration de l'agent en cours d'exécution
   **When** le LLM utilise la boucle ReAct avec les outils `MacroTools`, `SentimentTools` et `SeasonalityTools`
   **Then** le nombre de cycles (itérations du modèle) est limité à un maximum de 4 pour éviter toute boucle infinie.

3. **Given** le processus de la boucle de l'agent
   **When** la durée d'exécution totale dépasse 40 secondes OU le coût cumulé estimé des jetons (tokens) dépasse $0.50 USD
   **Then** l'exécution est immédiatement interrompue et lève une exception dédiée (TimeoutException ou exception de limite de budget).

4. **Given** la suite de tests de validation
   **When** les tests de `AgenticStrategistServiceTest` sont exécutés
   **Then** ils valident de bout en bout l'injection des variables, la limitation des itérations, le respect du timeout de 40 secondes et le calcul de la garde de coût de $0.50 USD en utilisant des mocks.

## Tasks / Subtasks

- [x] **Task 1: Créer le fichier de prompt de l'agentic strategist (AC 1)**
  - [x] Créer le fichier [agentic-strategist-system.txt](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/resources/prompts/agentic-strategist-system.txt)
  - [x] Intégrer les sections de définition du rôle, des contraintes logiques de classification du régime, de divergence du sentiment et d'invalidation de l'alpha selon le PRD §4.
  - [x] Inclure explicitement les variables `{{targetAsset}}`, `{{currentAssetPrice}}` et `{{cutoffTimestamp}}` pour injection dynamique.
- [x] **Task 2: Développer le décorateur de modèle de chat avec garde-fous (AC 2, AC 3)**
  - [x] Créer la classe `GuardrailChatModel` dans [com.martinfou.trading.intelligence.agent](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent) implémentant `ChatLanguageModel`.
  - [x] Suivre le nombre d'itérations et lancer une exception si `iterations > 4`.
  - [x] Calculer le coût de chaque requête/réponse selon le modèle (ex. gpt-4o: $2.50/M tokens en entrée, $10.00/M tokens en sortie ; deepseek-chat: $0.14/M tokens en entrée, $0.28/M tokens en sortie) et lancer une exception si le coût cumulé dépasse $0.50 USD.
- [x] **Task 3: Implémenter le service d'orchestration AgenticStrategistService (AC 1, AC 2, AC 3)**
  - [x] Créer la classe `AgenticStrategistService` sous [com.martinfou.trading.intelligence.agent](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent).
  - [x] Récupérer le prix de clôture à l'instant `cutoff` en utilisant `SeasonalityAnalyzer.loadBars` et en filtrant les barres.
  - [x] Instancier le modèle de chat via `AgenticModelFactory.createChatModel()` et l'envelopper dans `GuardrailChatModel`.
  - [x] Configurer `AiServices` en lui associant le modèle décoré, les outils (`MacroTools`, `SentimentTools`, `SeasonalityTools`) et le `SystemMessageProvider` résolvant le prompt du fichier texte.
  - [x] Exécuter la boucle d'analyse de l'agent dans un thread séparé avec un timeout de 40 secondes en utilisant un `ExecutorService` ou `CompletableFuture` et récupérer le `WeeklyStrategyOutlookRaw`.
- [x] **Task 4: Rédiger les tests unitaires et de robustesse (AC 4)**
  - [x] Créer le fichier de test [AgenticStrategistServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticStrategistServiceTest.java).
  - [x] Tester que le modèle reçoit bien les variables injectées.
  - [x] Tester le déclenchement d'une exception lorsque le nombre d'itérations dépasse 4 (en simulant des appels d'outils successifs avec un mock `ChatLanguageModel`).
  - [x] Tester le déclenchement d'une exception de coût lorsque la garde-fou de $0.50 USD est atteinte.
  - [x] Tester le comportement d'abandon par timeout de 40 secondes.
  - [x] Valider que le build Maven du projet complet compile et que tous les tests passent avec succès.

## Dev Notes

- **Modèle de conception (Design Patterns) :**
  - Utiliser le pattern Decorator pour `GuardrailChatModel` afin d'appliquer dynamiquement les contrôles de coût et d'itérations sans modifier le client ou la factory `AgenticModelFactory`.
  - Pas de framework magique (pas de Spring, pas de Lombok).
  - Normalisation de l'actif de `EUR_USD` en `EUR/USD` pour charger les barres via `SeasonalityAnalyzer`.
- **Intégration de LangChain4j :**
  - Utiliser la version `0.33.0` déclarée dans le parent POM.
  - Utiliser `AiServices.builder(...)` pour assembler l'interface de l'agent d'analyse.
  - Enregistrer `MacroTools`, `SentimentTools` et `SeasonalityTools` comme outils de l'AI Service.
- **Désérialisation :**
  - Le service retournera initialement un objet `WeeklyStrategyOutlookRaw` brut retourné par le LLM. Les validations métiers complexes (confort level, tolérance aux minuscules/majuscules) font l'objet de la Story 25.5 et ne doivent pas être implémentées dans cette story.

### Project Structure Notes

- Package de destination : `com.martinfou.trading.intelligence.agent`
- Emplacements des fichiers :
  - Prompt : `trading-intelligence/src/main/resources/prompts/agentic-strategist-system.txt`
  - Classe service : `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticStrategistService.java`
  - Classe de garde-fou : `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/GuardrailChatModel.java`
  - Test unitaire : `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticStrategistServiceTest.java`

### References

- **Spécifications du prompt et de la boucle ReAct (PRD §4 et §6.2) :** [prd.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#4.-quantitative-analysis-system-prompt-blueprint-%5Breq-ag-05%5D) et [prd.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#6.2-react-loop-&-cost-guardrails-%5Breq-ag-08%5D)
- **Spécifications des Epics (Story 25.4) :** [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#story-25.4:-developpement-du-service-d-orchestration-agenticstrategistservice-et-de-la-boucle-react)
- **Détails de l'implémentation des outils (Story 25.3) :** [25-3-implementation-des-outils-d-ingestion-avec-isolation-temporelle.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/25-3-implementation-des-outils-d-ingestion-avec-isolation-temporelle.md)

## Dev Agent Record

### Agent Model Used

Antigravity (Google DeepMind)

### Debug Log References

- None.

### Completion Notes List

- Implemented `AgenticStrategistService` executing a ReAct loop with budget ($0.50 USD), iteration limit (4), and timeout (40s) safety guardrails.
- Configured dynamic prompt template injection resolving asset price dynamically before the cutoff.
- Added dependency `dev.langchain4j:langchain4j` in `trading-intelligence` module to resolve `AiServices`.
- Refactored `SeasonalityAnalyzer` in `trading-data` module to evaluate its data directory dynamically (avoiding unit test pollution from static final fields).
- Wrote robust mock unit tests verifying all constraints (timeout, cost calculation under both GPT-4 and DeepSeek, 4 iterations limit, variable interpolation).

### File List

- `trading-intelligence/src/main/resources/prompts/agentic-strategist-system.txt`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticStrategistService.java`
- `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticStrategistServiceTest.java`
- `trading-intelligence/pom.xml`
- `trading-data/src/main/java/com/martinfou/trading/data/SeasonalityAnalyzer.java`
