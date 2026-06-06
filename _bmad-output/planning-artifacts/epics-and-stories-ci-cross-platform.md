# Epics & Stories — Cross-Platform CI

> Phase 3 — Solutioning (Quick Flow)
> Projet : Trading Bridge — Desktop Backtest Runner
> Track : Quick Flow (1 epic, 2 stories)

---

## Epic 1 — Cross-Platform CI Matrix

**Objectif** : Remplacer le job desktop unique (ubuntu-latest / Linux AppImage) par une matrix OS qui build Linux, macOS et Windows en parallèle.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|----------------|------------|--------|
| US-1 | Trader Windows | Télécharger un `.exe` installer du desktop | Utiliser sans installer Node.js |
| US-2 | Trader macOS | Télécharger un `.dmg` signé/unsigned | Lancer l'app sur Mac Intel + Apple Silicon |
| US-3 | Développeur | Voir les 3 builds passer dans un seul workflow | CI rapide, logs centralisés |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 1.1 | Matrix CI avec 3 runners | S | Remplacer job `desktop` unique par matrix `os: [ubuntu-latest, macos-latest, windows-latest]` + per-os electron-builder target. Linux → `--linux`, macOS → `--mac`, Windows → `--win`. Upload artifact avec nom distinct par OS. | — |
| 1.2 | Configuration Windows (NSIS pre-install) | XS | `windows-latest` inclut déjà NSIS, mais wrapper `npm run build` peut nécessiter `cross-env` ou win-compatible path. Vérifier que `npm run build` + `electron-builder --win` passent sans erreur. | 1.1 |

**Total Epic 1** : ~0.5 jour
