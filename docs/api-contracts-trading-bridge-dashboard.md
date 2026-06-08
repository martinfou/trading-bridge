# Contrats API - Trading Bridge Dashboard (Laravel)

Le tableau de bord Laravel sert d'interface utilisateur Web d'administration. Il n'expose pas d'API publique REST pour des clients externes, mais fournit des routes Web pour l'interface utilisateur.

## Routes Web (UI)

* **`GET /`** (Redirection)
  * **Description** : Redirige automatiquement vers la page du tableau de bord principal `/control`.

* **`GET /control`**
  * **Description** : Affiche le panneau d'administration principal (Control Room) avec la liste des exécutions, l'état de santé du plan de contrôle Java, et les configurations des comptes courtiers.
  * **Contrôleur** : `App\Http\Controllers\ControlRoomController@show`
  * **Données injectées** : Données récupérées via l'API du plan de contrôle Java (`health`, `controlSummary`, `brokerAccounts`).

* **`POST /control/strategies/{strategyId}/kill`**
  * **Description** : Coupe-circuit de sécurité permettant de demander l'arrêt immédiat d'une stratégie de trading active.
  * **Contrôleur** : `App\Http\Controllers\ControlRoomController@kill`
  * **Corps de la requête (Formulaire)** :
    ```json
    {
      "reason": "Raison de l'arrêt (optionnel)"
    }
    ```
  * **Comportement** : Transmet la commande de coupure au serveur Java via l'API client `/api/strategies/{id}/kill` avec la source `laravel-dashboard`. Redirige vers `/control` avec un message flash de succès ou d'erreur.
