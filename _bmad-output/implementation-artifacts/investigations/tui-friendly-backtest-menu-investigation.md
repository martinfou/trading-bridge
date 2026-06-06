# Investigation : TUI convivial — menu interactif backtest par actif

## Hand-off Brief

1. **Ce qui se passe.** Le TUI expose un assistant `/backtest` (strategy → symbole → dates) mais reste centré sur les commandes slash, sans menu principal ni parcours « actif d’abord » ; l’affichage des plages de dates dépend d’une API control plane récente souvent absente sur les instances en cours.
2. **Où en est le dossier.** Périmètre et écarts UX cartographiés ; causes opérationnelles et de conception identifiées ; correctifs partiels déjà mergés localement (wizard live, rapport CLI, `/api/data/*`) mais pas encore une expérience « menu convivial » complète.
3. **Prochaine étape recommandée.** `bmad-quick-dev` pour un menu racine + parcours backtest « actif → stratégie → plage affichée » avec sélection JLine (complétion / numéros), après redémarrage control plane à jour.

## Case Info

| Field            | Value                                                                 |
| ---------------- | --------------------------------------------------------------------- |
| Ticket           | N/A                                                                   |
| Date opened      | 2026-05-31                                                            |
| Status           | Active                                                                |
| System           | trading-tui (JLine3) + trading-runtime (control plane HTTP :8080)   |
| Evidence sources | Code source, historique session utilisateur, docs Epic 13.6          |

## Problem Statement

**Hypothèse utilisateur (à traiter comme telle, pas comme fait) :** « Je veux un TUI convivial, pouvoir lancer un backtest sur un actif, avec un menu interactif user-friendly. »

Interprétation opérationnelle : menu visible sans mémoriser les slash commands ; choix d’actif (EUR_USD, etc.) mis en avant ; plages de dates disponibles affichées avant validation ; retour d’exécution lisible (rapport type CLI).

## Evidence Inventory

| Source                         | Status    | Notes                                                                 |
| ------------------------------ | --------- | --------------------------------------------------------------------- |
| `trading-tui/.../TradingTuiMain.java` | Available | Boucle `tui>` ; pas de menu racine                                    |
| `trading-tui/.../TuiCommandHandler.java` | Available | Slash obligatoire ; `/backtest` sans args → wizard                    |
| `trading-tui/.../TuiInteractiveBacktest.java` | Available | Wizard 3 étapes ; listes numérotées ; prompts texte                    |
| `trading-runtime/.../ControlPlaneServer.java` | Available | `GET /api/data/symbols`, `GET /api/data/availability/{symbol}`      |
| `trading-data/.../HistoricalDataCatalog.java` | Available | Scan `data/historical/` pour années par symbole                       |
| `scripts/run-tui.sh`           | Available | Lance TUI ; option `-c` pour control plane                            |
| Session utilisateur (transcript) | Partial   | Erreur JSON `Endpoint` si control plane obsolète ; liste stratégies OK |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | Menu racine au démarrage (Backtest / List / Quit) | High | Open | Non implémenté |
| 2 | Parcours actif-first vs strategy-first | High | Open | Wizard actuel : strategy → symbol |
| 3 | Affichage liste années (pas seulement `2006-2025`) | Medium | Open | API renvoie `years[]` ; TUI n’affiche qu’un résumé |
| 4 | JLine Completer / sélection fléchée | Medium | Open | `LineReaderBuilder` sans completer aujourd’hui |
| 5 | Vérifier `dataCatalog` au connect | High | Done | `run-tui.sh` + health JSON |
| 6 | Filtrer stratégies par symbole choisi | Medium | Open | Réduire liste 20+ entrées |

## Timeline of Events

