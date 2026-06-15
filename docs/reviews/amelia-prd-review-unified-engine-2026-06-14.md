# Review Implémenteuse — PRD Unified Strategy Engine

**Reviewer :** 💻 Amelia (Senior Java/Spring — Trading Bridge)
**Date :** 2026-06-14
**PRD :** `_bmad-output/planning-artifacts/prds/prd-unified-strategy-engine-2026-06-14/prd.md`

---

## TL;DR

Le PRD est bien écrit mais **trop optimiste sur 4 points** : (1) le module existe déjà à moitié, (2) Docker compile n'existe pas, (3) le template Java actuel est trop limité pour du vrai codegen, (4) le phasage 5 semaines est irréaliste — compter plutôt **7-8 semaines**.

---

## 1. Ce qui existe déjà (et que le PRD rate)

| PRD dit | Réalité |
|---------|---------|
| "Nouveau module" `trading-intelligence` | ✅ **Existe déjà** — pom.xml, LLM clients, CompileGate, codegen, template registry, etc. |
| `langchain4j-core:1.0.0-beta2` à ajouter | ❌ **BOM déjà à 0.33.0** dans le parent. `1.0.0-beta2` n'existe peut-être même pas. |
| Template Java à créer | ✅ **Déjà fait** — `WeeklyStrategyCodeGenerator` + `TemplateRegistry` (T1-T8). |
| Pipeline compile | ✅ **CompileGate existe** — mais tourne en local, pas Docker. |
| Walk-forward | ✅ **WalkForwardOptimizer existe** — mais API `BiFunction<List<Bar>, List<Bar>, BacktestResult>` pas prête pour pipeline automatisé. |

### Ce qui manque vraiment

| Élément | Statut | Effort estimé |
|---------|--------|:--------:|
| `StrategyProfile` enum | ❌ Rien | 1h |
| `StrategyMetadata` record | ❌ Rien | 1h |
| `StrategySpec` (template data model) | ❌ Rien (le PRD propose un modèle trop rigide) | 4h |
| StrategyCatalog unifié | ❌ Seul `LlmWeeklyStrategyCatalog` existe (weekly only) | 3 jours |
| Pipeline orchestrateur | ❌ Weekly pipeline = watchers hot-folder, pas un orchestrateur unifié | 5 jours |
| Docker compile | ❌ Pas de Dockerfile, juste un `docker run` ad-hoc | 3 jours |
| Feedback loop / experience-store | ❌ Rien | 3 jours |
| RAG injection dans contexte LLM | ❌ Rien | 2 jours |

---

## 2. Problèmes Majeurs

### 2.1 Docker Compile N'EXISTE PAS

Le PRD dit "Docker Maven compile : ✅ Existant". **Faux.** Le `scripts/run-connectors.sh` fait un `docker run --rm -v ... maven:3-eclipse-temurin-21` ad-hoc, mais :

- Pas de Dockerfile dans le projet
- `CompileGate.java` lance `mvn compile -pl trading-strategies -am -q` en **local**, pas dans Docker
- Aucune image n'est buildée, taggée, versionnée
- Les dépendances Maven sont téléchargées à chaque run (10-30s à froid)

**Ce qu'il faut :**
1. Dockerfile multi-stage : `maven:3-eclipse-temurin-21` → compile → JRE runtime
2. `.dockerignore` pour exclure `target/`, `.git/`, `data/`
3. Volume mount pour les données historiques (4-6 Go)
4. Script `docker-build.sh` qui build + run en une commande

**Effort : 2-3 jours** — pas 0.

### 2.2 LangChain4j Version Fantôme

Le PRD spécifie `langchain4j-core:1.0.0-beta2`. Le pom.xml parent a déjà `langchain4j.version=0.33.0` avec BOM. **`1.0.0-beta2` n'est probablement pas une version réelle** — LangChain4j utilise `0.x.y` et `0.36+` est la dernière stable.

**Problème connexe :** On a DEUX clients LLM différents :
- `HttpDeepSeekClient` (HTTP raw) — utilisé par le weekly pipeline existant
- `AgenticModelFactory` (LangChain4j `OpenAiChatModel`) — créé pour le pipeline agentic

Le PRD propose de passer à LangChain4j pour tout. Ça veut dire **réécrire `HttpDeepSeekClient` → LangChain4j `OpenAiChatModel`**, ou garder les deux (lequel pilote ?). C'est un temps caché.

### 2.3 StrategySpec PRD Trop Rigide

Le `StrategySpec` du PRD a des champs hardcodés :
```java
int fastPeriod, int slowPeriod, int rsiPeriod, int atrPeriod, double entryThreshold
```

C'est prisonnier du pattern "2 EMA croisement + RSI + ATR". **Aucune flexibilité** pour :
- Unbreakout de volatilité (pas de fastPeriod/slowPeriod)
- Un mean reversion (besoin de z-score, pas de EMA cross)
- Un strategy basée sur calendrier économique

