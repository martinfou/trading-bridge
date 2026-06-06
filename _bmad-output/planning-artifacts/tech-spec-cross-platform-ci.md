# Tech Spec — Cross-Platform CI for Desktop Backtest Runner

> **Track** : Quick Flow (1-3 stories)
> **Date** : 2026-06-04
> **PO** : Martin Fournier

---

## Problem

Le CI desktop actuel ne build que sur `ubuntu-latest`, produisant uniquement un `*.AppImage` Linux. Les utilisateurs Windows et macOS ne peuvent pas obtenir de binaire natif — ils doivent cloner le repo et build eux-mêmes.

Le package.json a déjà toute la config electron-builder pour les 3 plateformes (Linux : AppImage/deb/pacman, macOS : DMG x64+arm64, Windows : NSIS x64). Seule la CI est à compléter.

## Solution

Utiliser une **GitHub Actions matrix** par OS runner :

| Runner | Build target | Artifact |
|--------|-------------|----------|
| `ubuntu-latest` | `electron-builder --linux` | AppImage + deb + pacman |
| `macos-latest` | `electron-builder --mac` | DMG (x64 + arm64) |
| `windows-latest` | `electron-builder --win` | NSIS installer |

Chaque runner build son OS natif. `electron-builder` gère les targets par OS sans config supplémentaire (architecture détectée automatiquement).

### Constraints

- macOS runner peut build ARM (Apple Silicon) + Intel — pas de license Apple Developer pour signing en dev
- Windows runner nécessite NSIS installé (default on windows-latest via chocolatey)
- `node ci` + `npm run build` avant `electron-builder` (identique à Linux actuel)

### Not doing

- ❌ AppImage crossbuild from macOS/Windows — each OS builds its own
- ❌ Code signing (macOS requires Apple Developer Program)
- ❌ Auto-update (v1 — manual download only)
- ❌ Windows code sign (requires EV certificate)
