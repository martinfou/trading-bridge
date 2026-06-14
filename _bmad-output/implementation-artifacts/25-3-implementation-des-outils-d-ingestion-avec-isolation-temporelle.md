# Story 25.3: Implémentation des outils d'ingestion avec isolation temporelle

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

En tant que développeur quantitatif,
Je veux implémenter les outils pour le calendrier macroéconomique, le sentiment et la saisonnalité avec une isolation temporelle stricte,
afin que les données futures soient masquées lors des simulations de backtesting.

## Acceptance Criteria

1. **Given** le package des outils de `trading-intelligence`
   **When** j'implémente `MacroTools`, `SentimentTools` et `SeasonalityTools` comme classes `@Tool` LangChain4j
   **Then** chaque outil requiert un paramètre `Instant cutoffTimestamp` représentant l'instant de simulation courant.
2. **Given** la méthode `fetchEconomicCalendar` dans `MacroTools`
   **When** elle est appelée avec un intervalle temporel et un `cutoffTimestamp`
   **Then** elle filtre et retourne uniquement les événements ayant un impact `HIGH` (ou `ImpactLevel.HIGH`)
   **And** elle masque la valeur `actual` (la remplace par `""`) pour tous les événements dont le timestamp est postérieur au cutoff.
3. **Given** les méthodes de `SentimentTools` et `SeasonalityTools`
   **When** elles interrogent les sources de données historiques (briefs JSON et barres de prix)
   **Then** elles utilisent le paramètre de cutoff pour filtrer et ignorer toutes les données postérieures à cet instant, empêchant toute fuite d'informations futures (lookahead bias).
4. **Given** l'ensemble des outils implémentés
   **When** les tests unitaires de validation sont exécutés
   **Then** ils confirment l'absence de biais de lookahead et le bon fonctionnement des filtres d'impact et de masquage.
   **And** le code compile avec succès.

## Tasks / Subtasks

- [x] **Task 1: Exposer la méthode loadBars de SeasonalityAnalyzer (AC 3)**
  - [x] Modifier [SeasonalityAnalyzer.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/SeasonalityAnalyzer.java) pour passer la visibilité de `loadBars` de `private` à `public`.
- [x] **Task 2: Implémenter MacroTools (AC 1, AC 2)**
  - [x] Créer la classe `MacroTools` dans [MacroTools.java](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/MacroTools.java) sous le package `com.martinfou.trading.intelligence.agent.tools`.
  - [x] Ajouter l'annotation `@Tool` LangChain4j sur la méthode `fetchEconomicCalendar`.
  - [x] Filtrer et masquer les valeurs `actual` des événements postérieurs au `cutoffTimestamp`.
- [x] **Task 3: Implémenter SentimentTools (AC 1, AC 3)**
  - [x] Créer la classe `SentimentTools` dans [SentimentTools.java](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SentimentTools.java) sous le package `com.martinfou.trading.intelligence.agent.tools`.
  - [x] Implémenter la logique de recherche du brief JSON le plus récent sous `data/weekly-intel/` qui est antérieur ou égal au cutoff.
  - [x] Retourner l'objet `SentimentData` formaté (score et ratio) pour l'actif demandé.
- [x] **Task 4: Implémenter SeasonalityTools (AC 1, AC 3)**
  - [x] Créer la classe `SeasonalityTools` dans [SeasonalityTools.java](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SeasonalityTools.java) sous le package `com.martinfou.trading.intelligence.agent.tools`.
  - [x] Utiliser `SeasonalityAnalyzer.loadBars` et filtrer les barres antérieures au cutoff.
  - [x] Grouper par semaine de l'année et calculer le ratio de réussite historique et le nombre de pips moyens pour renvoyer un `SeasonalityData`.