**Alternative :** Utiliser `Map<String, Object> params` comme le fait déjà `WeeklyStrategyCodeGenerator.generateThinProp()`. Le LLM décide quels paramètres sont pertinents, le template les utilise via substitution.

### 2.4 CompileGate Est Bloquant pour le Pipeline Automatisé

Le `CompileGate` actuel est un `ProcessBuilder` synchrone avec timeout 10 min. Pour un pipeline automatisé :

- **Problème réentrance** : Si deux runs lancent `mvn compile` en même temps → race condition sur les fichiers générés dans `trading-strategies/src/main/java/...`
- **Problème isolation** : Le generateur écrit direct dans le répertoire source du module. En Docker c'est OK (container éphémère), en local c'est risky
- **Problème nettoyage** : `clearGeneratedDir()` supprime les `.java` du dossier generated. Si un run échoue au milieu, les fichiers sont perdus

---

## 3. Phasage Réaliste (vs PRD)

### Phase 1 — Foundation (PRD: 2 semaines | Réel: 3 semaines)

La PRD dit 2 semaines. Problèmes :
- Dockerfile + `.dockerignore` + script de build = 2-3 jours
- `StrategySpec` avec le bon niveau de flexibilité = 2 jours de design + implé
- Refacto du codegen pour produire des stratégies complètes (pas juste des wrappers) = 3-4 jours
- Unification des deux clients LLM = 1-2 jours
- Tests d'intégration du pipeline complet = 2 jours

**→ 3 semaines serrées.**

### Phase 2 — Feedback Loop (PRD: 1 semaine | Réel: 1.5-2 semaines)

- Service `ExperienceStoreService` avec JSON IO = 1 jour
- Format "Lesson" avec metadata de résultat = 1 jour
- Boucle d'itération 5x avec fallback = 2-3 jours
- Injection RAG dans le prompt LLM = 1-2 jours
- Tests de non-régression sur les stratégies existantes = 1 jour

**Problème principal :** Comment on invalide le cache de contexte ? Si on injecte 5 leçons à chaque run, le prompt grossit. Il faut une stratégie de sliding window (garder les N dernières leçons pertinentes par profil). Pas documenté dans le PRD.

**→ 2 semaines.**

### Phase 3 — Multi-Profil (PRD: 1 semaine | Réel: 2 semaines)

- Profil PROP_SHOP = scoring 15pts, soft signals, HMM — tout ça existe mais pas dans une pipeline automatisé
- Profil NEWS_WEEKLY = événement calendrier — le IngestPipeline existe déjà
- Le vrai boulot c'est de mapper chaque profil à un ensemble de seuils et de gates
- StrategyCatalog enrichi avec métadonnées + requêtes = 2-3 jours

**Piège :** Le PRD met PROP_SHOP et NEWS_WEEKLY dans la même semaine. Ce sont deux systèmes différents avec des critères de validation complètements distincts. En pratique il faut traiter chaque profil séparément et c'est 1 semaine par profil.

**→ 2 semaines (PROP_SHOP en priorité, NEWS_WEEKLY allégé).**

### Phase 4 — Production (PRD: 1 semaine | Réel: 1 semaine)

C'est la partie la plus réaliste. Remplacer les crons existants, brancher le dashboard, documenter.

**Mais attention :** Si on garde les crons existants en parallèle pendant la transition, il faut gérer la période de double-écriture. Pas documenté.

**→ 1 semaine.**

### Tableau Récap

| Phase | PRD | Réel | Delta |
|-------|:---:|:----:|:-----:|
| 1 — Foundation | 2 sem | **3 sem** | +50% |
| 2 — Feedback Loop | 1 sem | **2 sem** | +100% |
| 3 — Multi-Profil | 1 sem | **2 sem** | +100% |
| 4 — Production | 1 sem | **1 sem** | +0% |
| **Total** | **5 sem** | **8 sem** | **+60%** |

---

## 4. Pièges Détaillés

### 4.1 Le Template Java est un Wrapper, Pas un Vrai Template

Le `WeeklyStrategyCodeGenerator` actuel génère des classes qui héritent de `AbstractPropStrategy` ou déléguent à des stratégies existantes. C'est un **wrapper pattern**, pas un vrai template de stratégie.

Pour un Unified Strategy Engine, il faut un vrai template avec :
- Logique d'entrée paramétrable (le LLM choisit les conditions)
- Gestion des indicateurs configurables
- Exit rules paramétrables

Le PRD ne spécifie pas comment le LLM contrôle la logique — est-ce que le template a des "slots" de code ? Ou est-ce que le LLM choisit parmi une liste de patterns prédéfinis ?

**Si c'est des slots de code, le LLM génère du code → problématique de sécurité** (injection, code invalide, boucles infinies).
**Si c'est des patterns prédéfinis, l'espace de conception est limité** par les patterns supportés.

