# Tech Spec — Deploy macOS Desktop App

> **Track** : Quick Flow (1 epic, 4 stories)
> **Date** : 2026-06-04
> **PO** : Martin Fournier

---

## 1. Problem

Le desktop app build déjà un `.dmg` sur `macos-latest` via CI, mais :
- ❌ L'app n'est pas **signée** → Gatekeeper bloque l'ouverture sur Mac des utilisateurs
- ❌ L'app n'est pas **notarisée** → Apple refuse le lancement sans confirmation explicite
- ❌ Pas de **release workflow** → pas de tag → pas de version propre
- ❌ Pas d'**auto-update** → l'utilisateur doit rebuild manuellement pour chaque mise à jour

## 2. Vision

Un utilisateur Mac peut :
1. Télécharger le `.dmg` depuis GitHub Releases
2. Ouvrir l'app sans alerte Gatekeeper (signée + notariée)
3. Recevoir les mises à jour automatiquement (auto-update)

## 3. Prérequis (Apple Developer Program)

| Prérequis | Statut | Action nécessaire |
|-----------|--------|-------------------|
| Apple Developer account ($99/an) | ❌ | Créer ou utiliser compte existant |
| Developer ID Application certificate | ❌ | Générer dans Xcode → Keychain → exporter .p12 |
| Developer ID Installer certificate | ❌ | Idem (pour le package .pkg optionnel) |
| App-specific password pour notarization | ❌ | Générer sur appleid.apple.com |
| Team ID (10 chars) | ❌ | Dans Apple Developer → Membership |

## 4. Non-goals (v1)

- ❌ Mac App Store (MAS) — distribution directe uniquement
- ❌ Windows/macOS auto-update dans v1 (juste macOS pour commencer)
- ❌ Sparkle framework — electron-updater suffit

## 5. Pipeline de déploiement

```
[tag push v*.*.*]
      ↓
  GitHub Actions release.yml
      ↓
  1. Build fat JAR (ubuntu)
  2. Build desktop (macos-latest)
     a. Import signing cert from GH secrets
     b. jlink JRE + assemble resources
     c. npm run build
     d. electron-builder --mac --publish=always
        → signs with Developer ID
        → notarizes via notarytool
        → outputs signed DMG
  3. Upload DMG to GitHub Release
  4. Create/update appcast for auto-update
```

## 6. État actuel vs cible

| Capacité | Maintenant | Objectif |
|----------|-----------|----------|
| DMG build | ✅ sur macos-latest | ✅ inchangé |
| Code sign | ❌ | ✅ Developer ID + notarization |
| Notarization | ❌ | ✅ via notarytool |
| GitHub Release | ❌ sur push tag | ✅ automatic |
| Auto-update | ❌ | ✅ electron-updater + GitHub Releases |
| Version management | ❌ | ✅ from package.json → git tag |