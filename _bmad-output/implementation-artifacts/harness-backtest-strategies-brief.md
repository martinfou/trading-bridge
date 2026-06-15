# Fiche produit — Stratégies harness pour validation du moteur de backtest

**Statut:** Implémenté (2026-06-02)  
**Auteur:** Mary (BA) — 2026-06-02  
**Demandeur:** Martin Fournier  
**Langue doc:** Français · **Code / IDs:** anglais  

---

## 1. Objectif

Fournir un **catalogue de stratégies déterministes** dont le **nombre de trades attendu** se déduit sans ambiguïté, pour :

- Détecter les régressions du `BacktestEngine` (file d’ordres, `closeOnly`, flatten de fin, adds same-side).
- Valider le pipeline **TUI / control plane** (`/backtest`, rapports) avec des IDs stables.
- Compléter (sans remplacer) `com.martinfou.trading.backtest.TestStrategies`, réservé aux tests unitaires du module backtest.

**Hors scope:** alpha, SL/TP métier prop, sizing risk-based (`AbstractPropStrategy`).

---

## 2. Principe moteur (référence)

Dans `BacktestEngine`, **`totalTrades` += 1 à chaque `closePosition`** (sortie explicite, SL/TP, ou flatten sur dernière barre).

| Scénario | Trades |
|----------|--------|
| Aucun ordre | 0 |
| 1 entrée, position tenue jusqu’à la fin | 1 (flatten forcé) |
| 1 entrée + 1 sortie avant la fin | 1 round-trip |
| BUY chaque barre sans garde « déjà en position » | Comportement anormal (adds / rafales) — **ne pas** utiliser comme baseline |

Les harness doivent **émettre au plus un ordre par barre** et **vider `pending` après lecture** (pattern `ScriptedStrategy` dans `TestStrategies`).

---

## 3. Architecture proposée

| Élément | Emplacement |
|---------|-------------|
| Package | `com.martinfou.trading.strategies.harness` |
| Registry | `HarnessStrategyCatalog` (miroir de `PropStrategyCatalog`) |
| Catalogue unifié | `StrategyCatalog.Family.HARNESS` + bootstrap dans `StrategyCatalog.bootstrapBuiltIn()` |
| Base commune | `HarnessScriptedStrategy` — copie minimale du pattern `TestStrategies.ScriptedStrategy` (pas de dépendance `trading-backtest` → `trading-strategies`) |
| Calendrier UTC | Réutiliser `PropSessions.dayKey`, `weekKey`, `hour`, `isMonday`, `isFriday` |
| Quantité fixe | `FixedQuantityStrategy` déjà appliqué par `StrategyCatalog.create(id, sym, quantityUnits)` |
| Tests | `trading-strategies/src/test/.../harness/*Test.java` + optionnel `trading-backtest` si réutilisation de barres synthétiques |

**Ne pas** étendre `AbstractPropStrategy` (plafond 3 trades/jour, simulation SL/TP interne, filtres d’entrée).

---

## 4. Catalogue des stratégies (MVP)

Quantité par défaut dans les stratégies harness : **10_000** unités (aligné sur `TestStrategies`), écrasable via run config / TUI.

### 4.1 Tier 1 — Sanity (implémenter en premier)

| ID catalogue | Classe | Règle | Trades attendus |
|--------------|--------|-------|-----------------|
| `Harness_NeverTrade` | `NeverTradeStrategy` | `onBar` no-op, `getPendingOrders()` vide | **0** |
| `Harness_BuyOnceHold` | `BuyOnceHoldStrategy` | 1× `MARKET BUY` au premier bar du symbole, rien après | **1** (flatten fin) |
| `Harness_BuyThenCloseNextBar` | `BuyThenCloseNextBarStrategy` | Bar paire: BUY ; bar impaire: `SELL` `closeOnly()` (alternance) | `ceil(barCount / 2)` |
| `Harness_LimitNeverFills` | `LimitNeverFillsStrategy` | 1× `LIMIT BUY` à `lowMin - 1` sur toute la série (calculé au reset ou 1er bar) | **0** |

