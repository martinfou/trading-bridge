# Adversarial Review — Trading Bridge PRD (2026-05-30)

## Preamble

Lecture cynique post-update. Le PRD a gagné en honnêteté (§8d) — ce qui réduit le risque de fraude involontaire, mais expose des incohérences résiduelles entre sections « vision MVP » et « état réel ».

## Findings

- **high** Success metric theater (§7 SM-2, SM-3) — SM-2 peut être coché avec PAPER_STUB ; SM-3 « 100 % promotions LIVE passent par gate » est vacuousement vrai si LIVE est impossible (`RunManager` rejette). Ces métriques ne prouvent pas maturité produit. *Fix:* métriques conditionnelles par phase (MVP-stub vs MVP-broker).

- **medium** MVP scope §6.1 vs §8c — §6.1 liste encore « Import SQ parser + firstSqJforx » in scope alors que §8c dit backlog Epic 2. Soit MVP scope ment, soit §8c sur-classifie le retard. *Fix:* marquer FR-1 « deferred MVP » ou mettre à jour §6.1 out-of-scope.

- **medium** FR-15 drift rules (§4.5) — seuils détaillés pour live/paper alors qu'aucun path broker n'existe. Spec premature : risque de story work sur signaux impossibles à calculer. *Fix:* tag FR-15 consequences « applicable après Epic 4 paper/live ».

- **low** « World class » (§1 Vision) juxtaposed to §8d « pas prop-firm ready » — intellectually honest but marketing-dangerous if excerpted without §8d. *Fix:* one-line qualifier in Vision pointing to §8d for maturity assessment.
