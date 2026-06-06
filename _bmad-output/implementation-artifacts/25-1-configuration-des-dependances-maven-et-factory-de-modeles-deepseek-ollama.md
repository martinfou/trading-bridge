# Story 25.1: Configuration des dépendances Maven et Factory de modèles DeepSeek/Ollama

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to add the required LangChain4j dependencies and implement an LLM client factory,
so that I can connect to DeepSeek or local Ollama models dynamically.

## Acceptance Criteria

1. **Given** the Maven parent POM and `trading-intelligence/pom.xml`
   **When** I add dependencies for `langchain4j-open-ai` and `langchain4j-ollama` using the LangChain4j BOM (version `0.33.0`)
   **Then** the project compiles successfully using `mvn clean install`
2. **Given** `AgenticModelFactory` is called with `DEEPSEEK_API_KEY` present in the environment
   **When** I request a chat model instance
   **Then** it returns an `OpenAiChatModel` configured with:
     - `baseUrl`: `https://api.deepseek.com`
     - `apiKey`: value of `DEEPSEEK_API_KEY`
     - `modelName`: `deepseek-chat` (or `deepseek-reasoner` if specified via configuration)
     - `timeout`: 40 seconds (global budget match)
3. **Given** `AgenticModelFactory` is called with `DEEPSEEK_API_KEY` absent or configured for local development
   **When** I request a chat model instance
   **Then** it returns an `OllamaChatModel` configured with:
     - `baseUrl`: value of `OLLAMA_HOST` (defaulting to `http://localhost:11434` if unset)
     - `modelName`: `deepseek-r1:8b` (or another local model specified via system property/env)
     - `timeout`: 40 seconds

## Tasks / Subtasks

- [x] **Task 1: Déclaration des dépendances (AC 1)**
  - [x] Ajouter l'importation de la BOM `dev.langchain4j:langchain4j-bom:0.33.0` dans la section `<dependencyManagement>` de [pom.xml](file:///home/martinfou/dev/src/trading-bridge/pom.xml)
  - [x] Ajouter les dépendances `dev.langchain4j:langchain4j`, `dev.langchain4j:langchain4j-open-ai` et `dev.langchain4j:langchain4j-ollama` dans [trading-intelligence/pom.xml](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/pom.xml)
- [x] **Task 2: Implémentation d'AgenticModelFactory (AC 2, AC 3)**
  - [x] Créer la classe `AgenticModelFactory` dans `com.martinfou.trading.intelligence.agent`
  - [x] Implémenter la méthode `createChatModel()` qui résout la clé d'API et retourne le modèle approprié (OpenAiChatModel ou OllamaChatModel) avec les timeouts requis (40 secondes)
- [x] **Task 3: Écriture des tests unitaires (AC 1, 2, 3)**
  - [x] Créer `AgenticModelFactoryTest` dans le dossier de test de `trading-intelligence`
  - [x] Valider l'instanciation correcte des modèles selon la présence/absence de la variable d'environnement `DEEPSEEK_API_KEY`

### Review Findings

- [x] [Review][Patch] Hardcoded LangChain4j BOM version in parent POM [pom.xml:42]
- [x] [Review][Patch] Redundant direct dependency on langchain4j core [trading-intelligence/pom.xml:48]
- [x] [Review][Patch] Missing configurable model selection for DeepSeek Chat Model [AgenticModelFactory.java:51]
- [x] [Review][Patch] Missing configurable local model selection for Ollama [AgenticModelFactory.java:62]
- [x] [Review][Patch] Hardcoded DeepSeek API base URL [AgenticModelFactory.java:50]
- [x] [Review][Patch] Hardcoded timeout limits [AgenticModelFactory.java:52]
- [x] [Review][Patch] Strict reliance on environment variables [AgenticModelFactory.java:34]
- [x] [Review][Patch] Magic string fallback logic and whitespace vulnerability [AgenticModelFactory.java:46]
- [x] [Review][Patch] Silent fallback behavior on configuration errors [AgenticModelFactory.java:46]
- [x] [Review][Patch] Missing Ollama host validation and protocol scheme check [AgenticModelFactory.java:56]
- [x] [Review][Patch] Untested default factory entry point [AgenticModelFactory.java:33]
- [x] [Review][Patch] Shallow unit test assertions [AgenticModelFactoryTest.java]

## Dev Notes

- **Conventions du projet :**
  - Pas de Spring, pas de Lombok.
  - Utilisation d'un logger SLF4J standard pour signaler l'initialisation du modèle.
- **Sécurité (AGENTS.md) :**
  - Ne jamais écrire la clé d'API en dur dans le code ou les fichiers de configuration.
  - S'assurer que les clés d'API ne sont pas journalisées dans les consoles de log ou les fichiers plats.

### Project Structure Notes

- Module cible : `trading-intelligence`
- Packages : `com.martinfou.trading.intelligence.agent`

### References

- [Spécifications PRD de l'Agentic Strategist](file:///home/martinfou/dev/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#5.1-Model-Configuration-and-Factory)
- [Document d'architecture technique](file:///home/martinfou/dev/src/trading-bridge/_bmad-output/planning-artifacts/architecture.md#Model-Factory-and-Provider-Abstraction)

## Dev Agent Record

### Agent Model Used

- Antigravity (Google DeepMind)

### Debug Log References

- Résolution d'un conflit de versions JUnit 5 via l'import de `org.junit:junit-bom:5.11.0` dans le POM parent.
- Résolution d'un conflit de versions Jackson via l'import explicite de `jackson-core` et `jackson-annotations` dans le POM parent pour correspondre à `${jackson.version}` (2.17.2).

### Completion Notes List

- Déclaration de `dev.langchain4j:langchain4j-bom:0.33.0` importée dans `<dependencyManagement>` du POM parent.
- Déclaration des dépendances `langchain4j`, `langchain4j-open-ai` et `langchain4j-ollama` dans `trading-intelligence/pom.xml`.
- Création et implémentation de [AgenticModelFactory](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticModelFactory.java) pour configurer `OpenAiChatModel` (pour DeepSeek) ou `OllamaChatModel` (local) avec un timeout de 40 secondes et log SLF4J sans fuite de clé d'API.
- Création et validation des tests unitaires complets dans [AgenticModelFactoryTest](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticModelFactoryTest.java).

### File List

- [pom.xml](file:///home/martinfou/dev/src/trading-bridge/pom.xml) (Modifié)
- [trading-intelligence/pom.xml](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/pom.xml) (Modifié)
- [AgenticModelFactory.java](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticModelFactory.java) (Nouveau)
- [AgenticModelFactoryTest.java](file:///home/martinfou/dev/src/trading-bridge/trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticModelFactoryTest.java) (Nouveau)
