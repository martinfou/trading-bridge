---
title: Epic 24 — Walk-Forward Analysis (WFA) and Optimization
status: final
created: 2026-06-15
updated: 2026-06-15
---

# PRD: Epic 24 — Walk-Forward Analysis (WFA) and Optimization

## 0. Document Purpose
Ce document définit les exigences fonctionnelles et l'implémentation de l'**Epic 24 (Walk-Forward Analysis & Optimization)** dans Trading Bridge. Il s'adresse aux développeurs pour le codage du moteur Java et de l'IHM desktop (Electron/Vue 3), ainsi qu'aux tests d'intégration. Ce PRD s'appuie sur la structure existante du `BacktestEngine` et du catalogue de stratégies pour y ajouter des capacités d'optimisation glissante et de suivi de fraîcheur.

## 1. Vision
Martin veut s'assurer que ses stratégies de trading ne sont pas sur-optimisées (overfitted) sur l'historique et restent robustes dans le temps. L'analyse Walk-Forward (WFA) résout ce problème en simulant des cycles d'optimisation (In-Sample) et de validation (Out-of-Sample) successifs sur des fenêtres glissantes de données. De plus, pour éviter la dérive de performance (drift) en production, le système doit l'alerter dès qu'une stratégie active a besoin d'être recalibrée selon des critères propres à chaque stratégie.

## 2. Target User

### 2.1 Primary Persona
* **Martin** : Trader quantitatif indépendant qui conçoit et déploie des stratégies automatiques. Il utilise le moteur pour valider la robustesse de ses modèles avant de les promouvoir en démo ou en réel, et surveille son parc de stratégies actives depuis son tableau de bord.

### 2.2 Jobs To Be Done
* **Valider la robustesse** : S'assurer qu'une stratégie produit des gains réguliers sur des données qu'elle n'a pas vues durant sa phase d'optimisation.
* **Éviter le biais de look-ahead** : Purger les transactions qui chevauchent les limites de fenêtres pour éviter que les résultats OOS soient artificiellement embellis par des informations futures.
* **Surveiller la fraîcheur** : Savoir en un coup d'œil quelles stratégies en production nécessitent une recalibration immédiate.

### 2.3 Key User Journeys

* **UJ-1. Martin configure et lance un WFA depuis l'IHM Desktop**
  * **Contexte** : Martin a sélectionné la stratégie `SmaCrossover` et souhaite tester sa robustesse sur EUR_USD.
  * **Entrée** : Sur l'écran de backtest, Martin sélectionne le mode "Walk-Forward".
  * **Parcours** : 
    1. Martin définit la taille de la fenêtre In-Sample (ex: 12 mois) et Out-of-Sample (ex: 3 mois).
    2. Il sélectionne 2 paramètres à optimiser (ex: période SMA rapide [10-30, pas de 5], période SMA lente [50-100, pas de 10]).
    3. Il clique sur "Démarrer".
  * **Climax** : L'IHM affiche une frise chronologique interactive montrant chaque pli (fold) IS/OOS avec son résultat.
  * **Résolution** : Martin télécharge le rapport consolidé et décide si la stratégie passe la validation.

* **UJ-2. Le système alerte Martin qu'une stratégie doit être recalibrée**
  * **Contexte** : Une stratégie tourne sur un compte paper depuis 35 jours.
  * **Entrée** : Martin ouvre son tableau de bord principal.
  * **Parcours** : 
    1. Martin voit un badge orange 🔔 à côté de la stratégie active.
    2. En survolant l'icône, une info-bulle indique : "Recalibration requise : 35 jours écoulés (limite : 30 jours)".
  * **Climax** : Martin clique sur l'alerte pour ouvrir directement l'écran de configuration du Walk-Forward pré-rempli.
  * **Résolution** : Martin lance le WFA pour mettre à jour les paramètres de la stratégie.

