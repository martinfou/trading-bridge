# Guide de Déploiement et Opérations - Trading Bridge

Ce document décrit les procédures de déploiement de la plateforme de trading en production ou sur un serveur VPS de trading en direct (Live Trading).

---

## 1. Déploiement Docker (VPS Live Trading)

La plateforme de trading en direct s'appuie sur Docker et Docker Compose pour isoler et exécuter les stratégies sur des paires de devises spécifiques.

### Structure du Déploiement
Les services de trading en direct sont déclarés dans le fichier [`docker-compose.yml`](file:///home/martinfou/dev/src/trading-bridge/docker-compose.yml) :
*   **`trader`** : Exécuteur principal (`LiveStrategyRunner`) faisant tourner les stratégies de référence (ex: `VWPReversion` et `ConsecutiveBarExhaustion`) sur `USD_CHF` (la paire avec le meilleur ratio de Sharpe historique).
*   **`nfp-week`** : Service dédié aux stratégies de volatilité macroéconomique lors des semaines de publication du rapport NFP américain (ex: Short `EUR_USD`).
*   **`comp-momentum`** : Stratégie de momentum composite sur la paire `USD_JPY`.
*   **`month-week`** : Stratégie temporelle mensuelle/hebdomadaire sur la paire `USD_JPY`.

### Lancement des Services
Pour lancer l'ensemble des conteneurs en tâche de fond (mode détaché) :
```bash
docker compose up -d
```

### Surcharger les Stratégies ou Paires
Vous pouvez personnaliser la stratégie ou la paire de devises ciblée via les variables d'environnement lors de l'appel de Docker Compose :
```bash
STRATEGY="vwpreversion" STRATEGY_PAIR="EUR_USD" docker compose up -d trader
```

---

## 2. Configuration et Sécurité (Variables d'Environnement)

Les identifiants et clés d'accès aux courtiers (comme OANDA) ne doivent **jamais** être écrits en dur ou enregistrés dans les fichiers du dépôt.

### Fichier `.env`
Les conteneurs Docker chargent leurs identifiants depuis le fichier d'environnement situé à l'emplacement :
`/home/martinfou/projects/trading-dashboard/.env`

Ce fichier doit contenir au minimum les variables suivantes :
```env
# Clé d'API OANDA (Practice ou Live)
OANDA_API_KEY=votre_cle_api_securisee

# Identifiant de compte de trading principal
OANDA_ACCOUNT_ID=votre_identifiant_de_compte

# Type d'environnement OANDA (practice ou live)
OANDA_ENV=practice
```

---

## 3. Persistance des Données et Journalisation

Pour garantir que les transactions, rapports de backtests et journaux d'événements survivent aux redémarrages des conteneurs, des volumes Docker sont montés :

*   **Données persistantes** (`./data` ou volume `data`) : Monté sur `/app/data` dans le conteneur. Il contient la base de données SQLite `events.db` et le registre local des comptes courtiers.
*   **Journaux** (`./logs` ou volume `logs`) : Monté sur `/app/logs` pour stocker les fichiers de logs de l'application Java.

---

## 4. Pipeline de Production et CI/CD

Le dépôt intègre des flux GitHub Workflows pour valider et packager la solution.

*   **Fichiers de CI** : Situés sous `.github/workflows/`.
*   **Vérification de build** : Chaque pull request déclenche une compilation complète et lance la suite des tests unitaires (`mvn test`).
*   **Packaging automatique** : Des builds matriciels sont configurés pour compiler et packager automatiquement l'application de bureau Electron (`desktop`) pour Linux, Windows et macOS lors du taggage d'une version de release.
