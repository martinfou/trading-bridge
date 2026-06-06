# Bug : CI — GitHub Actions Node.js 20 dépréciation

> **Track :** Quick Flow
> **Date :** 2026-06-06
> **Signal :** 4 warnings « Node.js 20 actions are deprecated » dans le CI run #25

---

## Problème

Toutes les actions GitHub Actions du workflow `ci.yml` utilisent les versions `@v4` qui tournent sur **Node.js 20**. Depuis l'annonce de GitHub :

- **16 juin 2026** — Node.js 24 devient le moteur par défaut
- **16 septembre 2026** — Node.js 20 sera retiré des runners

Les actions concernées :

| Action | Version actuelle | Version cible | Présence |
|--------|-----------------|---------------|----------|
| `actions/checkout` | `@v4` | `@v5` | Job `java` + `desktop` |
| `actions/setup-java` | `@v4` | `@v5` | Job `java` + `desktop` |
| `actions/upload-artifact` | `@v4` | `@v5` | Job `java` + `desktop` |
| `actions/setup-node` | `@v4` | `@v5` | Job `desktop` |
| `actions/download-artifact` | `@v4` | `@v5` | Job `desktop` |

**Risque :** À partir du 16 juin 2026, les workflows pourraient échouer si GitHub bascule au Node.js 24 par défaut et que les actions @v4 ne sont plus compatibles.

## Solution

Mettre à jour les 5 actions de `@v4` → `@v5` dans `.github/workflows/ci.yml`.

## Changements

- `actions/checkout@v4` → `actions/checkout@v5`
- `actions/setup-java@v4` → `actions/setup-java@v5`
- `actions/upload-artifact@v4` → `actions/upload-artifact@v5`
- `actions/setup-node@v4` → `actions/setup-node@v5`
- `actions/download-artifact@v4` → `actions/download-artifact@v5`

Les chemins, `with:` params et la logique ne changent pas — c'est un remplacement 1:1.

## Effort

XS — 6 remplacements, 0 changement logique.

## Coding Stories

| # | Story | Effort | Description |
|---|-------|--------|-------------|
| 1 | Upgrade GH actions @v4 → @v5 | XS | Remplacer les 5 actions v4 par v5 dans ci.yml |
| 2 | Vérifier CI run | XS | Pousser et confirmer que le run CI passe sans warnings |

## Build Order

1. Story 1 — edit ci.yml
2. Story 2 — git commit + push + vérifier CI
