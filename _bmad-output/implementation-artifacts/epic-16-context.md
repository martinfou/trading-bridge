# Epic 16 Context: Exécution broker

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Ce module gère l'intégration avec les courtiers (Broker connectors) pour le Paper Trading et le Live Trading. Il permet d'exécuter des stratégies de trading sur de vrais flux de marché (OANDA dans un premier temps, puis IBKR), de journaliser les événements (fills, ordres, alertes) et d'appliquer des règles de gestion des risques (Kill Switch, pre-trade risk, limites quotidiennes).

## Stories

- Story 16.1: Runner PAPER_STUB labellisé
- Story 16.2: Interface Broker skeleton (prop-shop) — Must-ship
- Story 16.3: Paper trading OANDA demo — Must-ship
- Story 16.4: Gate 30 jours paper avant LIVE — Must-ship
- Story 16.5: Exécution LIVE sur worker local — Must-ship
- Story 16.6: Kill switch et OPERATOR_ACTION — Must-ship
- Story 16.7: Réconciliation broker ↔ journal (prop-shop) — Must-ship
- Story 16.8: Pre-trade risk guards via RiskEngine (prop-shop) — Must-ship
- Story 16.9: Multi-compte prop — BrokerAccount (prop-shop) — Phase 2
- Story 16.10: Connecteur IBKR paper/live (prop-shop) — Phase 2
- Story 16.11: Harness test strategies
- Story 16.12: Fix OANDA position client tag matching
- Story 16.13: Watchdog Weekend Close and Memory Cleanup

## Requirements & Constraints

- Les credentials des courtiers ne doivent jamais être committés ni codés en dur. Ils doivent être chargés via des variables d'environnement ou la configuration locale.
- Le control plane HTTP tourne sur le port 8080.
- Les logs et événements du broker doivent être journalisés de manière idempotente dans l'Event Store SQLite (`events.db`).

## Technical Decisions

- Architecture asynchrone découplée : `RunManager` gère le cycle de vie, les connecteurs implémentent l'interface `Broker`.
- Timezones : UTC en interne partout (`Instant`), conversion à l'affichage uniquement.