## 3. Glossary
* **In-Sample (IS)** : Segment de données historiques utilisé pour entraîner ou optimiser les paramètres d'une stratégie.
* **Out-of-Sample (OOS)** : Segment de données historiques (consécutif à l'IS) sur lequel les paramètres optimisés sur l'IS sont testés sans modification.
* **Pli (Fold / Window)** : Unité temporelle associant une période IS et sa période OOS associée.
* **Purge des frontières (Boundary Purging)** : Processus consistant à supprimer les ordres/trades qui s'exécutent ou chevauchent la limite temporelle entre la fin de l'IS et le début de l'OOS pour éviter les fuites d'informations (data leakage).
* **Efficacité Walk-Forward (WFE)** : Ratio mesurant le maintien de la performance, calculé par :
  $$\text{WFE} = \frac{\text{Sharpe OOS}}{\text{Sharpe IS}}$$
* **Fraîcheur de Calibration** : Statut calculé d'une stratégie active indiquant si elle doit être ré-optimisée en fonction du temps, du nombre de bars ou de transactions depuis son dernier WFA.

## 4. Features

### 4.1 Moteur IS/OOS et Optimiseur Grid Search
**Description** : Implémentation du moteur de découpe chronologique et de l'algorithme d'optimisation par grille. Pour garantir la répétabilité des résultats, l'optimisation doit être déterministe. [ASSUMPTION: Nous n'utiliserons pas le moteur génétique pour le WFA en v1, mais une implémentation Grid Search séquentielle et déterministe].

**Exigences fonctionnelles** :

#### FR-1: Découpage chronologique IS/OOS
Le système doit diviser la plage de données historiques globale en $N$ plis glissants selon la configuration fournie (ex: taille IS, taille OOS, taux de chevauchement/pas de glissement).
* **Conséquences** :
  * Le découpage ne doit laisser aucun espace vide entre les plis.
  * Si les données finales sont insuffisantes pour compléter le dernier pli OOS, celui-ci est tronqué et un avertissement est loggué.

#### FR-2: Optimisation par Grid Search déterministe
Pour chaque pli, le système doit exécuter un Grid Search sur les paramètres configurés sur la période In-Sample afin de maximiser la métrique cible (par défaut le Ratio de Sharpe).
* **Conséquences** :
  * Les résultats pour un ensemble de paramètres identique doivent être rigoureusement identiques à chaque exécution.
  * Le système doit exécuter l'optimisation en utilisant des threads légers (Virtual Threads) pour accélérer le traitement.
  * En cas d'égalité du ratio de Sharpe entre plusieurs combinaisons de paramètres, le système doit sélectionner celle ayant généré le plus grand nombre de transactions sur la période In-Sample.

---

### 4.2 Purge des frontières et reconstruction de la courbe OOS
**Description** : Nettoyage des trades transitoires entre l'In-Sample et l'Out-of-Sample pour éviter les biais statistiques, suivi de la reconstruction d'une courbe de performance OOS unifiée.

**Exigences fonctionnelles** :

#### FR-3: Purge des trades frontaliers
Lors de la transition d'un pli d'optimisation à la phase de test OOS, le système doit purger (ignorer) tout trade initié durant la période IS qui se termine sur la période OOS, ou initié trop près de la frontière IS/OOS.
* **Conséquences** :
  * La marge de sécurité (gap) avant la frontière est égale à la durée maximale historique des positions observée sur la stratégie.

#### FR-4: Concaténation de la courbe OOS unifiée
Le système doit fusionner chronologiquement les trades générés sur toutes les périodes Out-of-Sample des plis successifs pour former une courbe de performance OOS unifiée et un rapport de métriques consolidé.
* **Conséquences** :
  * Les statistiques globales (Sharpe, DD, Profit Factor) de la courbe unifiée doivent être calculées et présentées comme métrique finale du WFA.

---

### 4.3 API REST et Lanceur CLI pour le WFA
**Description** : Endpoints HTTP et commande CLI pour exécuter des analyses Walk-Forward de manière scriptée ou asynchrone.

**Exigences fonctionnelles** :

#### FR-5: API REST de contrôle du WFA
Le serveur HTTP de `trading-runtime` doit exposer un endpoint pour lancer et suivre un WFA.
* **Conséquences** :
  * `POST /api/runs/walk-forward` prend en entrée la configuration du WFA (stratégie, paramètres, plages, IS/OOS) et retourne un `wfaRunId` avec un statut HTTP 202.
  * `GET /api/runs/walk-forward/{wfaRunId}` retourne la progression (%), les résultats des plis terminés, et en fin de course les métriques OOS unifiées.
  * Les données de synthèse (ID, stratégie, Sharpe OOS unifié, chemin du rapport) sont sauvegardées dans la base SQLite locale.
  * Le rapport complet et détaillé (résultats de chaque pli, paramètres optimisés, trades OOS) est écrit dans un fichier JSON local nommé `wfa-{wfaRunId}.json` que l'IHM peut lire directement pour afficher la timeline et le tableau de stabilité.

#### FR-6: Lanceur CLI WFA
Le module `trading-examples` doit proposer une option CLI pour exécuter un WFA directement en ligne de commande.
* **Conséquences** :
  * Exemple : `mvn exec:java -pl trading-examples -Dexec.args="--wfa SmaCrossover EUR_USD 2015-2025 --config wfa-config.json"`

