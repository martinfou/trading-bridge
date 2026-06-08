# Guide de Développement - Trading Bridge

Ce document décrit les procédures d'installation locale, de compilation, d'exécution des tests et de lancement en mode développement pour chaque partie du projet **Trading Bridge**.

---

## 1. Partie Java Backend (`trading-bridge-java`)

Le backend est structuré sous forme de monorepo Maven multi-modules nécessitant **Java 21** et **Maven 4.x**.

### Prérequis
*   **Java Development Kit (JDK)** : Version 21 (Eclipse Temurin recommandé).
*   **Maven** : Version 4.x (ou le script `./mvnw` fourni à la racine).

### Compilation globale
Pour nettoyer et compiler l'ensemble du monorepo Maven depuis la racine :
```bash
mvn clean install
```
*(Remarque : La commande `mvn clean install` doit s'exécuter sans erreur avant toute livraison ou validation).*

### Lancement des Tests
*   Exécuter tous les tests unitaires :
    ```bash
    mvn test
    ```
*   Exécuter les tests d'un module spécifique (ex: `trading-parser`) :
    ```bash
    mvn test -pl trading-parser
    ```
*   *Remarque* : Le test global `GoldenBacktestTest` est automatiquement ignoré si les données historiques locales sous `data/historical/` ne sont pas présentes.

### Commandes d'Exécution (CLI & Serveur)
*   **Lister les stratégies du catalogue** :
    ```bash
    mvn exec:java -pl trading-examples -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" -Dexec.args="--list"
    ```
*   **Lancer un backtest de démonstration (SmaCrossover)** :
    ```bash
    mvn exec:java -pl trading-examples -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" -Dexec.args="--sample"
    ```
*   **Lancer un backtest avec une stratégie et une année spécifiques** (ex: LondonOpenRangeBreakout sur EUR_USD pour 2012) :
    ```bash
    mvn exec:java -pl trading-examples -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
    ```
*   **Lancer le serveur de Plan de Contrôle (API/WebSockets)** :
    ```bash
    mvn exec:java -pl trading-runtime -Dexec.mainClass="com.martinfou.trading.runtime.ControlPlaneMain"
    ```
    *Le serveur écoute par défaut sur le port `8080`.*
*   **Lancer la console client interactive (TUI)** :
    ```bash
    # Nécessite que le plan de contrôle (ControlPlaneMain) soit déjà en cours d'exécution
    mvn exec:java -pl trading-tui -Dexec.mainClass="com.martinfou.trading.tui.TradingTuiMain"
    ```

---

## 2. Partie Application Bureau (`trading-bridge-desktop`)

L'application de bureau est construite avec **Electron**, **Vue 3**, **Vite** et **TypeScript**.

### Prérequis
*   **Node.js** : Version active LTS (ex: ^20 ou ^22).
*   **Java Runtime Environment (JRE)** (Nécessaire uniquement pour la distribution finale).

### Installation et Lancement en mode Dev
1.  Se positionner dans le sous-dossier :
    ```bash
    cd desktop
    ```
2.  Installer les dépendances NPM :
    ```bash
    npm install
    ```
3.  Lancer le serveur de développement avec rechargement automatique :
    ```bash
    npm run electron:dev
    ```

### Compilation et Packaging
Avant d'assembler l'application de bureau, il est nécessaire de générer le JAR shaded du plan de contrôle Java :
1.  Générer le JAR shaded (depuis la racine du monorepo) :
    ```bash
    mvn package -pl trading-runtime -am -DskipTests
    ```
2.  Copier le JAR shaded et générer la JRE intégrée (depuis le dossier `desktop`) :
    ```bash
    mkdir -p desktop-resources/jar
    cp ../trading-runtime/target/*-shaded.jar desktop-resources/jar/control-plane.jar
    bash scripts/build-jre.sh desktop-resources/jar/control-plane.jar desktop-resources
    ```
3.  Packager l'application selon votre plateforme :
    *   **Linux (AppImage, deb, pacman)** : `npm run package:linux`
    *   **macOS (DMG)** : `npm run package:mac`
    *   **Windows (NSIS)** : `npm run package:win`

---

## 3. Partie Tableau de Bord Web (`trading-bridge-dashboard`)

Le tableau de bord est une application **Laravel PHP** communiquant avec l'API Java.

### Prérequis
*   **PHP** : Version 8.3 ou supérieure.
*   **Composer** : Gestionnaire de dépendances PHP.
*   **Node.js & NPM**.

### Installation rapide
1.  Se positionner dans le dossier :
    ```bash
    cd dashboard
    ```
2.  Lancer le script d'initialisation rapide :
    ```bash
    composer run setup
    ```
    *Ce script se charge d'installer les dépendances (Composer et NPM), copier le fichier `.env.example`, générer la clé d'application, exécuter les migrations SQLite et générer les assets frontends.*

### Lancement du Serveur de Développement
Pour démarrer simultanément le serveur de développement Laravel, le processeur de file d'attente (queue listener), le lecteur de logs et Vite :
```bash
composer run dev
```

### Exécution des Tests
```bash
composer run test
```