| Time     | Event | Source | Confidence |
| -------- | ----- | ------ | ---------- |
| Session  | `/backtest` liste 20 stratégies puis prompt | Transcript utilisateur | Confirmed |
| Session  | Erreur après choix stratégie 8 : JSON `Endpoint` | Transcript | Confirmed |
| 2026-05-31 | Wizard + API data ajoutés dans codebase | Git / lecture code | Confirmed |
| 2026-05-31 | Correctif affichage liste avant prompt | `TuiInteractiveBacktest.say()` + `liveOutput` | Confirmed |

## Confirmed Findings

### Finding 1 : Le TUI est un client slash-command, pas un menu applicatif

**Evidence :** `trading-tui/src/main/java/com/martinfou/trading/tui/TuiCommandHandler.java:39-41`

**Detail :** Toute entrée sans `/` renvoie « Commands start with /. Type /help ». Aucun menu principal au lancement (`TradingTuiMain` affiche seulement une ligne d’aide).

### Finding 2 : Un wizard backtest interactif existe mais est secondaire

**Evidence :** `trading-tui/src/main/java/com/martinfou/trading/tui/TuiCommandHandler.java:121-127`, `TuiInteractiveBacktest.java:20-130`

**Detail :** `/backtest` sans arguments déclenche strategy → symbol → data options. Listes numérotées ; pas de navigation fléchée ; 20 stratégies d’un coup.

### Finding 3 : Les plages de dates dépendent du control plane à jour

**Evidence :** `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:146-150` ; erreur utilisateur « Endpoint GET /api/data/symbols not found »

**Detail :** Sans `/api/data/*`, le wizard dégrade (sample 500) et n’affiche pas les années locales. **Deduced** : instance control plane non reconstruite après ajout API.

### Finding 4 : L’API expose les années mais le TUI ne les liste pas ligne par ligne

**Evidence :** `HistoricalDataCatalog.toMap()` inclut `years`, `suggestedRange`, `gaps` ; `TuiInteractiveBacktest.describeAvailability()` :37-203 — une seule ligne résumé.

### Finding 5 : Le rapport post-backtest est aligné CLI si le run expose `result` complet

**Evidence :** `TuiBacktestReport.format()` ; `BacktestResultPayload` dans `RunManager.markCompleted`

## Deduced Conclusions

### Deduction 1 : L’écart « pas convivial » est surtout découverte + ordre des étapes + richesse du menu dates

**Based on :** Findings 1, 2, 4

**Reasoning :** L’utilisateur doit connaître `/backtest` ; le parcours commence par la stratégie alors qu’il demande un actif ; les dates disponibles ne sont pas présentées comme menu explicite (années listées).

**Conclusion :** « User-friendly » nécessite menu racine + option actif-first + affichage structuré des années — pas seulement corriger le wizard actuel.

## Hypothesized Paths

### Hypothesis 1 : Menu racine + sous-menu « Run backtest » suffit

**Status :** Open

**Theory :** Remplacer l’aide textuelle par un menu numéroté au démarrage et après chaque commande réduit la friction sans changer l’architecture HTTP.

**Would confirm :** Prototype avec `1) Backtest 2) List strategies 3) Help 4) Quit` accepté par l’utilisateur.

**Would refute :** Utilisateur exige toujours les slash commands ou un GUI.

### Hypothesis 2 : Parcours actif → stratégie filtrée améliore l’objectif « backtest sur un actif »

**Status :** Open

**Theory :** Choisir EUR_USD d’abord, puis ne montrer que les stratégies dont `defaultSymbol` ou compatibilité catalogue = EUR_USD.

**Would confirm :** Filtrage via `StrategyCatalog.entries()` + symbole choisi.

**Would refute :** Toutes les stratégies acceptent tout symbole à l’exécution (vrai aujourd’hui — filtrage serait UX-only).

### Hypothesis 3 : Control plane obsolète est la cause principale des échecs perçus

**Status :** Confirmed (pour la session rapportée)

**Theory :** JSON parse error = 404 texte sur `/api/data/symbols`.

**Resolution :** Erreur reproduite ; correctif client + graceful fallback ; besoin restart documenté.

