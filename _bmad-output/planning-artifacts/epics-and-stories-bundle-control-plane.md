# Epics & Stories — Bundle Java Control Plane in Desktop App

> Phase 3 — Solutioning (Quick Flow)
> Projet : Trading Bridge — Desktop Backtest Runner

---

## Epic 1 — Bundle Java Control Plane

**Objectif** : Le desktop app embarque le JRE et le fat JAR, lance le control plane automatiquement.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|----------------|------------|--------|
| US-1 | Trader | Lancer l'app et que le backend Java démarre tout seul | Zéro config, ça marche out of the box |
| US-2 | Trader | Que l'app me montre quand le backend est prêt | Pas de guesswork |
| US-3 | Trader | Que le backend s'arrête proprement quand je quitte l'app | Pas de zombie process |

### Coding Stories

| # | Story | Effort | Dépend de |
|---|-------|--------|-----------|
| **1.1** Fat JAR via maven-shade-plugin | XS | — |
| **1.2** Script `scripts/build-jre.sh` (jlink wrapper) | S | 1.1 |
| **1.3** Electron main process — JVM lifecycle (spawn, wait, kill) | M | 1.1, 1.2 |
| **1.4** electron-builder extraResources config | XS | 1.1, 1.2 |
| **1.5** CI — fat JAR artifact + jlink dans desktop matrix | S | 1.1, 1.2, 1.4 |

**Total Epic 1** : ~1 jour