### 4.2 Tier 2 — Calendrier (besoins utilisateur)

Paramètres constructeur (ou constantes documentées) : heures UTC inclusives.

| ID catalogue | Classe | Règle | Formule attendue |
|--------------|--------|-------|------------------|
| `Harness_DailyRoundTrip` | `DailyRoundTripStrategy` | État: FLAT → IN. **Entrée:** premier bar du jour où `hour == openHour` (défaut **8**). **Sortie:** dernier bar du même `dayKey` où `hour == closeHour` (défaut **21**). Toujours LONG. Un seul cycle par `dayKey`. | `trades ≈ countDistinct(dayKey)` où au moins une barre a `hour >= openHour` et une a `hour <= closeHour` |
| `Harness_WeeklyRoundTrip` | `WeeklyRoundTripStrategy` | **Entrée:** premier bar du lundi (`isMonday`) avec position flat. **Sortie:** dernier bar du vendredi (`isFriday`) du même `weekKey`. LONG fixe. | `trades ≈ countDistinct(weekKey)` avec barres lun–ven présentes |

**Marge d’acceptation tests:** `|actual - expected| ≤ 2` (premier/dernier jour ou semaine tronqués dans le CSV).

### 4.3 Tier 3 — Régression « abus » (optionnel MVP+1)

| ID catalogue | Classe | Règle | Usage |
|--------------|--------|-------|--------|
| `Harness_FlipEveryBar` | `FlipEveryBarStrategy` | Bar paire: BUY si flat ; bar impaire: SELL si long — **sans** garde anti-réentree | Plafond documenté: `trades <= 2 * barCount` ; alerte si dépassement |
| `Harness_ExactlyNRoundTrips` | `ExactlyNRoundTripsStrategy` | Param `n` : BUY bar `2i`, close bar `2i+1` pour `i in 0..n-1` | **n** |

---

## 5. Spécification comportementale (Daily / Weekly)

### 5.1 `Harness_DailyRoundTrip`

```
openHour  = 8   // UTC, première barre du jour à cette heure déclenche l'entrée
closeHour = 21  // UTC, dernière barre du jour à cette heure déclenche la sortie
side      = BUY / LONG only
```

**Machine d’état:**

1. `FLAT` — si `dayKey` change → reset flags du jour.
2. À la **première** barre du jour avec `hour == openHour` et `FLAT` → émettre `MARKET BUY`, passer `IN`.
3. Tant que `IN` — ignorer les autres barres du jour sauf `hour == closeHour` → émettre `MARKET SELL` + `closeOnly()`, passer `FLAT`.
4. Si le jour se termine encore `IN` (pas de barre à `closeHour`) → **ne pas** sortir ce jour (position reportée) **OU** sortir sur dernière barre du `dayKey` — **choix implémentation: dernière barre du dayKey** pour éviter le carry multi-jours (recommandé pour prévisibilité).

**Recommandation:** sortie sur **dernière barre du `dayKey`** si `closeHour` jamais vue → garantit ≈ 1 trade/jour sur H1 complet.

### 5.2 `Harness_WeeklyRoundTrip`

```
Entrée  : premier bar lundi (weekKey W), FLAT
Sortie  : dernier bar vendredi du même W ; si pas de barre ven, sortie dernier bar vendredi disponible dans W
```

Ne pas ré-entrer mardi–jeudi. Utiliser `PropSessions.weekKey(bar)`.

---

## 6. Critères d’acceptation (Given / When / Then)

### AC-1 — NeverTrade

- **Given** ≥ 100 barres synthétiques ou CSV EUR_USD H1  
- **When** backtest `Harness_NeverTrade`  
- **Then** `result.totalTrades() == 0` et equity == capital initial (coûts à 0)

