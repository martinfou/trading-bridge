# Epics & Stories — Deploy macOS Desktop App

> Phase 3 — Solutioning (Quick Flow)
> Projet : Trading Bridge — Desktop Backtest Runner

---

## Epic 1 — macOS Deployment Pipeline

**Objectif** : Signer, notariser, et distribuer le desktop app Mac via GitHub Releases, avec auto-update.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|----------------|------------|--------|
| US-1 | Trader Mac | Télécharger le .dmg et l'ouvrir sans alerte Gatekeeper | Pas de friction à l'install |
| US-2 | Trader Mac | Recevoir les mises à jour automatiquement | Toujours à jour sans effort |
| US-3 | Développeur | Publier une release avec `git tag v1.2.3 && git push --tags` | Workflow simple et reproductible |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| **1.1** | Setup Apple Developer secrets GitHub | XS | Docs + checklist : exporter .p12, créer app password, configurer GH secrets `APPLE_DEVELOPER_ID`, `APPLE_ID`, `APPLE_ID_PASS`, `APPLE_TEAM_ID`. **Pas de code** — juste documentation et setup manuel. | — |
| **1.2** | electron-builder signing + notarization config | S | Ajouter `afterSign` hook avec `electron-notarize`, config `mac: { identity, hardenedRuntime, gatekeeperAssess: false }` dans `package.json`. Le signing est automatique quand le cert est dans le keychain CI. | 1.1 |
| **1.3** | GitHub Release workflow (on tag push) | M | Nouveau workflow `.github/workflows/release.yml` : déclenché sur `push: tags: 'v*'`. Build fat JAR + desktop, signe, notarise, upload le DMG vers GitHub Release, crée le CHANGELOG depuis les commits. | 1.2 |
| **1.4** | Auto-update via electron-updater | M | Installer `electron-updater`, configurer `publish: { provider: github }` dans electron-builder, ajouter la logique de check d'update dans `main.ts` (silent check au démarrage, menu "Check for updates"). Publie l'appcast sur GitHub Releases automatiquement. | 1.3 |

**Total Epic 1** : ~1.5 jours

## Build order

| Phase | Stories | Dépend de |
|-------|---------|-----------|
| **4.1** | 1.1 Setup secrets (manuel) | — |
| **4.2** | 1.2 Signing config | 1.1 |
| **4.3** | 1.3 Release workflow | 1.2 |
| **4.4** | 1.4 Auto-update | 1.3 |
