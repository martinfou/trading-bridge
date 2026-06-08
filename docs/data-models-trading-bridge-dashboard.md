# Modèles de Données - Trading Bridge Dashboard (Laravel)

Le tableau de bord Laravel s'appuie sur une base de données SQLite locale nommée `database.sqlite` (située dans `dashboard/database/database.sqlite`) pour le stockage de son propre état interne (utilisateurs, sessions, file d'attente de jobs).

> [!NOTE]
> Les données de trading réelles (backtests, événements de runs, déploiements) ne sont **pas** stockées dans cette base de données. Elles sont hébergées par le serveur Java et récupérées dynamiquement via l'API REST.

---

## Tables SQLite Standard (Laravel)

### 1. Table `users`
Utilisée pour l'authentification des administrateurs (si activée).
* **Colonnes clés** : `id`, `name`, `email`, `password`, `remember_token`, `created_at`, `updated_at`.

### 2. Table `cache` & `cache_locks`
Utilisée pour le cache local de l'application et les verrous distribués.
* **Colonnes cache** : `key` (TEXT, PRIMARY KEY), `value` (TEXT), `expiration` (INTEGER).

### 3. Table `jobs` & `failed_jobs`
Utilisée pour gérer les tâches de fond asynchrones du tableau de bord.
* **Colonnes jobs** : `id` (INTEGER, PRIMARY KEY), `queue` (TEXT), `payload` (TEXT), `attempts` (INTEGER), `reserved_at` (INTEGER), `available_at` (INTEGER), `created_at` (INTEGER).
