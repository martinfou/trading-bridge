---
stepsCompleted: [1, 2, 3, 4]
inputDocuments: []
session_topic: 'Activation et orchestration du pipeline de trading-intelligence pour planifier, coder et déployer des stratégies en paper trading'
session_goals: 'Comprendre et documenter comment activer le pipeline de trading-intelligence, identifier les prérequis et configurations nécessaires pour le paper trading, et s''assurer du bon fonctionnement de bout en bout'
selected_approach: 'ai-recommended'
techniques_used: ['First Principles Thinking', 'Reverse Brainstorming', 'Solution Matrix']
ideas_generated: 26
context_file: ''
session_active: false
workflow_completed: true
---

# Brainstorming Session Results

**Facilitator:** Martin Fournier
**Date:** 2026-06-14

## Session Overview

**Topic:** Activation et orchestration du pipeline de trading-intelligence pour planifier, coder et déployer des stratégies en paper trading
**Goals:** Comprendre et documenter comment activer le pipeline de trading-intelligence, identifier les prérequis et configurations nécessaires pour le paper trading, et s'assurer du bon fonctionnement de bout en bout

### Session Setup

Nous démarrons cette session pour répondre au besoin d'activer l'infrastructure existante de `trading-intelligence` et d'automatiser le cycle : Ingestion -> Planification (WeeklyPlanner) -> Génération de code (WeeklyStrategyCodeGenerator) -> Compilation (WeeklyCompileWatcher) -> Déploiement (WeeklyDeployWatcher) -> Paper trading.

## Technique Selection

**Approach:** AI-Recommended Techniques
**Analysis Context:** Activation de l'infrastructure de planification, génération de code et déploiement avec focus sur la robustesse et les étapes de déploiement en paper trading.

**Recommended Techniques:**
- **First Principles Thinking:** Pour décomposer le pipeline de trading-intelligence en composants élémentaires indépendants et recenser leurs entrées, configurations et sorties réelles.
- **Reverse Brainstorming:** Pour identifier de manière proactive tous les points de défaillance possibles du pipeline automatique afin de prévoir des mécanismes d'alerte et de garde-fou.
- **Solution Matrix:** Pour associer chaque étape clé à sa commande d'activation exacte, ses configurations (fichiers de propriétés, clés API) et ses indicateurs de succès.

**AI Rationale:** Cette séquence permet d'abord de clarifier le fonctionnement théorique et pratique du code existant, puis d'anticiper les pièges opérationnels d'une exécution autonome (cron/watcher), et enfin de produire un guide d'activation concret et actionnable.

---

## Idea Organization and Prioritization

### Thematic Organization

