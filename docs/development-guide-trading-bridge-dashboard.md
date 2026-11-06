# Guide de Développement — Console Web (`trading-bridge-dashboard`)

**Date de mise à jour :** 2026-11-05  
**Portée :** Application PHP 8.3 + Laravel 13.8 (`dashboard/`)

---

## 1. Installation et Démarrage Local

```bash
cd dashboard

# Installation complète (Composer, fichier .env, clé APP_KEY, migrations SQLite, build Vite)
composer run setup

# Lancer le serveur de développement (http://127.0.0.1:8000/control)
composer run dev
```

---

## 2. Configuration (`.env` & `config/trading.php`)

| Variable `.env` | Valeur par Défaut | Description |
| :--- | :--- | :--- |
| `CONTROL_PLANE_URL` | `http://127.0.0.1:8080` | URL de base du serveur `trading-runtime` Java. |
| `CONTROL_ROOM_REFRESH_SECONDS` | `5` | Cadence de rafraîchissement automatique (`<meta http-equiv="refresh">`) de la vue `/control`. |

---

## 3. Principes d'Architecture et Tests (`PHPUnit`)

1. **Aucune persistance métier locale** : Ne créez pas de modèles Eloquent pour stocker des trades ou des runs dans `dashboard/`. Toute donnée métier doit provenir de `App\Services\ControlPlaneClient`.
2. **Résilience Hors-Ligne** : `ControlRoomController::show` doit systématiquement intercepter `Illuminate\Http\Client\RequestException` et retourner un code HTTP `200` avec des tableaux vides par défaut et la variable `$error` renseignée, afin que la console d'administration reste accessible même si le démon Java redémarre.
3. **Exécution des tests fonctionnels** :
   ```bash
   cd dashboard
   composer test
   ```
   Les tests situés dans `tests/Feature/ControlRoomTest.php` utilisent `Http::fake()` pour valider le rendu de `/control` et l'envoi du Kill Switch (`POST /control/strategies/{strategyId}/kill` avec `actor = "laravel-dashboard"`).

