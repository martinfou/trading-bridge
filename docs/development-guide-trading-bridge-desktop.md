# Guide de Développement — Application Bureau (`trading-bridge-desktop`)

**Date de mise à jour :** 2026-11-05  
**Portée :** Application Electron 33 + Vue 3.5 + TypeScript 5.7 (`desktop/`)

---

## 1. Démarrage en Mode Développement

```bash
# Depuis la racine (vérifie/compile le JAR Java puis lance Electron)
./start-desktop.sh

# Ou depuis le dossier desktop/
cd desktop
npm install
npm run electron:dev
```
* Si vous développez déjà avec un serveur Control Plane Java tournant séparément dans votre IDE, vous pouvez lancer `NO_CONTROL_PLANE=1 npm run electron:dev` pour empêcher Electron de démarrer un second processus JVM.

---

## 2. Structure et Conventions Front-End (`desktop/src/`)

1. **Typage Strict (`src/types/control-plane.ts`)** :
   * Tout nouvel appel REST ou message WebSocket doit être typé dans `src/types/control-plane.ts` et exposé via `src/composables/useControlPlane.ts`.
2. **Gestion des Coûts & Paniers Multi-Actifs** :
   * Utilisez toujours `useCostPresets.ts` (`resolveDefaultPresetForSymbol`) lors d'un changement de symbole pour appliquer automatiquement les commissions, le slippage et la taille de contrat adaptés (`CME_MICRO_FUTURES`, `OANDA_FOREX_CORE`, `IBKR_EQUITY_TIERED`, etc.).
   * Utilisez `isCompatibleBasket` (`useStrategyCatalog.ts`) avant de lancer un run multi-symboles afin d'interdire le mélange de contrats à terme entiers (`1.0` contrat) et de lots fractionnaires Forex (`0.01` lot).
3. **Graphiques TradingView Lightweight Charts (`v5.0.0`)** :
   * Dans `TradeChart.vue`, conservez `getCleanBars()` pour dédupliquer les horodatages lors des rollovers de contrats à terme (`MGC`, `MES`) et respectez la précision décimale par classe d'actifs (`2`, `3` ou `5` décimales).
   * Dans `EquityChart.vue`, renseignez `oosSplitIndex` ou `oosSplitRatio` dès qu'un run comporte une séparation In-Sample / Out-of-Sample afin d'afficher la double zone `AreaSeries` (bleu IS / émeraude OOS).

---

## 3. Build, Vérification TypeScript & Packaging Multiplateforme

```bash
cd desktop

# Vérification stricte vue-tsc + bundle Vite
npm run build

# Générer le JRE minimal embarqué (jdeps + jlink) avant packaging de distribution
./scripts/build-jre.sh

# Construire l'installateur natif selon l'OS cible
npm run package:mac     # Génère release/*.dmg (x64 & arm64)
npm run package:linux   # Génère AppImage, .deb, .pacman
npm run package:win     # Génère l'installateur NSIS .exe
```

