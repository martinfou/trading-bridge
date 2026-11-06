# Inventaire des Composants Web — Trading Bridge Dashboard (`trading-bridge-dashboard`)

**Date de mise à jour :** 2026-11-05  
**Framework :** PHP `8.3` / Laravel `13.8` / Blade SSR + Tailwind CSS `4.0`  
**Emplacement :** `dashboard/resources/views/` & `dashboard/app/`

---

## 1. Vue Principale (`resources/views/control-room.blade.php` — 160 lignes)

La console Web d'administration repose sur une architecture SSR autonome à rafraîchissement automatique (`<meta http-equiv="refresh" content="{{ $refreshSeconds }}">`, 5 secondes par défaut) structurée en 6 blocs visuels :

| Bloc UI | Données Sources (`ControlPlaneClient`) | Fonctionnalités & Indicateurs |
| :--- | :--- | :--- |
| **1. Bandeau d'En-Tête & Statut** | `health()` (`GET /api/health`) + Flash session | Affiche l'URL du Control Plane Java, la cadence de rafraîchissement, la version du moteur (`$health['version']`) et les bannières vertes/rouges de statut ou d'inaccessibilité du serveur Java. |
| **2. Carte de Fraîcheur (*Freshness Card*)** | `$summary['freshness']` (`GET /api/control/summary`) | Affiche l'horodatage du dernier événement reçu (`lastEventAt`) et le délai écoulé en secondes (`secondsSinceLastEvent`). |
| **3. Carte des Comptes Courtiers** | `$brokers['accounts']` (`GET /api/broker-accounts`) | Liste les comptes configurés (`OANDA`, `IBKR`), l'identifiant masqué (`accountIdMasked`) et l'indicateur de présence des identifiants (`✓` / `✗`). |
| **4. Tableau d'Exposition & Drawdown Journalier** | `$summary['runs']` (`GET /api/control/summary`) | Liste chaque run actif avec `strategyId`, `runId`, `mode`, badge `executionLabel`, statut (`LIVE` vert vs `STALE` jaune si `isStale`), `dailyDrawdownPct` + badge `BREACH` en cas de dépassement, compteur d'événements et **formulaire POST de Kill Switch** (`route('control-room.kill', $run['strategyId'])`) protégé par confirmation JavaScript (`confirm('Kill ...?')`). |
| **5. Carte des Ruptures de Séquence (*Gap Signals*)** | `$summary['signals']['gaps']` | Alerte l'opérateur en cas de numéros de séquence manquants dans le flux d'événements d'un run. |
| **6. Carte des Signaux de Dérive (*Drift Signals*)** | `$summary['signals']['drift']` | Affiche les recommandations du `DriftEngine` (`strategyId`, `recommendation`, `reason`) lorsque le comportement Live/Paper s'écarte du backtest de référence. |

---

## 2. Composants Applicatifs PHP (`dashboard/app/`)

* **`App\Http\Controllers\ControlRoomController`** (`app/Http/Controllers/ControlRoomController.php`, 59 lignes) :
  * `show()` : Orchestre les appels au Control Plane Java et garantit un rendu tolérant aux pannes (HTTP 200 même si le démon Java est arrêté).
  * `kill()` : Valide la raison de l'arrêt d'urgence et transmet l'ordre de liquidation au Control Plane Java (`actor = "laravel-dashboard"`).
* **`App\Services\ControlPlaneClient`** (`app/Services/ControlPlaneClient.php`, 76 lignes) :
  * Client HTTP Laravel (`Illuminate\Support\Facades\Http`) encapsulant `health()`, `controlSummary()`, `brokerAccounts()` et `kill()`.
* **`App\Providers\AppServiceProvider`** (`app/Providers/AppServiceProvider.php`, 27 lignes) :
  * Enregistre `ControlPlaneClient` en singleton via `config/trading.php`.
