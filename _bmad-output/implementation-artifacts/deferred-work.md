# Deferred Work

## Deferred from: code review of 2-10-utc-timezone-migration (2026-05-18)

- **Horloge UTC injectable** — `TimeConventions.clock()` statique ; tests déterministes sur `Order` / `AutoTrader` reportés hors scope minimal.
- **Lignes CSV ignorées silencieusement** — Comportement pré-existant dans `DataLoader` ; pas introduit par la migration UTC.
- **Tests d’intégration OandaPriceClient** — Smoke test seulement ; tests unitaires parsing candle optionnels.
- **SmaCrossoverStrategy** — Aucun champ temporel ; pas de migration nécessaire pour AC4.
- **BacktestEngine diff minimal** — Import cleanup seulement ; OK avec domaine `Instant`.
- **Preuve AC7 build** — `mvn clean install` à confirmer en local par le développeur.
- **Zone Germany PMI (Berlin vs London)** — Décision 4-C : vérifier l’heure officielle sur le calendrier source avant de changer `Europe/Berlin`.