#### Thème 1 : Architecture du Pipeline et Ingestion résiliente
*   **[Pipeline #1] : Le Planificateur Hebdomadaire Automatique (`WeeklyPlanJobMain`)**
    *   *Concept* : Lancer `WeeklyPlanJobMain` via une tâche Cron système (ex. chaque dimanche à 20h00 UTC). Il ingère les actualités et le calendrier de la semaine à venir, produit le plan théorique de trading, et écrit le fichier Java.
    *   *Nouveauté* : La planification anticipe les événements majeurs avant l'ouverture des marchés.
*   **[Pipeline #2] : Les Veilleurs Démons (`WeeklyCompileWatcher` & `WeeklyDeployWatcher`)**
    *   *Concept* : Exécuter ces deux classes sous forme de démons système en tâche de fond. Dès qu'un nouveau fichier Java est généré, il est automatiquement compilé, validé (par le `CompileGate`), et envoyé au Control Plane sans aucune intervention manuelle.
    *   *Nouveauté* : Séparation complète de la logique de planification (qui peut prendre du temps avec le LLM) et de la logique de déploiement réseau à chaud.
*   **[Ingestion #10] : Le Générateur de Données Synthétiques de Secours (`SyntheticDataFallback`)**
    *   *Concept* : Si les API de Sentiment ou de News sont inaccessibles au moment du job de planification (ex. coupure réseau), le planificateur bascule sur un modèle saisonnier historique pour simuler des données de sentiment et générer une stratégie "défensive" par défaut.
    *   *Nouveauté* : Garantit que le pipeline ne plante jamais et produit toujours une stratégie valide, même en cas de coupure de nos sources tierces.
*   **[Défaillance #18] : Le Décalage Horaire Invisible (`TimezoneShiftSabotage`)**
    *   *Concept* : L'ingesteur interprète mal le fuseau horaire de la source ou ignore le changement d'heure d'été/d'hiver. La stratégie se déclenche 1 heure trop tôt ou trop tard, exécutant ses ordres dans le vide ou après le pic de volatilité.
    *   *Nouveauté* : Le code compile et tourne parfaitement, mais trade au mauvais moment à cause d'une dérive temporelle silencieuse.
*   **[Défaillance #19] : La Panne Silencieuse de Liste Vide (`EmptyCalendarSilentOutage`)**
    *   *Concept* : L'API du calendrier renvoie un code de succès (HTTP 200) mais avec une liste d'événements vide en raison d'un problème chez le fournisseur. Le pipeline continue sans lever d'erreur, planifiant une semaine "sans risques macro" et exposant le compte à des annonces majeures non surveillées.
    *   *Nouveauté* : L'absence de plantage technique qui masque un échec complet d'alimentation fonctionnelle.
*   **[Défaillance #20] : La Boucle de Contradiction du Planner (`PlannerContradictionLoop`)**
    *   *Concept* : Les actualités indiquent un biais haussier fort tandis que le sentiment de détail OANDA montre une opposition extrême. Le ReAct loop de `AgenticStrategistService` n'arrive pas à concilier ces données et tourne en boucle infinie en tentant d'analyser le problème, consommant tout le budget LLM jusqu'à l'erreur.
    *   *Nouveauté* : Blocage logique provoqué non par un bug de programmation, mais par l'incohérence des données de marché fournies au LLM.

#### Thème 2 : Validation et Qualité du code LLM (Compilation)
*   **[Défaillance #15] : La Pollution de Syntaxe Markdown (`MarkdownInclusionSabotage`)**
    *   *Concept* : Le LLM renvoie le code Java entouré de blocs de code markdown (comme ` ```java ... ``` `). Le générateur écrit ces caractères directement dans le fichier `.java`, ce qui rend le fichier impossible à compiler par Java.
    *   *Nouveauté* : Identifie un biais de formatage typique des LLM conversationnels qui écrivent directement sur le disque.
*   **[Défaillance #16] : L'Hallucination d'API du Core (`ApiHallucinationSabotage`)**
    *   *Concept* : Le LLM génère du code appelant des méthodes inexistantes sur nos classes de base (ex : `bar.getAverageSpread()`). Le compilateur Java échoue immédiatement car les signatures de méthodes ne correspondent à rien dans `trading-core`.
    *   *Nouveauté* : Met en évidence l'absence de synchronisation dynamique entre les types Java du projet et la mémoire contextuelle du LLM.
*   **[Défaillance #17] : La Logique Zombie Infinie (`InfiniteLoopFreeze`)**
    *   *Concept* : Le LLM introduit par erreur une boucle infinie ou récursive lors de l'évaluation des conditions d'un événement. Lors du déploiement, le thread sature le processeur (100% CPU), gelant instantanément le Control Plane.
    *   *Nouveauté* : Identifie une erreur de logique fatale, indétectable par la simple compilation de syntaxe.
*   **[GitOps #7] : Le Validateur de Pré-Commit local (`GitHookCompileCheck`)**
    *   *Concept* : Un hook Git de pré-commit intercepte la création de la branche par le planificateur et lance un `mvn compile` pour s'assurer de la validité du code produit.
    *   *Nouveauté* : Empêche toute pollution du dépôt avec du code invalide ou cassé.
*   **[GitOps #8] : Le Code Java Auto-Justifié (`SelfDocumentingLLMCode`)**
    *   *Concept* : Le code Java généré intègre des Javadoc extrêmement détaillées contenant le plan de trading, le score de sentiment COT de la semaine et des liens vers les articles de presse analysés.
    *   *Nouveauté* : Le code source conserve pour toujours la trace logique et historique de la décision de trading prise par l'IA au moment de sa création.

#### Thème 3 : Déploiement à chaud et Gouvernance GitOps
*   **[Gouvernance #3] : L'Approbation par Git Pull Request (`GitCommitPromoteGate`)**
    *   *Concept* : Le planificateur écrit le code de la stratégie et son plan au format Markdown, puis les pousse automatiquement sur une nouvelle branche Git. La revue et la fusion (merge) de cette Pull Request déclenchent le déploiement sur le Control Plane.
    *   *Nouveauté* : Utilisation de l'outil de gestion de version standard comme système de validation historique et d'audit.
*   **[Gouvernance #4] : L'Approbation interactive par ChatOps (Slack / Discord)**
    *   *Concept* : Une fois le code généré, le pipeline envoie un résumé du plan de la semaine sur Slack/Discord avec un bouton "Approuver" pour envoyer la requête HTTP d'activation au Control Plane.
    *   *Nouveauté* : Pilotage mobile et instantané sans avoir besoin d'ouvrir l'EDI ou le terminal.
*   **[Gouvernance #5] : Le Backtest Flash Pré-Déploiement (`PreDeployBacktestGate`)**
    *   *Concept* : Avant de demander l'approbation, le pipeline lance un backtest rapide de la stratégie générée sur les 3 dernières semaines de données fraîchement téléchargées.
    *   *Nouveauté* : Présentation des performances simulées réelles avant validation.
*   **[GitOps #6] : La Rétraction Automatique par Git Revert (`AutoGitRevert`)**
    *   *Concept* : Si la stratégie subit un incident (dépassement du drawdown max, exception), le Control Plane coupe les positions et envoie un commit de retrait (`git revert`) sur la branche principale.
    *   *Nouveauté* : L'état du dépôt reflète toujours exactement la réalité opérationnelle de ce qui tourne.
*   **[Défaillance #21] : L'Épuisement de la Mémoire par Hot-Deploy (`MetaspaceLeakSabotage`)**
    *   *Concept* : Chaque semaine, le déployeur injecte une nouvelle classe Java générée dans la JVM. Si l'ancien ClassLoader et les anciennes instances ne sont pas correctement libérés, la JVM subit une fuite Metaspace.
    *   *Nouveauté* : Résout les crashs silencieux à moyen terme causés par des déploiements dynamiques continus.
*   **[Défaillance #22] : La Position Orpheline par Coupure Réseau (`OrphanedTradeSabotage`)**
    *   *Concept* : L'ordre est exécuté chez le courtier mais la connexion coupe avant la réponse. La stratégie pense que l'ordre a échoué et ne place pas de Stop Loss, laissant un trade courir sans surveillance.
    *   *Nouveauté* : Une désynchronisation d'état critique où la stratégie perd le contrôle de ses positions actives.
*   **[Défaillance #23] : Le Conflit de Double Déploiement (`DuplicateStrategyConflict`)**
    *   *Concept* : Le déployeur pousse la stratégie de la semaine N+1 sans arrêter proprement celle de la semaine N, créant un conflit de marge et doublant les tailles de risques.
    *   *Nouveauté* : Gestion obligatoire du cycle de vie des instances actives dans le Runtime.

#### Thème 4 : Logique de Trading Événementielle (`CalendarPureEventStrategy`)
*   **[Architecture #9] : La Méta-Stratégie Régime-Dépendante (`RegimeShiftOrchestrator`)**
    *   *Concept* : Le générateur produit une classe composite qui contient plusieurs comportements (Tendance, Range, Volatilité) et bascule dynamiquement entre eux selon le régime détecté en temps réel par le Runtime.
    *   *Nouveauté* : Permet de survivre à un changement brutal de dynamique de marché sans intervention ni recompilation.
*   **[Architecture #11] : La Stratégie Événementielle Pure (`CalendarPureEventStrategy`)**
    *   *Concept* : Le code généré n'utilise aucun indicateur technique classique (pas de SMA, pas de RSI), mais base ses décisions uniquement sur l'heure système UTC et la distance par rapport aux événements du calendrier économique (ex. NFP, FOMC).
    *   *Nouveauté* : Évite le bruit des indicateurs classiques lors des annonces macroéconomiques majeures.
*   **[Exécution #12] : L'Ordre OCO Pré-Annonce (`PreEventStraddleOCO`)**
    *   *Concept* : Placement de deux ordres stop (un d'achat au-dessus, un de vente en-dessous) formant un "straddle" 2 minutes avant l'annonce. L'exécution de l'un annule l'autre.
    *   *Nouveauté* : Capture le mouvement violent sans prédire le résultat directionnel de la nouvelle.
*   **[Exécution #13] : Le Filtre d'Élargissement du Spread (`SpreadWideningFilter`)**
    *   *Concept* : Interdiction de soumettre tout ordre si le spread actuel fourni par le courtier dépasse un seuil maximal (ex. 5 pips).
    *   *Nouveauté* : Protection contre les coûts d'exécution astronomiques typiques des premières secondes d'une annonce majeure.
*   **[Exécution #14] : Le Fade de Volatilité Post-Annonce (`PostNewsMeanReversion`)**
    *   *Concept* : La stratégie attend 15 minutes après l'annonce que la volatilité se calme, puis cherche un signal d'épuisement du mouvement pour entrer à contre-courant.
    *   *Nouveauté* : Trade avec un spread stabilisé et un slippage quasi-nul sur des prix extrêmes sur-réagis.

---

### Prioritization Results

1.  **Top Prioritaires (Fort impact, aligné avec la demande) :**
    *   **Intégration Agentic avec Gemini/Antigravity (Custom Skill)** : Pour déclencher toute la chaîne de planification, compilation et validation directement depuis la boîte de chat de l'EDI.
    *   **Validation par CompileGate** : Évite absolument de casser les builds Maven à cause de défaillances syntaxiques du LLM (comme les backticks markdown).
    *   **Gouvernance par Git Pull Request** : Pour valider manuellement de manière propre le code généré avant le déploiement sur le Control Plane.
2.  **Quick Wins (Gains Rapides) :**
    *   Lancer manuellement les 3 jobs en ligne de commande depuis le terminal de l'EDI en utilisant les commandes Maven existantes.
    *   Configurer le fichier `.env` avec les clés API pour unifier le chargement des variables de planification.
3.  **Concept de Rupture (À long terme) :**
    *   Le concept de `CalendarPureEventStrategy` combiné à des ordres stop OCO et un filtre de spread temps réel pour trader les actualités de manière déterministe.

---

## Action Planning

### Plan d'Action 1 : Commande d'activation manuelle depuis l'EDI
*   **Pourquoi c'est important :** Permet d'exécuter l'ensemble de la chaîne de manière déterministe en une seule ligne de commande dans le terminal de votre EDI.
*   **Next Steps :**
    1.  Créer un script shell `scripts/generate-weekly-strategy.sh` à la racine qui exécute successivement `WeeklyPlanJobMain` puis `WeeklyCompileWatcherMain`.
    2.  Ajouter l'option `--deploy` pour exécuter `WeeklyDeployWatcherMain` afin d'envoyer la stratégie compilée directement au Control Plane actif en local.
    3.  Créer ou mettre à jour le fichier `.env` pour charger automatiquement la variable `DEEPSEEK_API_KEY` et la variable `CONTROL_PLANE_URL=http://localhost:8080`.
*   **Resources Needed :** Script shell et variables d'environnement centralisées.
*   **Timeline :** 1 heure de développement.
*   **Success Indicators :** L'exécution du script compile la stratégie et la charge dans le Control Plane sans plantage.

### Plan d'Action 2 : Intégration Agentic avec Gemini/Antigravity (Custom Skill)
*   **Pourquoi c'est important :** Permet de déléguer l'ensemble du processus à Gemini dans le chat. Vous dites simplement à l'agent *"Génère la stratégie de cette semaine"*, et il gère tout de A à Z.
*   **Next Steps :**
    1.  Créer le dossier `.agents/skills/generate-strategy/` et y insérer un fichier `SKILL.md` contenant les instructions système.
    2.  Documenter le workflow de l'agent : exécuter le job, extraire les points clés du plan Markdown pour les montrer à l'utilisateur, valider la compilation, et réparer de manière autonome les éventuels défauts du code généré.
*   **Resources Needed :** Structure de compétence de l'agent (Gemini Agent SDK compatible).
*   **Timeline :** 2 heures.
*   **Success Indicators :** L'assistant est capable de lancer la génération, de corriger lui-même les erreurs de code et de pousser le déploiement sur confirmation de l'utilisateur.

---

## Session Summary and Insights

### Key Achievements
- Nous avons cartographié les 3 composants clés existants du module `trading-intelligence` (`WeeklyPlanJobMain`, `WeeklyCompileWatcherMain`, `WeeklyDeployWatcherMain`).
- Nous avons identifié 9 vulnérabilités opérationnelles majeures et conçu des parades (comme le `CompileGate` pour nettoyer le code Java généré par le LLM).
- Nous avons cadré la transition vers un déclenchement manuel et assisté par agent (Gemini) à la demande directement depuis l'EDI.

### Session Reflections
Cette session a permis de passer d'un modèle abstrait de démons automatiques à un workflow centré sur le développeur (Git PR, scripts d'EDI et compétences d'agent). C'est beaucoup plus pratique et sécurisé pour démarrer en paper trading.