- [x] **Task 5: Écrire les tests unitaires et valider (AC 4)**
  - [x] Créer [ToolsTest.java](file:///Volumes/T7/src/trading-bridge/trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/tools/ToolsTest.java) pour tester rigoureusement l'isolation temporelle et le masquage.
  - [x] Lancer la compilation et valider le bon fonctionnement général via Maven.

### Review Findings

- [x] [Review][Decision] Inclusion de la semaine active en cours de calcul de saisonnalité — SeasonalityTools.java inclut la semaine du cutoff de l'année en cours (si >= 2 barres). Puisque cette semaine est incomplète et représente la période active simulée, son inclusion fausse les statistiques historiques. Faut-il exclure l'année en cours du calcul de la saisonnalité ?
- [x] [Review][Decision] Gestion codée en dur de la taille des pips — getPipSize ne prend en charge que JPY (0.01) et XAU (0.1), et utilise par défaut 0.0001. Cela provoquera des erreurs de calcul de saisonnalité pour les indices boursiers (SPX, NDX), matières premières ou paires exotiques. Faut-il ajouter une gestion dynamique ou déclarer ces actifs hors-scope ?
- [x] [Review][Decision] Requêtes réseau réelles vers OANDA en backtesting — SentimentTools.java effectue un appel API OANDA en direct si le cutoff est proche du moment présent (moins de 24h). Cela introduit un comportement non déterministe dans le backtest et risque de divulguer des identifiants OANDA. Faut-il interdire tout appel réseau et utiliser uniquement le fallback 50/50 en backtesting ?
- [x] [Review][Decision] Absence de validation de l'âge maximal pour le sentiment récupéré — findClosestBrief retourne le brief de sentiment le plus proche antérieur au cutoff, même s'il est très ancien (plusieurs mois). Faut-il définir un âge maximal (ex: 7 ou 14 jours) au-delà duquel on retourne un sentiment neutre (50/50) ?
- [x] [Review][Patch] Formatage de log SLF4J incorrect dans SeasonalityTools [SeasonalityTools.java:276-277]
- [x] [Review][Patch] Risque d'écrasement des données de production locales dans les tests [ToolsTest.java:36-112]
- [x] [Review][Patch] Crash potentiel lors du parcours des briefs si le nom de fichier est malformé [MacroTools.java:108-110]
- [x] [Review][Patch] Crash potentiel lors du parcours des briefs si le nom de fichier est malformé [SentimentTools.java:405-407]
- [x] [Review][Patch] Lecture exhaustive et inefficace des fichiers briefs [MacroTools.java:82-114]
- [x] [Review][Patch] Itération non déterministe sur les années dans SeasonalityTools [SeasonalityTools.java:228]
- [x] [Review][Patch] Utilisation incohérente de Collectors.toList() et .toList() [MacroTools.java:90]
- [x] [Review][Patch] Absence d'annotations de paramètres @P pour documenter les outils LangChain4j [MacroTools.java:27-30]
- [x] [Review][Patch] Conditions de concurrence et instabilité (flakiness) dans les tests unitaires [ToolsTest.java]
- [x] [Review][Patch] Traces d'exceptions masquées/tronquées dans les logs d'avertissement [MacroTools.java:111]
- [x] [Review][Patch] Traces d'exceptions masquées/tronquées dans les logs d'avertissement [SentimentTools.java:112]
- [x] [Review][Patch] Instanciation redondante de SeasonalityAnalyzer [SeasonalityTools.java:210]
- [x] [Review][Patch] Absence de validation de nullité sur les paramètres d'outils (NullPointerException) [MacroTools.java:27-30]
- [x] [Review][Patch] Absence de validation de nullité sur les paramètres d'outils (NullPointerException) [SeasonalityTools.java:200-202]
- [x] [Review][Patch] Absence de validation de nullité sur les paramètres d'outils (NullPointerException) [SentimentTools.java:28-30]
- [x] [Review][Defer] Perte des métadonnées macroéconomiques previous et forecast pour les briefs historiques [MacroTools.java:118-126] — deferred, pre-existing

## Dev Notes


* **Conventions du projet :**
  * Pas de Lombok, pas de Spring.
  * Utilisation d'annotations `@Tool` de LangChain4j.
  * Masquage obligatoire des données futures (valeur `actual` de l'événement macro vide si postérieur au cutoff).
  * Les briefs historiques sont stockés dans `data/weekly-intel/brief-YYYY-MM-DD.json`.

### References

* **Spécifications PRD de l'Agentic Strategist (Outils Spec §3)** : [prd.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-agentic-strategist-2026-06-06/prd.md#3.-data-ingestion-&-tool-specifications-(@tool))
* **Spécifications des Epics** : [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#story-25.3-implementation-des-outils-d-ingestion-avec-isolation-temporelle)

## Dev Agent Record

### Agent Model Used

- Antigravity (Google DeepMind)

### Debug Log References

- Résolution des décalages d'indices de semaines ISO-8601 pour l'isolation temporelle dans `ToolsTest`. May 19, 2027 appartient à la semaine 20, tandis que May 26, 2027 cible correctement la semaine 21.

### Completion Notes List

- Exposition de `SeasonalityAnalyzer.loadBars` pour chargement des barres historiques hors-module.
- Création et implémentation de `MacroTools`, `SentimentTools` et `SeasonalityTools` avec isolation temporelle obligatoire (`cutoffTimestamp`).
- Création des tests de validation unitaires et d'isolation dans `ToolsTest.java` (génération à la volée des briefs et des barres historiques binaires de test).
- Compilation Maven globale et tests unitaires avec succès (`BUILD SUCCESS`).

### File List

- `trading-data/src/main/java/com/martinfou/trading/data/SeasonalityAnalyzer.java` (Modifié)
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/MacroTools.java` (Nouveau)
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SentimentTools.java` (Nouveau)
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/agent/tools/SeasonalityTools.java` (Nouveau)
- `trading-intelligence/src/test/java/com/martinfou/trading/intelligence/agent/tools/ToolsTest.java` (Nouveau)
