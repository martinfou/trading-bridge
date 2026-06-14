# Story 25.5: Logique de Comfort Level, validations financières et désérialisation Jackson

Status: done

## Story

En tant que développeur,
Je veux analyser la réponse JSON du LLM de manière résiliente avec Jackson et appliquer des validations programmatiques strictes,
afin de corriger les anomalies de casse et rejeter les ordres financiers incohérents avant qu'ils ne soient transmis aux modules d'exécution.

## Acceptance Criteria

1. **Given** un JSON brut retourné par le LLM
   **When** le service désérialise le JSON avec Jackson
   **Then** il utilise un `ObjectMapper` insensible à la casse pour les enums (e.g. `BULLISH`, `Bullish`, `bullish` se désérialisent tous correctement vers `MarketDirection.BULLISH`).

2. **Given** un outlook désérialisé brut (`WeeklyStrategyOutlookRaw`)
   **When** le service convertit l'outlook brut vers le DTO final `WeeklyStrategyOutlook`
   **Then** il calcule programmatiquement le `ComfortLevel` selon les règles déterministes suivantes :
   - **HIGH** : Le biais n'est pas `NEUTRAL`, le win-rate de saisonnalité est $\ge 60.0\%$, le score de sentiment est aligné avec le biais ($> 0.2$ pour `BULLISH` ou $< -0.2$ pour `BEARISH`), et les indicateurs `macroEventConflict` et `sentimentDivergence` sont tous deux à `false`.
   - **LOW** : Au moins l'une des conditions suivantes est vraie : `macroEventConflict` est `true`, `sentimentDivergence` est `true`, le win-rate de saisonnalité est $< 50.0\%$, ou le biais est `NEUTRAL`.
   - **MEDIUM** : Dans tous les autres cas (comportant le cas par défaut/catch-all).

3. **Given** un outlook converti
   **When** le service valide les setups de transaction
   **Then** il lève une `ValidationException` si :
   - Le paramètre `targetedPriceZone` d'un setup n'est pas strictement positif ou s'il dévie de plus de $\pm 5.0\%$ par rapport au prix de clôture de l'actif.
   - Le paramètre `invalidationPips` d'un setup n'est pas strictement positif ou n'est pas compris dans la plage de $10$ à $200$ pips.
   - L'association du biais de l'outlook, de la direction (`side` : `BUY` / `SELL`) et du type de trigger (`type` : `LIMIT` / `STOP` / `MARKET`) viole les règles de directionnalité :
     - Biais `BULLISH` permet uniquement les ordres acheteurs directionnels (`side == BUY` et `type` parmi `BUY_LIMIT`, `BUY_STOP`, `MARKET`).
     - Biais `BEARISH` permet uniquement les ordres vendeurs directionnels (`side == SELL` et `type` parmi `SELL_LIMIT`, `SELL_STOP`, `MARKET`).
     - Biais `NEUTRAL` interdit tout setup (la liste `setups` doit être vide).
   - Les clés présentes dans la table `executionContextRules` contiennent des éléments autres que `"thresholdPrice"`, `"triggerOffset"` ou `"trendStrength"`.

4. **Given** la classe de test `AgenticStrategistServiceTest`
   **When** les tests unitaires sont lancés
   **Then** ils vérifient de bout en bout l'insensibilité à la casse, le calcul du niveau de confort (HIGH, MEDIUM, LOW) et le rejet des setups non conformes aux limites financières et aux règles de biais.

## Tasks / Subtasks

- [x] **Task 1: Déclarer l'exception personnalisée ValidationException (AC 3)**
  - [x] Créer la classe `ValidationException` sous `com.martinfou.trading.intelligence.agent` étendant `RuntimeException`.
- [x] **Task 2: Configurer la désérialisation Jackson insensible à la casse (AC 1)**
  - [x] Instancier un `ObjectMapper` Jackson avec `MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS` activé.
  - [x] Modifier l'interface interne `AgenticStrategist` pour retourner un type `String` brut, permettant une désérialisation Jackson explicite et maîtrisée.
- [x] **Task 3: Coder l'algorithme de calcul du ComfortLevel (AC 2)**
  - [x] Ajouter une méthode utilitaire calculant la valeur de `ComfortLevel` (HIGH, MEDIUM, LOW) à partir des attributs de `WeeklyStrategyOutlookRaw`.
