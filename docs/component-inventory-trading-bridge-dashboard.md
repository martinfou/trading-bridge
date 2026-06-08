# Inventaire des Composants Dashboard - Trading Bridge

Ce document répertorie et décrit les éléments d'interface utilisateur du tableau de bord Web d'administration de **Trading Bridge Dashboard** (Laravel + TailwindCSS).

---

## 1. Vues Principales (Blade Templates)

Le tableau de bord est conçu de manière minimaliste et efficace sous forme d'une console unique (située sous `dashboard/resources/views/`) :

*   **`control-room.blade.php`** (Console de Contrôle)
    *   *Description* : Vue principale de l'application. Elle affiche l'état opérationnel complet de la plateforme, les stratégies actives et permet les coupures de sécurité.
*   **`welcome.blade.php`** (Page d'accueil par défaut)
    *   *Description* : Fichier par défaut de Laravel (inactif, car les accès à la racine `/` sont redirigés vers la console `/control`).

---

## 2. Éléments Graphiques de la Console (Control Room Panel)

La console de contrôle de `control-room.blade.php` est composée de plusieurs cartes et zones fonctionnelles :

### A. Bandeaux d'Alertes et Messages
*   **Bandeau de statut (Success Banner)** : Affiche en vert le succès d'une opération de coupure (Kill Switch) acceptée par le serveur.
*   **Bandeau d'erreur (Error Banner)** : S'affiche en rouge si le serveur Java est injoignable, ou en cas d'échec d'une requête HTTP.

### B. Cartes d'État (KPI & Broker Status)
*   **Carte de Fraîcheur (Freshness Card)** : Affiche le moment du dernier événement reçu par le serveur Java et le temps écoulé en secondes (utile pour détecter un blocage du moteur).
*   **Carte des Comptes Courtiers (Broker Accounts Card)** : Liste les comptes configurés (OANDA, Interactive Brokers), leur état de configuration et masque les identifiants sensibles (`accountIdMasked`).

### C. Tableau des Stratégies en Cours (Runs Table)
Tableau récapitulatif listant toutes les exécutions actives. Il affiche :
*   L'identifiant de la stratégie et son UUID d'exécution (`runId`).
*   Le mode d'exécution (Live / Paper / Simulation) accompagné de son étiquette d'exécution (ex: `PAPER_STUB`).
*   L'état de réactivité : Affiche un badge vert **LIVE** ou un badge orange **STALE** (si aucun événement n'a été reçu récemment).
*   La dérive journalière (Daily Drawdown %) : Affiche la perte maximale de la journée avec un badge de dépassement rouge **BREACH** si le seuil autorisé est franchi.
*   Le nombre d'événements comptabilisés dans le flux.
*   Le bouton d'arrêt d'urgence **Kill** : Déclenche une demande de confirmation JavaScript avant de poster le formulaire d'interruption.

### D. Cartes de Signaux d'Alerte (Signals Cards)
*   **Signaux de Trous de Séquences (Gap Signals)** : Liste les pertes d'événements ou écarts détectés dans l'historique du journal SQLite.
*   **Signaux de Dérive (Drift Signals)** : Affiche des recommandations (HOLD, etc.) et des explications si les résultats en direct s'écartent statistiquement des prévisions théoriques du backtest de référence.