Recommandation : Commencer par des patterns prédéfinis (comme maintenant) et ajouter les slots si les résultats sont trop limités.

### 4.2 Walk-Forward = 16 Runs = 8 Minutes Minimum

Le PRD dit 4 paires × 4 périodes = 16 runs walk-forward. Chaque run BacktestEngine sur H1 2006-2026 (200K+ bars) prend **15-30 secondes** sur une machine normale.

**Estimation basse :** 16 × 15s = 4 min.
**Estimation réaliste :** 16 × 25s = 6-7 min + compilation (1-2 min Docker) = **8-9 min**.

L'objectif "< 10 min" est atteignable MAIS :
- Pas de marge pour les retries (si erreur → timeout)
- Pas de parallélisation (le PRD mentionne 4 threads mais rien n'est implémenté)
- Pas de cache (si la même paire/période est backtestée deux fois, on refait tout)

### 4.3 Experience-Store : Simple JSON ≠ Simple

Le PRD dit "Fichier JSON flat dans data/experience-store/ — Simple, versionnable, lisible". C'est vrai pour le stockage, mais :

- **Lecture RAG** : Il faut charger, filtrer par profil, trier par pertinence, et injecter dans le prompt. Pas documenté.
- **Déduplication** : Si deux runs génèrent la même leçon, on a des doublons dans le contexte → gaspillage de tokens.
- **Versioning des leçons** : Une leçon apprise peut être invalidée par un run suivant. Le format JSON plat ne gère pas ça.
- **Pas de sliding window** : À 10 runs/semaine × 4 semaines = 40 leçons. À 2K tokens chaque = 80K tokens de contexte à chaque run. Le PRD est optimiste sur le coût.

### 4.4 Backward Compatibility avec les Stratégies Existantes

10 stratégies LT, les stratégies prop shop, les stratégies news weekly existent déjà dans `trading-strategies/`. Le nouveau pipeline va générer des classes dans `trading-strategies/src/main/java/.../llmweekly/generated/`.

**⚠️ Risque :** `CompileGate` compile `trading-strategies` en entier. Si une stratégie existante a une erreur de compilation (ça arrive avec des stratégies générées), le pipeline LLM échoue pour une raison non liée à la nouvelle stratégie.

**Mitigation :** Isoler les stratégies générées dans un module séparé ou une source root avec compilation séparée.

### 4.5 Maven Wrapper Non Utilisé par CompileGate

`CompileGate` lance `mvn` (pas `./mvnw`). Le projet a un `mvnw` (Maven wrapper) à la racine. Si Maven 4 n'est pas installé sur la machine (et que le Docker compile est la solution prévue), le `CompileGate` local ne marchera pas.

En pratique, le projet utilise `mvn` via `$PATH`. Si on Dockerise, le Dockerfile utilise `maven:3-eclipse-temurin-21` avec Maven 3, pas Maven 4. Le wrapper (`mvnw`) est Maven 4. **Incompatibilité potentielle.**

---

## 5. Dépendances Maven Manquantes

| Dépendance | Pourquoi | Version recommandée |
|------------|----------|:-------------------:|
| `langchain4j-core` | Déjà dans BOM 0.33.0 | Utiliser le BOM existant — PAS 1.0.0-beta2 |
| `langchain4j-open-ai` | Client OpenAI-compatible (DeepSeek) | Déjà dans pom.xml de trading-intelligence ✅ |
| `langchain4j-ollama` | Fallback local | Déjà présent ✅ |
| `org.slf4j:slf4j-api` | Logging | Déjà présent ✅ |

**Rien à ajouter** du côté LangChain4j — tout est déjà dans la BOM et le module a déjà les dépendances. Le PRD est en retard d'une itération.

---

## 6. Recommandations

1. **Recycler le code existant** — WeeklyStrategyCodeGenerator, TemplateRegistry, CompileGate, WalkForwardOptimizer. Le PRD sous-estime ce qui est déjà fait.

2. **Priorité Docker** — C'est le plus gros trou dans le PRD. Sans Docker compile, le pipeline automatisé n'est pas isolé.

3. **StrategySpec Map-based** — Ne pas hardcoder les paramètres. Pattern `Map<String, Object>` comme le codegen actuel.

4. **Pipeline séquentiel d'abord** — Paralléliser les backtests plus tard. Le gain est modeste (8 min → 3 min) pour une complexité élevée (gestion des threads, synchronisation des résultats).

5. **Planifier 8 semaines** — Le PRD a 10 semaines de marge dans l'epic si on regarde le sprint backlog (pas commencé). Utiliser cette marge plutôt que d'optimiser le phasage.

6. **Déduplication des clients LLM** — Supprimer `HttpDeepSeekClient` une fois que `AgenticModelFactory` couvre tous les cas d'usage. Garder `StubLlmClient` pour les tests.

7. **Experience-store minimal v1** — Commencer par un simple append-only JSON. Pas de sliding window sophistiqué. Améliorer en v2.
