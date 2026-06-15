# Epic 28 — Unified Strategy Engine

**Objectif :** Pipeline LLM (LangChain4j) de génération autonome de stratégies de trading, avec feedback loop, catalog queryable, et profils de validation paramétrables — le tout dans `trading-intelligence` existant.

**Statut :** `planned` — Phase 3 (Solutioning) approuvée 2026-06-14

---

## Quick Start — MVP 1 Semaine

> **Si tu veux lancer le plus vite possible :** Phase 4.1 uniquement.
> Résultat en 5 jours : CLI qui génère → compile → backtest → valide → rapporte.

```bash
# Jour 5 — Usage final
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0.1
export PATH="$JAVA_HOME/bin:..."
cd ~/projects/trading-bridge
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunStrategyPipeline"
# → Génère 3 concepts LT, backtest 4 pairs, rapporte les qualifiés
```

---

## User Stories (valeur métier)

| # | As a... | I want to... | So that... | Priorité | Phase |
|---|---------|-------------|------------|:--------:|:-----:|
| US-1 | Trader | Lancer la génération autonome de stratégies LT via CLI | J'obtiens N concepts testés sans intervention manuelle | **P0** | 4.1 |
| US-2 | Trader | Voir les résultats de chaque itération (concept → backtest → verdict) | Je comprends ce qui a été testé et pourquoi | **P0** | 4.1 |
| US-3 | Trader | Que les échecs soient loggés et non répétés automatiquement | Le LLM apprend de ses erreurs dans la même session | **P0** | 4.2 |
| US-4 | Développeur | Configurer le profil de validation (LT strict / Prop scoring / News week) | Le même moteur sert les 3 workflows | P1 | 4.3 |
| US-5 | Trader | Chercher dans le catalog par PF, Sharpe, drawdown, catégorie | Je retrouve une stratégie sans fouiller git | P1 | 4.4 |
| US-6 | Trader | Que les leçons persistent entre les runs | Le LLM ne répète pas les erreurs de la semaine dernière | P1 | 4.2 |
| US-7 | Trader | Relancer la génération prop shop via le même moteur | Un seul cron remplace les 3 existants | P2 | 4.3 |
| US-8 | Développeur | Ajouter un nouveau type de stratégie sans toucher au pipeline | Le moteur est extensible par configuration | P2 | 4.4 |

---

## Coding Stories

### Phase 4.1 — Foundation (MVP, ~5 jours)

**Objectif :** Pipeline LT fonctionnel : LLM → Template → Compile → Backtest → Résultat

| # | Story | Effort | Description | US | Dépendances |
|---|-------|:------:|-------------|:--:|:-----------:|
| 28-1 | **Bootstrap pipeline orchestrator** | **S** | Créer `LtPipelineOrchestrator` qui enchaîne les phases : génération → codegen → compile → backtest → évaluation. Wrapper autour des composants existants de `trading-intelligence`. | US-1 | — |
| 28-2 | **Bootstrap mise dans le pipeline** | **XS** | Script qui exporte `JAVA_HOME` et `PATH` vers les binaires mise. À utiliser dans le CLI runner et les crons. | US-1 | — |
| 28-3 | **StrategySpec + StrategyMetadata (trading-core)** | **S** | Records `StrategySpec`, `StrategyMetadata`, `PairResult`, `EntryCondition`, `ExitCondition`. Modèle extensible avec `Map<String, Object> params`. | US-1 | — |
| 28-4 | **LLM Strategy Generator** | **M** | `LtStrategyGenerator` : appelle DeepSeek via `AgenticModelFactory`, parse la réponse JSON en `StrategySpec`. Prompt système charge le playbook (sections 1-9) + leçons + catalog. 3 retries max. | US-1, US-2 | 28-3 |
| 28-5 | **Template Code Generator (LT)** | **M** | `LtTemplateCodeGenerator` : étend `TemplateRegistry` existant pour les stratégies LT. Génère un `.java` compilable dans `trading-strategies/longterm/`. Template avec substitution de paramètres (SMA, ATR, RSI, SL/TP). | US-1 | 28-3 |
| 28-6 | **ValidationProfile interface + LongTermValidator** | **S** | Interface `ValidationProfile` dans `trading-core`. Implémentation `LongTermValidator` : walk-forward IS/OOS1/OOS2, PF ≥ 1.05 / 1.0 / 1.0, DD < 20%, trades ≥ 100. Message `whyRejected()` clair pour le feedback LLM. | US-1 | 28-3 |
| 28-7 | **CLI Runner (RunStrategyPipeline)** | **M** | `RunStrategyPipeline` dans `trading-examples`. Usage : `mvn exec:java ... RunStrategyPipeline`. Génère 3 concepts, backtest 4 pairs × 4 périodes, rapport structuré (concept, PF, Sharpe, DD, verdict). Paramètres : `--profile LONG_TERM`, `--iterations 5`. | US-1, US-2 | 28-1→28-6 |

**Effort Phase 4.1 :** 3S + 2M + 1XS ≈ **~5 jours**

### Phase 4.2 — Feedback Loop (~3 jours)

**Objectif :** Les échecs sont loggés, le LLM apprend, les leçons persistent.

