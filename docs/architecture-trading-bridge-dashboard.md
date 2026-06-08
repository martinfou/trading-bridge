# Architecture - Trading Bridge Dashboard (Laravel)

Ce document présente l'architecture technique détaillée du tableau de bord Web **Trading Bridge Dashboard**.

---

## 1. Résumé Exécutif (Executive Summary)

**Trading Bridge Dashboard** est une console d'administration et de supervision Web optionnelle et légère, déployée principalement sur des serveurs VPS. Elle permet de suivre à distance et en temps réel (via du rafraîchissement périodique) l'état de fonctionnement du plan de contrôle Java, de consulter le résumé des exécutions de stratégies actives, et de déclencher manuellement la coupure d'urgence (Kill Switch) en cas d'anomalie de marché.

---

## 2. Pile Technologique (Technology Stack)

*   **Langage Serveur** : PHP ^8.3
*   **Framework Applicatif** : Laravel ^13.8 (avec Tinker, Pint, Pail, Pao)
*   **Style & UI** : Tailwind CSS ^4.0.0 (compilé via Vite)
*   **Bundler d'Assets** : Vite ^8.0.0
*   **Base de Données Locale** : SQLite
*   **Framework de Tests** : PHPUnit ^12.5.12

---

## 3. Patron d'Architecture (Architecture Pattern)

L'application respecte le patron classique **MVC (Modèle-Vue-Contrôleur)** fourni par le framework Laravel :

*   **Contrôleurs (Controllers)** : Interceptent les requêtes Web des navigateurs, appellent le client API Java synchrone pour collecter les données, et injectent les résultats dans les vues Blade.
*   **Vues (Views)** : Fichiers Blade générant du HTML sémantique dynamique, stylisés à l'aide de Tailwind CSS.
*   **Services** : Un client HTTP unifié encapsule les requêtes d'intégration vers le serveur Java.

---

## 4. Architecture des Données (Data Architecture)

Le tableau de bord utilise sa propre base de données SQLite locale (`database.sqlite`) exclusivement pour stocker son état applicatif (utilisateurs inscrits, sessions actives et gestion des jobs).
Il n'a pas de tables propres pour les données de trading (backtests, événements de marché, positions réelles). Ces informations résident dans la base du moteur Java et sont sollicitées à la volée par le biais d'appels d'API REST.

---

## 5. Design de l'Intégration API

*   **Client REST synchrone** : Le service `ControlPlaneClient` effectue des requêtes HTTP synchrone (en utilisant le client HTTP natif de Laravel basé sur Guzzle) vers le point de terminaison Java local sur `localhost:8080`.
*   **Polling périodique** : L'interface utilisateur Web se rafraîchit à un intervalle régulier de 5 secondes (paramétrable dans la configuration PHP `trading.refresh_seconds`) pour maintenir les informations de trading à jour.

---

## 6. Structure des Fichiers (Source Tree)

*   `dashboard/app/Http/Controllers/ControlRoomController.php` : Récupère les données et gère l'action de Kill Switch.
*   `dashboard/app/Services/ControlPlaneClient.php` : Classe de service d'appels API Java.
*   `dashboard/routes/web.php` : Déclarations des routes d'accès Web `/` et `/control`.
*   `dashboard/resources/views/control-room.blade.php` : Vue Blade principale pour l'affichage de la console de contrôle.
*   `dashboard/database/migrations/` : Fichiers de migration de la base locale SQLite.

---

## 7. Déploiement et Opérations (Deployment Architecture)

Le tableau de bord Laravel est principalement destiné à être déployé sur le même serveur VPS que le moteur de trading Java.
Il est installé en local via Composer et s'exécute généralement en arrière-plan à côté du serveur Java. Les identifiants d'accès OANDA et les variables d'environnement de sécurité sont centralisés dans le fichier `.env` de l'application Laravel et partagés avec le conteneur de trading Docker de production.
