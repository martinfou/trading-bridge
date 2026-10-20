# Tech-spec — Publisher IBKR vers Hermes (lecture seule)

> Phase 2/3 BMad — 2026-10-20
> Product Owner : Martin Fournier
> Statut : prêt pour implémentation

## Objectif

Pousser l'état du compte IBKR paper (compte + positions ouvertes + P&L latent) vers hermes-web,
toutes les 5 minutes, en one-shot, sans jamais toucher au chemin d'ordres du trader.

## Décisions

| # | Décision |
|---|---|
| D1 | Lecture seule. Le publisher n'appelle jamais `placeMarketOrder` ni `cancelOrder`. |
| D2 | Push, pas pull. Le hub ne parle jamais à IBKR. |
| D5 | Timer systemd user, exécution `--once` toutes les 5 minutes. Aucun process permanent. |

## Ce qui existe déjà et ne doit pas être réécrit

`origin/feature/ibkr-futures-hermes-publisher` contient `HermesIbkrPublisher`, `HermesIbkrPublisherMain`,
`HttpHermesSender` et leurs tests. Le contrat de payload est figé (voir la tech-spec hermes-web).
Cette branche est en retard de 202 commits : **on porte les fichiers, on ne merge pas la branche.**

Écart mesuré : le publisher de la branche appelle `fetchAccountDetails`, `fetchAllOpenOrders`,
`fetchExecutions` sur `IbkrGatewayClient`. Sur `master`, cette interface ne les expose pas, et
`TcpIbkrGatewayClient` (client maison utilisé par le chemin d'ordres) ne doit pas être modifié.

## Architecture retenue (amendée après mesure)

Le port Java a été évalué puis écarté : `EClientIbkrGatewayClient` fait 1059 lignes couplées au chemin
d'ordres, et l'importer sur `master` (202 commits d'écart) demandait un refactor non revu pour un besoin
de lecture. Le repo a déjà la convention d'un script one-shot (`scripts/smoke-oanda-readonly.py`,
`scripts/trading_pnl_push.py`), donc le publisher suit ce pattern.

```
scripts/ibkr_snapshot_push.py         one-shot : ibapi -> payload -> POST hermes-web
deploy/systemd (côté machine)         hermes-ibkr-publisher.{service,timer}, 5 min
```

- interface `ibapi` officielle, **clientId 90** (`HERMES_IBKR_CLIENT_ID`) : aucune collision avec le
  clientId 17 du control plane ni avec celui du trader
- aucune classe du chemin d'ordres n'est touchée (`git diff --stat` le prouve)
- `--selftest` vérifie le contrat de payload sans Gateway ni réseau (11 vérifications)
- `--dry-run` imprime le payload et n'émet aucun POST

Ce qui est conservé du travail existant : le **contrat de payload** (déjà celui qu'attend
`IbkrPanel.vue`), l'idempotence par `snapshot_id`, et le principe d'un clientId dédié.
Ce qui est abandonné : l'outbox Java et le publisher `--interval` permanent, remplacés par le timer.

## Configuration

| Variable | Défaut | Rôle |
|---|---|---|
| `IBKR_GATEWAY_HOST` | `127.0.0.1` | Gateway local |
| `IBKR_GATEWAY_PORT` | `4002` | port paper (le live est 4001 ; ne pas se fier à `LocalServerPort`) |
| `IBKR_ACCOUNT_ID` | requis | `DUR967322` |
| `HERMES_IBKR_CLIENT_ID` | `90` | clientId dédié lecture |
| `HERMES_WEB_URL` | requis | `https://hermes.martinfournier.com` |
| `HERMES_WEB_TOKEN` | requis | token accepté par `VerifyHealthToken` |

## Comportement d'un cycle `--once`

1. `connect()` sur le Gateway paper.
2. `reqAccountSummary` (NetLiquidation, TotalCashValue, BuyingPower, AvailableFunds, InitMargin,
   MaintMargin, GrossPositionValue, UnrealizedPnL, RealizedPnL, Currency) avec attente bornée 20 s.
3. `reqPositions` avec attente bornée 20 s.
4. **Les deux flux sont exigés** : si un seul arrive, le cycle échoue (aucun POST partiel).
5. POST `/api/trading/broker/snapshot` avec le payload figé.
6. Sortie 0 si accepté, 1 sinon (le timer journalise l'échec, le tick suivant repart propre).
7. `disconnect()` dans un `finally`, quoi qu'il arrive.

Une métrique absente ou égale au sentinel IBKR `1.7976931348623157E308` est **omise** du payload :
le hub affiche `—`, jamais un `0` inventé. Les erreurs non fatales du Gateway sont remontées dans
`gateway_errors` ; une erreur fatale (326, 502, 504, 1100, 1102, 2110) fait échouer le cycle au lieu
de publier un état « connecté » mensonger.

Pas d'outbox : un POST perdu n'est pas rejoué, il est remplacé au tick suivant.

## Interdits explicites

- Aucun appel d'ordre, d'annulation ou de modification.
- Aucun `TB_ALLOW_ORDERS` : le publisher ne doit pas en dépendre, il ne passe pas par le chemin d'ordres.
- Aucun `IBKR_USE_STUB`.
- Pas de process permanent : `--once` uniquement en production.

## Déploiement

`deploy/systemd/` :
- `hermes-ibkr-publisher.service` : `Type=oneshot`, `EnvironmentFile=%h/.config/hermes-ibkr/publisher.env`
- `hermes-ibkr-publisher.timer` : `OnBootSec=2min`, `OnUnitActiveSec=5min`, `Persistent=true`

Le service dépend du Gateway : s'il n'est pas joignable, il sort en 1 sans rien écrire.

## Critères d'acceptation

- [ ] `--dry-run --once` imprime le JSON du payload et n'émet aucun POST
- [ ] `--once` sur le Gateway paper renvoie code 0 et le hub accuse réception
- [ ] Le compte IBKR apparaît dans le hub avec NAV 11282.16 CAD et 0 position quand le compte est flat
- [ ] Un Gateway arrêté fait sortir en 1 avec un message clair, sans écriture partielle
- [ ] Aucune classe du chemin d'ordres n'est modifiée (`git diff --stat` le prouve)
- [ ] Un test prouve que le publisher n'appelle aucune méthode d'ordre

## Hors périmètre

- fills historiques et P&L réalisé (Flex Queries)
- exécution d'ordres IBKR (D1)
