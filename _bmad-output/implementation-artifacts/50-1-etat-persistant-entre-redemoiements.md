# Story 50.1 — Le fichier d'état survit à un redéploiement (v2, lot 1)

- **Status :** done — déployé le 2026-10-27 à 19h37 EDT (image reconstruite, 4 services redémarrés, zéro
  position ouverte au moment du déploiement). Preuves : `State directory for 'vwpreversion': /app/data/state`,
  fichiers `data/state/live-strategy-state-*.json` présents sur l'hôte, `stateSource: resumed` après redémarrage
  du conteneur `comp-momentum` (`♻ Resumed state: 0 active trades...`), 152 tests verts sur 4 modules, revue
  indépendante en 2 passes APPROVED. Commit `084ebf42`.
- **Reste hors périmètre :** le lot 2 (blocage d'entrées) n'est pas ouvert. Le hub doit encore apprendre à
  lire `strategies[].untrackedBrokerTrades` pour l'afficher.
- **Date :** 2026-10-26
- **Propriétaire :** Martin
- **baseline_commit :** `2509b1e` (HEAD master après `git pull` du 2026-10-26 18h)
- **Décision :** D42 — l'état vit sur le volume persistant, l'instrument sauvegardé est validé, un orphelin
  devient visible et alerte. Le **blocage** des entrées est reporté au lot 2 (décision D43, Party Mode John).
- **Tech-spec :** `_bmad-output/planning-artifacts/tech-spec-state-file-durability.md`
- **Revue :** Winston CHANGES_REQUIRED, Amelia CHANGES_REQUIRED, John SCOPE_CHANGE. Corrections intégrées et
  re-vérifiées dans le code.

## Story

**En tant qu'**opérateur de la pile paper,
**je veux** que l'état des stratégies survive à un redéploiement, que l'instrument sauvegardé soit validé, et
qu'une position orpheline devienne visible,
**afin de** ne plus jamais avoir une position réelle chez le courtier sans gestionnaire et sans alerte.

## Contexte mesuré

Le 2026-10-26 à 18h00 EDT, la recréation de `trading-live` a détruit
`/tmp/live-strategy-state-<stratégie>.json` avec le conteneur. Le trade 504 (SELL 4 600 USD_CHF) est resté
ouvert chez OANDA pendant que le runner repartait à plat (`No saved state file found — starting fresh`),
sans aucune alerte (`unreconciled_trades: []`). Position fermée à la main à 18h04 pour **-5,74 CAD**.

Trois défauts supplémentaires ont été trouvés en revue : l'entrée n'est pas persistée avant 60 s (`:1483` vs
`saveStatePeriodic :1955`), `resumeState()` abandonne tout sur un seul champ corrompu (`catch` global `:2692`),
et `writeJsonAtomic` n'a pas de repli si `ATOMIC_MOVE` est refusé (`:2083`).

## User Stories (valeur métier)

| # | As a... | I want... | Value |
|---|---|---|---|
| US-1 | opérateur | qu'un redémarrage ne perde pas le suivi des positions ouvertes | le stop courtier n'est plus le seul rempart, la stratégie reprend sa sortie |
| US-2 | opérateur | qu'une position orpheline soit signalée dans le hub | je vois l'incident au lieu de le découvrir par le solde |
| US-3 | opérateur | distinguer « plat normal » de « plat parce qu'on a perdu la mémoire » | je sais quand redémarrer est sûr |
| US-4 | opérateur | qu'un changement de paire ne restaure pas des trades sur la mauvaise paire | un `STRATEGY_PAIR` modifié ne crée pas un fantôme suivi au mauvais prix |

## Coding Stories (lot 1)

| # | Story | Effort | Dépend de | Description |
|---|---|---|---|---|
| 50.1.1 | Répertoire d'état résolu et injecté | XS | — | `stateDir` depuis `live-config.json:16`, surcharge `TB_STATE_DIR`, injection par constructeur, `createDirectories`, log INFO du chemin |
| 50.1.2 | Volume persistant dans compose | XS | 50.1.1 | `/app/data/state` sur les **5** services (`trader`, `nfp-week`, `comp-momentum`, `month-week`, `lt-rsi3`) |
| 50.1.3 | Persistance immédiate sur fill | XS | — | `saveStateNow()` après `activeTrades.add` (`:1483-1489`) |
| 50.1.4 | Validation d'instrument dans `resumeState()` | S | 50.1.1 | Instrument sauvegardé ≠ résolu → `stale-instrument`, pas de restauration des `activeTrades` |
| 50.1.5 | Reprise non silencieuse | S | 50.1.4 | `stateSource ∈ {resumed, fresh, stale-instrument, resume-failed}`, log ERROR sur échec de parse |
| 50.1.6 | Tags au niveau trade | S | — | `tradeClientExtensions` dans `OandaExecutor` (ordre + stop), motif de `HttpOandaRestClient:311` |
| 50.1.7 | Détection d'orphelin, alerte seule | M | 50.1.5, 50.1.6 | `getOpenTradesDetailed()`, filtre par tag de stratégie, `untrackedBrokerTrades`, log ERROR, **aucun blocage d'entrée** |
| 50.1.8 | Repli d'écriture non atomique | XS | — | `AtomicMoveNotSupportedException` → move simple + WARN |
| 50.1.9 | Tests + redémarrage réel | S | toutes | 6 tests JUnit + vérification manuelle du volume |

## Détails d'implémentation

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|---|---|---|---|
| 50.1.1 | `LiveStrategyRunner.java:268` (modifier), appelants du constructeur `:126`/`:247` (modifier), `config/live-config.json:16` (consommer) | — ordre env > config > `/tmp`<br/>— constructeur reçoit `Path stateDir`<br/>— répertoire créé, chemin loggé INFO<br/>— tests existants verts sans variable | `StateDirResolutionTest` |
| 50.1.2 | `docker-compose.yml` (5 services) | — `TB_STATE_DIR=/app/data/state` sur les 5<br/>— `docker compose config` valide<br/>— fichier visible sous `./data/state/` | `docker compose config` + `ls data/state` |
| 50.1.3 | `LiveStrategyRunner.java:1483-1489` | — fill confirmé ⇒ fichier à jour immédiatement<br/>— pas d'écriture si le fill n'a pas de prix | `EntryPersistenceTest` |
| 50.1.4 | `LiveStrategyRunner.java:2509-2695` | — instrument ≠ ⇒ aucune `activeTrade` restaurée<br/>— compteurs et ledger conservés<br/>— WARN nommant les deux instruments | `ResumeStateInstrumentTest` |
| 50.1.5 | `LiveStrategyRunner.java` (`resumeState`, `writeAggregatedMonitor`) | — 4 valeurs de `stateSource`<br/>— parse en échec ⇒ ERROR, pas WARN | `ResumeStateFailureTest` |
| 50.1.6 | `OandaExecutor.java:73-94`, `:162-183` | — `tradeClientExtensions.tag` posé sur MARKET et STOP<br/>— même valeur que `clientExtensions`<br/>— `GET /openTrades` renvoie le tag | test de payload (motif `HttpOandaRestClientTest:103`) |
| 50.1.7 | `OandaExecutor.java` (nouvelle `getOpenTradesDetailed`), `LiveStrategyRunner.java` (moniteur) | — tag de la stratégie + absent local ⇒ listé + ERROR<br/>— tag d'une autre stratégie ⇒ ignoré<br/>— sans tag ⇒ compté sans attribution<br/>— **aucun ordre bloqué** | `OrphanDetectionTest` |
| 50.1.8 | `LiveStrategyRunner.java:2083-2096` | — `ATOMIC_MOVE` refusé ⇒ move simple + WARN<br/>— aucune exception propagée à l'appelant | `AtomicWriteFallbackTest` |
| 50.1.9 | `trading-strategies/src/test/java/com/martinfou/trading/strategies/` (créer 6 fichiers) | — 6 tests verts<br/>— redémarrage réel : `stateSource: resumed`, fichier sous `./data/state/` | `./mvnw -B test -Dtest='StateDirResolutionTest,ResumeStateInstrumentTest,ResumeStateFailureTest,EntryPersistenceTest,OrphanDetectionTest,AtomicWriteFallbackTest'` |

## Acceptance Criteria (DoD)

1. `stateDir` résolu selon l'ordre env > config > `/tmp`, répertoire créé, chemin loggé au démarrage.
2. Redémarrage de conteneur avec position ouverte : `stateSource: "resumed"`, `activeTrades` restauré, fichier
   sous `./data/state/` sur l'hôte.
3. Instrument sauvegardé ≠ résolu : `stateSource: "stale-instrument"`, aucune `activeTrade` de l'autre paire.
4. Parse en échec : `stateSource: "resume-failed"` + log ERROR.
5. Trade taggé pour la stratégie et absent localement : dans `untrackedBrokerTrades` + log ERROR. Tag d'une
   autre stratégie : absent de la liste. Sans tag : compté sans attribution.
6. Fill confirmé : état persisté immédiatement.
7. `ATOMIC_MOVE` refusé : écriture réussie via move simple.
8. `docker compose config` valide, 5 services avec répertoire persistant, **aucun blocage d'entrée ajouté**.
9. Revue indépendante passée avant fusion, `[verified]` sur le merge.
10. Aucune position ouverte au moment du déploiement, vérifié chez le courtier avant `up -d`.

## Hors périmètre (lot 2 et au-delà)

- `ENTRY_BLOCKED_ORPHAN` et tout refus d'entrée : lot 2, après preuve d'attribution par tag sur deux
  stratégies USD_JPY.
- Adoption automatique d'un orphelin (D36 / story 1.10).
- Déplacement de `monitorFile` hors de `/tmp`.
- Changement du format du fichier d'état.