### AC-2 — BuyOnceHold

- **Then** `totalTrades() == 1`

### AC-3 — BuyThenCloseNextBar

- **Given** ≥ 2 barres  
- **Then** `totalTrades() == ceil(barCount / 2.0)`

### AC-4 — DailyRoundTrip (synthétique)

- **Given** 48 barres H1 artificielles = 2 jours × 24h, heures 0..23, symbole fixe  
- **When** openHour=8, closeHour=21, sortie fallback = dernière barre du jour  
- **Then** `totalTrades() == 2` (±0)

### AC-5 — WeeklyRoundTrip (synthétique)

- **Given** 5 barres daily lun→ven même `weekKey`  
- **Then** `totalTrades() == 1`

### AC-6 — Catalogue

- `StrategyCatalog.contains("Harness_DailyRoundTrip")`  
- `StrategyCatalog.family(id) == HARNESS`  
- `RunBacktest --list` et TUI listent les IDs harness

### AC-7 — LimitNeverFills

- **Then** `totalTrades() == 0`

---

## 7. Plan de tests

| Test | Module | Données |
|------|--------|---------|
| `NeverTradeStrategyTest` | trading-strategies | 10 barres synthétiques |
| `DailyRoundTripStrategyTest` | trading-strategies | `List<Bar>` 2 jours UTC construits en test |
| `WeeklyRoundTripStrategyTest` | trading-strategies | 5 daily bars |
| `HarnessStrategyCatalogTest` | trading-strategies | tous les IDs enregistrés |
| `HarnessBacktestIntegrationTest` | trading-backtest **ou** strategies | enchaîne `BacktestEngine` + harness (évite régression moteur) |

**Golden (optionnel):** si `data/historical/EUR_USD_H1_*.csv` présent, assert `Harness_DailyRoundTrip` avec borne `expected ± 5%` sur `totalTrades` vs `TradingDaysEstimator` — sinon `@DisabledIf`.

---

## 8. Tâches d’implémentation (ordre)

1. Ajouter `Family.HARNESS` à `StrategyCatalog`.
2. Créer `HarnessScriptedStrategy` + `HarnessStrategyCatalog`.
3. Implémenter Tier 1 (4 stratégies) + tests AC-1..3, AC-7.
4. Implémenter Tier 2 Daily + Weekly + tests AC-4..5.
5. Enregistrer dans `PropStrategyCatalog` **non** — uniquement `HarnessStrategyCatalog`.
6. Documenter dans `AGENTS.md` une ligne « harness backtest probes » (optionnel, 1 phrase).
7. Tier 3 si temps.

**Estimation:** 1 story · ~4–6 h dev.

---

## 9. Exemple d’utilisation

```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass=com.martinfou.trading.examples.RunBacktest \
  -Dexec.args="Harness_DailyRoundTrip EUR_USD 2020"

# TUI (control plane requis)
/backtest Harness_NeverTrade EUR_USD catalog:EUR_USD_H1
```

---

## 10. Risques et mitigations

| Risque | Mitigation |
|--------|------------|
| Carry position multi-jours (Daily) | Sortie forcée dernière barre du `dayKey` |
| Doublon IDs avec prop/SQ | Préfixe obligatoire `Harness_` |
| `AbstractPropStrategy` importé par erreur | Revue code + package `harness` isolé |
| Tests flaky sur CSV réel | Tests synthétiques obligatoires ; golden en bonus |

---

## 11. Traçabilité

- Complète: investigation TUI backtest (`_bmad-output/implementation-artifacts/investigations/tui-friendly-backtest-menu-investigation.md`)
- Référence existante: `trading-backtest/.../TestStrategies.java`
- Epic suggéré: **Epic 12 consolidation** ou nouveau story `12-x-harness-strategies` dans `sprint-status.yaml` (PM à trancher)

---

*Fin de fiche — prête pour `bmad-dev-story` ou handoff Amelia.*
