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

## Architecture retenue

Nouvelle interface dédiée, **sans toucher** `IbkrGatewayClient` ni `TcpIbkrGatewayClient` :

```
trading-data/src/main/java/com/martinfou/trading/data/ibkr/readonly/
├── IbkrReadOnlyClient.java        interface : connect, disconnect, isConnected,
│                                              fetchAccountDetails, fetchOpenPositions,
│                                              fetchAllOpenOrders
└── EClientIbkrReader.java         implémentation basée sur l'EClient officiel (ibapi),
                                   latches bornés, aucun ordre

trading-runtime/src/main/java/com/martinfou/trading/runtime/hermes/
├── HermesIbkrPublisher.java       cycle push + outbox
├── HermesIbkrPublisherMain.java   CLI : --once | --dry-run | --interval N
└── HttpHermesSender.java          POST bearer vers hermes-web
```

Le reader réutilise l'EClient officiel déjà vendoré dans `trading-data` (`com.ib.client`). Il ouvre
sa propre connexion avec **clientId 90** (`HERMES_IBKR_CLIENT_ID`), ce qui supprime la collision
`code=326 client id is already in use` mesurée avec le control plane.

## Configuration

| Variable | Défaut | Rôle |
|---|---|---|
| `IBKR_GATEWAY_HOST` | `127.0.0.1` | Gateway local |
| `IBKR_GATEWAY_PORT` | `4002` | port paper (le live est 4001 ; ne pas se fier à `LocalServerPort`) |
| `IBKR_ACCOUNT_ID` | requis | `DUR967322` |
| `HERMES_IBKR_CLIENT_ID` | `90` | clientId dédié lecture |
| `HERMES_WEB_URL` | requis | `https://hermes.martinfournier.com` |
| `HERMES_WEB_TOKEN` | requis | token accepté par `VerifyHealthToken` |
| `HERMES_IBKR_STATE_DIR` | `data/runtime` | dossier de l'outbox |

## Comportement d'un cycle `--once`

1. `connect()` sur le Gateway paper.
2. `reqAccountSummary` (NetLiquidation, TotalCashValue, BuyingPower, AvailableFunds, InitMargin,
   MaintMargin, GrossPositionValue, UnrealizedPnL, RealizedPnL, Currency) avec attente bornée 15 s.
3. `reqPositions` avec attente bornée 15 s.
4. POST `/api/trading/broker/snapshot` avec le payload figé.
5. POST `/api/trading/pnl-import` (`broker=ibkr`) pour le snapshot de compte.
6. Sortie code 0 si les deux POST sont acceptés, 1 sinon (le timer systemd journalise l'échec).
7. `disconnect()` dans un `finally`, quoi qu'il arrive.

Un POST en échec est conservé dans l'outbox (`HERMES_IBKR_STATE_DIR`) et rejoué au cycle suivant.
Un `snapshot_id` déjà accepté n'est jamais rejoué.

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