- [x] **Task 4: Implémenter le service de validation financière (AC 3)**
  - [x] Implémenter les règles de cohérence de prix ($\pm 5\%$) et d'invalidation ($10$ à $200$ pips).
  - [x] Valider l'association biais/trigger (Biais BULLISH → ordres d'achat, Biais BEARISH → ordres de vente, Biais NEUTRAL → vide).
  - [x] Restreindre les clés de configuration autorisées dans `executionContextRules`.
- [x] **Task 5: Mettre à jour la signature de run d'AgenticStrategistService (AC 1, AC 2, AC 3)**
  - [x] Modifier le type de retour pour renvoyer le record final `WeeklyStrategyOutlook` de `trading-core`.
  - [x] Assembler la logique : Ingestion prix -> Appel LLM -> Parse Jackson -> Calcul ComfortLevel -> Validations -> Retour.
- [x] **Task 6: Enrichir la suite de tests unitaires (AC 4)**
  - [x] Ajouter les cas de test dans `AgenticStrategistServiceTest` pour valider l'insensibilité à la casse.
  - [x] Tester les transitions de `ComfortLevel` (HIGH, MEDIUM, LOW).
  - [x] Valider le déclenchement de `ValidationException` pour chaque règle de garde-fou financier.

## Dev Notes

- **Conventions de DTO :**
  - `WeeklyStrategyOutlook` et `ComfortLevel` sont définis dans le package `com.martinfou.trading.core.agent` du module `trading-core`.
  - `WeeklyStrategyOutlookRaw` est défini dans `trading-intelligence` et sert uniquement à capter la structure brute générée par le modèle de chat.
  - Pas de Spring, pas de Lombok.
- **Conversion et types :**
  - Attention à l'ordre des vérifications : la validation ne doit s'effectuer que sur des données valides syntaxiquement. Le calcul du ComfortLevel s'applique avant les validations de setups.
  - Si le biais est `NEUTRAL`, l'absence de setups est obligatoire. Tout setup présent pour un biais `NEUTRAL` doit lever une `ValidationException`.

### Project Structure Notes

- Package : `com.martinfou.trading.intelligence.agent`
- Emplacements des fichiers :
  - Exception : `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/ValidationException.java`
  - Service : `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticStrategistService.java`
  - Tests : `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticStrategistServiceTest.java`

### References

- **Spécifications fonctionnelles (PRD §6.3) :** [prd.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#6.3-validation-&-safe-fallbacks-%5Breq-ag-09%5D)
- **Spécifications des Epics (Story 25.5) :** [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#story-25.5:-logique-de-comfort-level-validations-financieres-et-deserialisation-jackson)
- **Structure de la Story précédente (Story 25.4) :** [25-4-developpement-du-service-d-orchestration-agenticstrategistservice-et-de-la-boucle-react.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/25-4-developpement-du-service-d-orchestration-agenticstrategistservice-et-de-la-boucle-react.md)

## Dev Agent Record

### Agent Model Used

Antigravity (Google DeepMind)

### Debug Log References

- mvn test -pl trading-intelligence -am

### Completion Notes List

- Désérialisation Jackson insensible à la casse implémentée pour gérer de manière résiliente les réponses du LLM.
- Algorithme déterministe de calcul du ComfortLevel (HIGH, MEDIUM, LOW) programmé selon le win-rate de saisonnalité, le score de sentiment et les indicateurs macro/sentiment.
- Validations financières strictes développées (targetedPriceZone à +/- 5%, invalidationPips entre 10 et 200, conformité biais/side/trigger, clés autorisées d'executionContextRules).
- Suite de tests unitaires exhaustive écrite dans AgenticStrategistServiceTest vérifiant de bout en bout ces règles de validation et de calcul.
- Corrections de revue de code intégrées et validées (validation du type de trigger, rejet du biais nul, thread-safety, null-safety de TokenUsage, filtrage regex des briefs, mise en cache barsDir).

### File List

- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/ValidationException.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/AgenticStrategistService.java`
- `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/AgenticStrategistServiceTest.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/utils/InstrumentUtil.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/GuardrailChatModel.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SeasonalityTools.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/MacroTools.java`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SentimentTools.java`
- `trading-data/src/main/java/com/martinfou/trading/data/SeasonalityAnalyzer.java`
- `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/WeeklyStrategyOutlookRawTest.java`