| # | Story | Effort | Description | US | Dépendances |
|---|-------|:------:|-------------|:--:|:-----------:|
| 28-8 | **Experience Store (JSONL)** | **S** | `ExperienceStore` : append-only JSONL dans `data/experience-store/`. Write : `(StrategySpec, BacktestResult, failureReason, lessons)`. Read : dernières N entrées + synthèses. Gestion de rotation (max 100 fichiers). | US-3, US-6 | 28-3 |
| 28-9 | **Feedback loop (max 5 itérations)** | **S** | Dans `LtPipelineOrchestrator` : après chaque échec, construire un message de feedback structuré (spec testé + résultats + analyse) et le renvoyer au LLM avant la prochaine itération. Max 5 itérations. | US-3 | 28-1, 28-8 |
| 28-10 | **Synthèse périodique des leçons** | **XS** | Quand l'Experience Store atteint 50 entrées, un job LLM résume les 50 → 1 entrée synthétique. Garde les 10 dernières entrées brutes + toutes les synthèses. | US-6 | 28-8 |

**Effort Phase 4.2 :** 2S + 1XS ≈ **~3 jours**

### Phase 4.3 — Multi-Profil (~3 jours)

**Objectif :** Le même moteur sert LT, Prop Shop et News Weekly.

| # | Story | Effort | Description | US | Dépendances |
|---|-------|:------:|-------------|:--:|:-----------:|
| 28-11 | **PropShopValidator** | **M** | `PropShopValidator` implémente `ValidationProfile`. Scoring 15pts : news consensus, COT alignment, seasonality, volatilité, conflit OANDA, soft signals, HMM. Paires : 9, période 2019-2025. | US-4 | 28-6 |
| 28-12 | **NewsWeeklyValidator** | **S** | `NewsWeeklyValidator` : validation par événement calendrier (pas de backtest). Vérifie que l'événement existe, que le SL est assez large, que la paire est cohérente. | US-4 | 28-6 |
| 28-13 | **Cron unifié** | **S** | Un cron job Hermes qui lance `RunStrategyPipeline` avec le profil approprié (LT le weekend, Prop Shop le dimanche, News Weekly en semaine). Remplace les 3 crons existants. | US-7 | 28-7 |

**Effort Phase 4.3 :** 1M + 2S ≈ **~3 jours**

### Phase 4.4 — Catalog & Polish (~3 jours)

**Objectif :** Visibilité et extensibilité.

| # | Story | Effort | Description | US | Dépendances |
|---|-------|:------:|-------------|:--:|:-----------:|
| 28-14 | **Enrichir StrategyCatalog** | **S** | Ajouter `StrategyMetadata` à chaque stratégie dans `StrategyCatalog`. Query par profil, catégorie, PF, Sharpe, DD. CLI `--list --filter pf>1.5` ou `--filter profile=LONG_TERM`. | US-5 | 28-3 |
| 28-15 | **Unifier les clients LLM** | **M** | Déprécier `HttpDeepSeekClient`. Migrer tous les appels LLM vers `AgenticModelFactory` (qui supporte DeepSeek et Ollama). Mêmes timeouts, mêmes retries. | US-8 | — |
| 28-16 | **Rapport structuré amélioré** | **S** | Markdown report : résumé exécutif → détails par itération → tableau comparatif → verdict. Sauvegarde automatique dans `_bmad-output/implementation-artifacts/`. Option `--joplin` pour sauvegarder dans Joplin. | US-2 | 28-7 |
| 28-17 | **Doc : mise à jour du playbook** | **S** | Mettre à jour `docs/lt-strategy-playbook.md` Section 10 pour refléter l'implémentation réelle. Ajouter le diagramme d'architecture final et les commandes CLI. | — | 28-7 |

**Effort Phase 4.4 :** 1M + 3S ≈ **~3 jours**

---

## Résumé

| Phase | Effort | Stories | Valeur livrée |
|-------|:------:|:-------:|---------------|
| **4.1 — Foundation** | **~5 jours** | 7 | CLI qui génère → compile → backtest → valide → rapporte |
| **4.2 — Feedback Loop** | **~3 jours** | 3 | Le LLM apprend de ses erreurs, les leçons persistent |
| **4.3 — Multi-Profil** | **~3 jours** | 3 | Prop Shop + News Weekly via le même moteur |
| **4.4 — Catalog & Polish** | **~3 jours** | 4 | Query, rapports, doc |
| **Total Epic 28** | **~14 jours** | **17** | |

---

## Build Order

| Ordre | Phase | Dépend de |
|:-----:|-------|:---------:|
| 1 | **4.1 Foundation** | Rien — démarrage immédiat |
| 2 | **4.2 Feedback Loop** | 4.1 |
| 3 | **4.3 Multi-Profil** | 4.1 (parallélisable avec 4.2) |
| 4 | **4.4 Catalog & Polish** | 4.1, 4.3 |

**Note :** 4.1 et 4.2 sont la priorité (US-1, US-2, US-3 = P0). 4.3 et 4.4 peuvent être dépriorisées si le temps manque.

---

## Risques & Mitigations

| Risque | Probabilité | Impact | Mitigation |
|--------|:----------:|:------:|------------|
| LLM produit un JSON StrategySpec invalide | Haute | Faible | 3 retries, parse validation avant tout traitement |
| Temps de compilation Maven plus long que prévu | Basse | Faible | Mode incrémental déjà actif. Docker en fallback. |
| Backtest 4 pairs × 4 périodes trop long (40-60s) | Moyenne | Moyen | Parallélisation par paire (4 threads). Si trop lent, réduire à 2 pairs. |
| DeepSeek API down pendant le cron | Basse | Moyen | Fallback automatique vers Ollama local (déjà dans AgenticModelFactory). |
| Le LLM génère toujours le même concept | Moyenne | Faible | Injection du catalog complet + instruction de diversité. Si persiste, forcer une catégorie aléatoire. |
| `trading-intelligence` déjà existant crée des conflits | Haute | Moyen | Architecture brownfield — ajouter, pas réécrire. Tests d'intégration avant/après. |
