# Story 28.2: Interface Graphique Desktop (Timeline, Stabilité & Multi-Actifs)

Status: backlog

## Story

En tant que trader,
Je veux visualiser graphiquement les résultats du Walk-Forward avec indicateur de classe d'actif (Forex, Futures Small/Mid Cap, Actions) sous forme de timeline interactive et de grille de stabilité,
Afin de pouvoir juger visuellement de la robustesse de ma stratégie sur les différents plis et marchés.

## Acceptance Criteria

1. **Given** une exécution WFA terminée sur un actif quelconque (ex: `M2K` Small Cap Futures, `EMD` Mid Cap, `MES`, ou `IWM` ETF)
2. **When** je consulte la vue détaillée du run WFA sur l'IHM Desktop (onglet "Walk-Forward" dans `InspectView.vue` et route `/wfa`)
3. **Then** le header affiche un badge d'actif distinctif :
   - ⚡ `FUTURES` (avec symbole `M2K`/`MES`/`EMD` et multiplicateur de point)
   - 📈 `EQUITY` (avec symbole d'action/ETF et devise USD)
   - 🏷️ `FOREX` (avec paire de devises)
4. **And** le système affiche une frise chronologique (`WfaTimeline.vue`) représentant les plis :
   - Segment Out-of-Sample (OOS) coloré en vert si Sharpe $> 0$, en rouge si $\le 0$.
   - Période In-Sample (IS) correspondante en gris clair.
5. **And** au survol d'un pli, une carte tooltip détaillée affiche :
   - Dates début/fin In-Sample & Out-of-Sample
   - Sharpe IS vs Sharpe OOS
   - Nombre de trades OOS, Win Rate, et PnL net calculé avec le multiplicateur d'actif
   - Ratio de dégradation de Sharpe ($\text{Degradation} = \frac{\text{Sharpe OOS}}{\text{Sharpe IS}}$)
6. **And** le système affiche un tableau comparatif (`WfaParameterStability.vue`) listant pour chaque pli les valeurs des paramètres optimaux sélectionnés avec heatmap de dispersion.
7. **And** le chargement et le rendu initial des graphiques s'effectuent en moins de 500 ms (SM-2).

## Tasks / Subtasks

- [ ] **Task 1: Badges Multi-Actifs et En-tête WFA (`desktop/`)** (AC: 1, 3)
  - [ ] Ajouter les badges visuels et métadonnées d'instrument (multiplicateur, tick size).
- [ ] **Task 2: Composant Vue 3 `WfaTimeline.vue` (`desktop/`)** (AC: 4, 5)
  - [ ] Développer la frise temporelle réactive avec code couleur et tooltips enrichis.
- [ ] **Task 3: Composant `WfaParameterStability.vue` (`desktop/`)** (AC: 6)
  - [ ] Heatmap de stabilité des paramètres avec indicateur de dérive.
- [ ] **Task 4: Intégration et performance (`desktop/`)** (AC: 2, 7)
  - [ ] Connecter à l'API et valider le temps de rendu < 500ms.
