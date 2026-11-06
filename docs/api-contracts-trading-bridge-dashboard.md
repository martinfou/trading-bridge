# Contrats Web — Trading Bridge Dashboard (`trading-bridge-dashboard`)

**Date de mise à jour :** 2026-11-05  
**Framework :** PHP `8.3` / Laravel `13.8` (`dashboard/routes/web.php`)  
**Contrôleur :** `App\Http\Controllers\ControlRoomController`

---

## 1. Distinction Architecturale : `dashboard/` vs `hermes-web`

Le projet **Trading Bridge** interagit avec deux interfaces Web distinctes :
1. **`trading-bridge-dashboard` (`dashboard/` dans ce dépôt)** : Console d'administration SSR légère et autonome (créée lors de l'Epic 13.7) qui interroge directement le Control Plane Java local (`http://127.0.0.1:8080`) en mode *pull* HTTP.
2. **Portail externe `hermes-web` (`https://hermes.martinfournier.com`)** : Plateforme externe vers laquelle les démons Python (`scripts/sqx_telemetry_pusher.py`, `scripts/ibkr_pnl_push.py`, `scripts/ibkr_snapshot_push.py`) et `OperationsSnapshotPusher` poussent la télémétrie en mode *push*.

---

## 2. Routes Web Exposées par `trading-bridge-dashboard` (`routes/web.php`)

| # | Méthode | URI | Nom de Route | Action Contrôleur | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 1 | `GET` | `/` | — | `Redirect` vers `/control` | Redirection automatique de la racine vers la salle de contrôle. |
| 2 | `GET` | `/control` | `control-room` | `ControlRoomController@show` | Agrège `/api/health`, `/api/control/summary` et `/api/broker-accounts` depuis le serveur Java et affiche `resources/views/control-room.blade.php` (rafraîchissement automatique toutes les 5 s). Si le serveur Java est injoignable, rend quand même un code HTTP `200` avec une bannière d'alerte explicite. |
| 3 | `POST` | `/control/strategies/{strategyId}/kill` | `control-room.kill` | `ControlRoomController@kill` | Valide le motif (`reason`, max 500 caractères), appelle `POST /api/strategies/{strategyId}/kill` sur le Control Plane Java avec `actor = "laravel-dashboard"`, et redirige vers `/control` avec un message flash de confirmation ou d'erreur. |
| 4 | `GET` | `/up` | — | Healthcheck natif Laravel 13 | Endpoint de santé de l'application PHP (`bootstrap/app.php`). |

---

## 3. Appels Sortants vers le Control Plane Java (`App\Services\ControlPlaneClient`)

Configuré via `config/trading.php` (`CONTROL_PLANE_URL`, défaut `http://127.0.0.1:8080`) :
* `GET {baseUrl}/api/health` (timeout 15 s)
* `GET {baseUrl}/api/control/summary` (timeout 15 s)
* `GET {baseUrl}/api/broker-accounts` (timeout 15 s)
* `POST {baseUrl}/api/strategies/{strategyId}/kill` (timeout 30 s, payload JSON `{"actor": "laravel-dashboard", "reason": "..."}`)
