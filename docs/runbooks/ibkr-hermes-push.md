# Runbook — push IBKR vers hermes-web (lecture seule)

## Ce que ça fait

Toutes les 5 minutes, un one-shot lit le Gateway IBKR paper et pousse l'état du compte
(solde, buying power, P&L latent, positions ouvertes) vers hermes-web, qui l'affiche dans la
page Trading (`IbkrPanel.vue`).

Aucun ordre n'est jamais envoyé. Le publisher ne passe pas par le chemin d'ordres du trader.

## Pièces

| Élément | Chemin |
|---|---|
| Script | `scripts/ibkr_snapshot_push.py` |
| Interpréteur + `ibapi` | `~/.local/share/hermes-ibkr/venv` |
| Configuration (600) | `~/.config/hermes-ibkr/publisher.env` |
| Service | `~/.config/systemd/user/hermes-ibkr-publisher.service` |
| Timer | `~/.config/systemd/user/hermes-ibkr-publisher.timer` |
| Gateway | IBC sur `127.0.0.1:4002` (voir `ibkr-gateway-ibc`) |

## Configuration

`~/.config/hermes-ibkr/publisher.env` :

```
IBKR_GATEWAY_HOST=127.0.0.1
IBKR_GATEWAY_PORT=4002
IBKR_ACCOUNT_ID=DUR967322
HERMES_IBKR_CLIENT_ID=90
HERMES_WEB_URL=https://hermes.martinfournier.com
HERMES_WEB_TOKEN=<token accepté par VerifyHealthToken>
```

Le `clientId` 90 est dédié à la lecture. Ne pas le partager avec le trader (17) : IBKR refuse
deux connexions simultanées sur le même clientId (`code=326`).

## Vérifier

```bash
# contrat de payload, sans Gateway ni réseau
~/.local/share/hermes-ibkr/venv/bin/python scripts/ibkr_snapshot_push.py --selftest

# payload réel, aucun POST
~/.local/share/hermes-ibkr/venv/bin/python scripts/ibkr_snapshot_push.py --dry-run

# un cycle complet, poussé vers le hub
~/.local/share/hermes-ibkr/venv/bin/python scripts/ibkr_snapshot_push.py

# état du timer et du dernier run
systemctl --user list-timers hermes-ibkr-publisher.timer
systemctl --user status hermes-ibkr-publisher.service --no-pager
journalctl --user -u hermes-ibkr-publisher.service -n 20 --no-pager
```

Attendu sur un cycle réussi : `OK 201 /api/trading/broker/snapshot ... "created":true`.
Un même `snapshot_id` rejoué renvoie `200 ... "created":false` (idempotent).

Un cycle en échec sort en 1 et n'écrit rien : le tick suivant (5 min) repart d'un état propre.
Il n'y a **pas d'outbox** : un POST perdu n'est pas rejoué, il est simplement remplacé par le
snapshot suivant. Corollaire assumé : une métrique absente du payload (le Gateway ne l'a pas
renvoyée, ou a renvoyé le sentinel `1.7976931348623157E308`) s'affiche `—` dans le hub, jamais `0`.

**Gateway arrêté ou illisible** : le script publie quand même un snapshot **dégradé**
(`connection.connected: false`, `account` sans métriques, `gateway_errors` rempli) puis sort en 1.
C'est volontaire : sans ce push, le hub garderait le dernier `connected: true` et afficherait un
Gateway vivant alors qu'il est mort. Ne pas tester ce cas avec le port par défaut, sous peine de
publier un faux « disconnected » en production : utiliser `--dry-run` pour l'inspection.

## Pannes

| Symptôme | Cause | Correctif |
|---|---|---|
| `Gateway IBKR injoignable` | Gateway arrêté ou port faux | `ibgw` puis `ibgw-status` ; le port paper est **4002** |
| `code=326 client id is already in use` | clientId 90 déjà pris | changer `HERMES_IBKR_CLIENT_ID` ou libérer l'autre client |
| `401` du hub | token absent ou différent côté prod | reprendre `HEALTH_IMPORT_TOKEN` du `.env` déployé |
| `422` sur le snapshot | `snapshot_id` ou `account_id` manquant | vérifier `IBKR_ACCOUNT_ID` |
| Le panel affiche `Stale` | aucun push depuis > 10 min | timer inactif, ou Gateway arrêté |

## Ce que ça ne fait pas

- pas de fills historiques ni de P&L réalisé (Flex Queries, chantier séparé) : le POST
  `/api/trading/pnl-import` n'est pas appelé tant qu'il n'y a pas d'entrées à envoyer
- pas d'exécution d'ordres : c'est une décision ferme (lecture seule)
- pas de temps réel : la fraîcheur maximale est celle du timer, 5 minutes
- pas de filtrage des lignes `sec_type=CASH` : sur un compte multi-devises, IBKR remonte les
  soldes cash comme des positions CASH, et une vraie position forex (EUR.USD IDEALPRO) porte le
  même `sec_type`. Les filtrer masquerait le cas d'usage principal ; le champ `sec_type` reste
  donc dans le payload pour que l'affichage puisse les distinguer.
