# Modèles de Données — Trading Bridge Dashboard (`trading-bridge-dashboard`)

**Date de mise à jour :** 2026-11-05  
**Base de données locale :** SQLite (`dashboard/database/database.sqlite`)  
**ORM :** Laravel Eloquent (`Laravel 13.8`)

---

## 1. Architecture Sans État Métier (*Stateless Domain Proxy*)

Le panneau Web **Trading Bridge Dashboard** (`dashboard/`) ne duplique aucune donnée métier de trading (ni transactions, ni stratégies, ni courbes de capital). Toutes les informations opérationnelles affichées dans la vue `control-room.blade.php` sont récupérées à la volée auprès du serveur Java (`trading-runtime`) via `App\Services\ControlPlaneClient`.

---

## 2. Tables d'Infrastructure Laravel Locales (`database/migrations/`)

La base SQLite locale `database/database.sqlite` est utilisée uniquement pour le fonctionnement technique interne du framework Laravel (sessions HTTP, jetons CSRF, cache et files d'attente) :

1. **`0001_01_01_000000_create_users_table.php`** :
   * `users` (`id`, `name`, `email`, `email_verified_at`, `password`, `remember_token`, `timestamps`) — Modèle `App\Models\User`.
   * `password_reset_tokens` (`email` PK, `token`, `created_at`).
   * `sessions` (`id` PK, `user_id`, `ip_address`, `user_agent`, `payload`, `last_activity`) — Stockage des sessions opérateur et des messages flash (`status` / `error`) après activation du Kill Switch.
2. **`0001_01_01_000001_create_cache_table.php`** :
   * `cache` (`key` PK, `value`, `expiration`) et `cache_locks` (`key` PK, `owner`, `expiration`).
3. **`0001_01_01_000002_create_jobs_table.php`** :
   * `jobs`, `job_batches`, `failed_jobs`.
