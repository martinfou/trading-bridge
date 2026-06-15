# Decision Log — Unified Strategy Engine PRD

## DC-01 : Nouveau module `trading-intelligence` vs ajout à `trading-core`

**Date :** 2026-06-14
**Décision :** Créer un nouveau module `trading-intelligence`
**Raison :** Préserve le graphe acyclique des dépendances. `trading-core` ne doit pas dépendre de LangChain4j. Le nouveau module isole les dépendances LLM.
**Alternatives :** Ajouter à `trading-core` — rejeté car `trading-core` serait pollué par des dépendances LLM.
**Conséquence :** `StrategySpec`, `StrategyProfile`, `StrategyMetadata` sont dans `trading-core` (les DTOs partagés). La logique LLM est dans `trading-intelligence`.

---

## DC-02 : LangChain4j vs Spring AI

**Date :** 2026-06-14
**Décision :** LangChain4j
**Raison :** Pas de Spring dans le projet, LangChain4j pèse ~2MB vs ~15MB pour Spring AI. Structured output natif via `JsonSchema`. Tool calling natif.
**Piste d'implémentation :** Préférer `langchain4j-open-ai` plutôt que `langchain4j-deepseek` car DeepSeek API est compatible OpenAI. Même module, URL configurable.

---

## DC-03 : Template Java (codegen) vs LLM génère la classe complète

**Date :** 2026-06-14
**Décision :** Template Java avec substitution de paramètres
**Raison :** Garantit que le code est toujours compilable. Le LLM décide les paramètres, le pattern (EntryCondition, ExitCondition), mais pas la syntaxe Java.
**Risque :** Template trop rigide limite la créativité. Mitigation : si 3 échecs avec template, laisser le LLM générer une classe libre.
**Ouvert :** À confirmer en Phase 2.

---

## DC-04 : Stockage des leçons en JSON vs SQLite

**Date :** 2026-06-14
**Décision :** Fichier JSON flat dans `data/experience-store/`
**Raison :** Simple, versionnable (git), lisible, pas de dépendance supplémentaire.
**Conséquence :** Chaque run append une entrée. Rotation hebdomadaire (garder 100 entrées max).

---

## DC-06 : Docker vs Local Maven Compilation

**Date :** 2026-06-14
**Décision :** Compilation locale Maven (pas Docker)
**Raison :** La machine hôte a Java 26.0.1 et Maven 3.9.16 via mise, qui compilent le projet (target 21) sans problème. Docker ajoute un overhead de 30-60s par compile (startup conteneur + resolution dépendances + compile full). La compilation locale est incrémentale (~5-10s) et plus simple.
**Commande :** `mvn compile -pl trading-strategies -am -q` (après avoir mis `JAVA_HOME` et `PATH` sur les binaires mise)
**Pipeline impact :** Une itération complète (compile + backtest 4 pairs) passe de ~60-90s à ~15-20s
**Note :** Docker reste disponible comme fallback si jamais le JDK local pose problème.

**Date :** 2026-06-14
**Décision :** DeepSeek d'abord, Claude en option
**Raison :** Déjà intégré (HttpDeepSeekClient), coût négligeable. Si les résultats sont décevants sur la créativité, passer à Claude.
**Ouvert :** À mesurer après 5 runs.