## Missing Evidence

| Gap | Impact | How to Obtain |
| --- | ------ | ------------- |
| Test utilisateur post-rebuild | Valider perception « convivial » | Martin relance `./scripts/run-tui.sh -c` puis `/backtest` |
| Préférence actif-first vs strategy-first | Ordonnancement menu | Question directe à l’utilisateur |

## Source Code Trace

| Element | Detail |
| ------- | ------ |
| Error origin (session) | `ControlPlaneClient.parseResponse` — body non-JSON sur 404 |
| Trigger | `TuiInteractiveBacktest` après choix stratégie → `listDataSymbols()` |
| Condition | Control plane sans route `/api/data/symbols` |
| Related files | `ControlPlaneServer`, `DataAvailabilityService`, `HistoricalDataCatalog`, `run-tui.sh` |

## Conclusion

**Confidence :** Medium

**Confirmed :** Le dépôt contient déjà un wizard `/backtest` et une API de disponibilité des données ; le TUI n’est pas encore un « menu applicatif » convivial centré actif + plages de dates détaillées.

**Hypothesized :** L’expérience cible = menu racine JLine + sous-flux backtest (actif → stratégie filtrée → menu années issu de `years[]`) + contrôle de version control plane (`dataCatalog: true`).

## Recommended Next Steps

### Fix direction

1. **Menu racine** dans `TradingTuiMain` ou `TuiMainMenu` : boucle numérotée (Backtest, List, Status, Quit).
2. **Refactor wizard** : mode `asset-first` ; après symbole, appeler `/api/data/availability/{symbol}` et afficher `years` en liste numérotée + option « full range ».
3. **JLine** : `Completer` sur stratégies/symboles ; optionnel `Widgets` pour list selection.
4. **Ops** : au connect, si `health.dataCatalog != true`, avertissement unique en français/anglais.

### Diagnostic

```bash
curl -s http://localhost:8080/api/health | grep dataCatalog
curl -s http://localhost:8080/api/data/availability/EUR_USD
```

## Reproduction Plan

1. `./scripts/run-tui.sh --with-control-plane`
2. `tui> /backtest` — vérifier liste stratégies **avant** prompt, puis symboles, puis résumé dates.
3. Choisir option 1 (full range) — rapport `BACKTEST RESULT` à la fin.
4. Répéter avec control plane arrêté / ancien jar : observer fallback sample + message API.

## Follow-up: 2026-05-31

### Symptôme rapporté

Saisie d’un **numéro** à l’invite « Strategy [# or id] » → « crash ».

### Finding 6 (Confirmed)

**Evidence :** `TuiSelection.java:32` (ancienne version) retournait `trimmed` pour tout texte non numérique sans vérifier l’appartenance à la liste ; `TuiInteractiveBacktest.java:49` `orElseThrow()` → **NoSuchElementException** non capturée par `TuiCommandHandler` → stack trace / sortie du TUI.

**Evidence :** `TuiCommandHandler.java:62-70` ne capturait que `IOException` et `ControlPlaneException`, pas `IllegalArgumentException`.

### Finding 7 (Confirmed — session « 8 »)

Numéro **valide** (ex. `8` = InsideBarBreakout) ne crash pas la sélection ; l’échec suivant était **HTTP 404** sur `/api/data/symbols` (control plane obsolète), affiché comme `Request failed: … Endpoint` — perçu comme crash.

### Correctifs appliqués

- `TuiPrompts.choose()` : re-prompt sur entrée invalide, message `✗ Choice N out of range (1–20)`.
- `TuiSelection.resolve()` : plus de free-text silencieux ; id inconnu = erreur explicite.
- `TuiCommandHandler` : capture `IllegalArgumentException`.

### Hypothesis 3

**Status :** Confirmed

**Resolution :** Deux causes distinctes : (A) entrée hors plage / id typo → exception non gérée ; (B) numéro OK puis API data manquante.