---

### 4.4 Vue Timeline IS/OOS dans l'IHM Desktop
**Description** : Visualisation sous forme de frise chronologique interactive dans l'application Desktop (Vue 3 / Electron) pour analyser le comportement des plis et la stabilité des paramètres.

**Exigences fonctionnelles** :

#### FR-7: Frise chronologique interactive
L'IHM doit afficher une frise horizontale représentant les plis temporels empilés.
* **Conséquences** :
  * Les segments IS sont colorés en gris.
  * Les segments OOS associés sont colorés en vert si leur Sharpe OOS est positif, ou rouge s'il est négatif.

#### FR-8: Tableau de stabilité des paramètres
Sous la frise, un tableau doit lister pour chaque pli les valeurs des paramètres qui ont été sélectionnés comme optimaux.
* **Conséquences** :
  * Permet de repérer visuellement si les paramètres optimaux sont stables d'un pli à l'autre ou s'ils dérivent de manière chaotique.

---

### 4.5 Suivi de fraîcheur et alertes de recalibration (WF due!)
**Description** : Système d'alerte indiquant à Martin quand une stratégie en cours de paper ou live trading nécessite une ré-optimisation.

**Exigences fonctionnelles** :

#### FR-9: Métadonnées de calibration dans la classe Java
Les règles de calibration (fréquence, limites) doivent être déclarées directement dans la classe Java de la stratégie à l'aide d'annotations. [ASSUMPTION: Nous allons créer une annotation `@CalibrationPolicy` dans `trading-core`].
* **Conséquences** :
  * Exemple : `@CalibrationPolicy(maxAgeDays = 30, maxBarsCount = 5000, maxTradesCount = 100)`

#### FR-10: Indicateurs de fraîcheur dans l'IHM et la TUI
Le tableau de bord de l'IHM Desktop et de la TUI doit afficher un indicateur visuel de fraîcheur pour chaque stratégie active.
* **Conséquences** :
  * 🔋 **Vert** : Calibration fraîche.
  * 🔔 **Orange (WF due)** : Calibration proche de la limite ou légèrement dépassée.
  * ⚠️ **Rouge (WF overdue)** : Calibration obsolète (dépassement critique des limites de temps, bars ou trades).

## 5. Non-Goals (Explicit)
* **Pas de hot-swap automatique** : Le système ne doit pas mettre à jour automatiquement les paramètres d'une stratégie en production sans l'intervention manuelle de Martin (pas d'auto-calibration live autonome).
* **Pas de multi-instrument complexe** : L'optimiseur WFA de la v1 ne gère qu'un seul instrument à la fois par exécution.

## 6. MVP Scope

### 6.1 In Scope
* Moteur de découpe IS/OOS avec algorithme de Grid Search séquentiel/parallélisé via Virtual Threads.
* Purge des transactions frontalières à la limite IS/OOS.
* API REST de lancement asynchrone + CLI de test.
* Interface graphique montrant la timeline des plis et la stabilité des paramètres.
* Annotations `@CalibrationPolicy` lues par le runtime pour afficher les badges (🔋, 🔔, ⚠️) sur l'IHM et la TUI.

### 6.2 Out of Scope for MVP
* Optimisation WFA utilisant le moteur génétique (`trading-genetics`) (reporté à la v2).
* Recalibration automatique automatisée sans validation humaine.

## 7. Success Metrics
* **SM-1** : L'analyse Walk-Forward unifiée produit des résultats 100% identiques pour les mêmes données et paramètres d'entrée (déterminisme parfait).
* **SM-2** : La timeline interactive de l'IHM Desktop se charge en moins de 500ms après la fin du WFA.
* **SM-C1** : L'utilisation des Virtual Threads pour le Grid Search ne doit pas consommer plus de 80% des ressources processeur configurées afin de ne pas perturber les instances de trading en cours d'exécution.

## 8. Open Questions
*Aucune question ouverte en suspens. All questions have been resolved during BMad elicitation and Party Mode.

## 9. Assumptions Index
* **[ASSUMPTION: Grid Search v1]** (§4.1) : Nous implémentons d'abord le Grid Search déterministe pour stabiliser les calculs avant d'étendre au moteur génétique.
* **[ASSUMPTION: Annotation @CalibrationPolicy]** (§4.5) : Les règles de calibration sont stockées directement comme annotations Java sur les classes de stratégies.
