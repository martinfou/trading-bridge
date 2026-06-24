# Story 27.4 : Affichage du PnL flottant, numéro de ticket et métadonnées des transactions sous les positions ouvertes

Status: ready-for-dev

## Story

En tant que trader quantitatif,
Je veux visualiser en temps réel le gain/perte (PnL) flottant en valeur et en pourcentage de mes positions ouvertes, ainsi que le numéro de ticket (ID de trade broker) et les métadonnées (tag client) associées,
afin de pouvoir surveiller avec précision le statut financier et la provenance de mes positions actives depuis la table de surveillance.

## Critères d'acceptation

1. **Given** une stratégie active possédant des positions ouvertes (qu'elles soient réelles OANDA/IBKR ou reconstruites à partir du journal de transactions)
   **When** je consulte l'onglet "Open Positions" (Positions ouvertes) dans la section de surveillance (LiveTradingView)
   **Then** la table de surveillance affiche les colonnes supplémentaires :
     - **Ticket** : Affiche l'identifiant unique du trade fourni par le broker (`brokerTradeId`), ou vide si indisponible.
     - **PnL ($)** : Affiche le profit ou perte non réalisé en valeur absolue de la devise du compte.
     - **PnL (%)** : Affiche la variation en pourcentage par rapport au prix d'entrée.
     - **Metadata** : Affiche le tag client de transaction (`clientTag` / extension client) pour tracer l'origine de l'ordre.

2. **Given** une position affichée dans la table
   **When** le prix du marché varie (nouveaux ticks bid/ask reçus)
   **Then** les colonnes de PnL se mettent à jour instantanément en utilisant le Bid actuel pour une position BUY (long) et l'Ask actuel pour une position SELL (short).
   - Les valeurs de PnL positives s'affichent en vert vif avec un symbole `+` (ex: `+$150.00 (+1.25%)`).
   - Les valeurs de PnL négatives s'affichent en rouge vif avec un symbole `-` (ex: `-$75.00 (-0.63%)`).

3. **Given** un run avec connexion broker active (OANDA ou IBKR)
   **When** le broker renvoie des positions avec un ID de trade (`brokerTradeId`) et un tag client (`clientTag`)
   **Then** la colonne "Ticket" affiche le `brokerTradeId` et la colonne "Metadata" affiche le `clientTag` retournés par le broker.

4. **Given** un run sans connexion broker active (repli sur le journal local des transactions)
   **When** la position est reconstruite à partir des fills du journal via `JournalPositions.fromFills(...)`
   **Then** la colonne "Ticket" reste vide ou affiche `—`, et la table affiche la moyenne pondérée du prix d'entrée calculée dans `JournalPositions` ainsi que le PnL calculé sur cette base.

## Tâches / Sous-tâches

- [ ] **Task 1: Extension du modèle de données Java (Backend - Core & Data)**
  - [ ] Dans [Position.java](file:///Volumes/T7/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/Position.java), ajouter une propriété `unrealizedPnl` (double), un getter `unrealizedPnl()` et un helper builder `withUnrealizedPnl(double pnl)`.
  - [ ] Dans [OandaPositionSnapshot.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/OandaPositionSnapshot.java), ajouter le champ `unrealizedPnl` (double).
  - [ ] Dans [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java), extraire le champ `"unrealizedPL"` (converti en double) de la réponse `/accounts/{id}/openTrades` d'OANDA et le transmettre au constructeur de `OandaPositionSnapshot`.
  - [ ] Dans [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java), mapper `row.unrealizedPnl()` pour peupler le PnL sur l'objet `Position` retourné.

- [ ] **Task 2: Suivi du prix moyen d'entrée et du PnL dans les positions du Journal**
  - [ ] Dans [JournalPositions.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/JournalPositions.java), étendre le record `Snapshot` pour inclure `double entryPrice`.
  - [ ] Dans `applyFill(...)`, extraire le prix d'exécution du payload. Calculer le prix moyen pondéré d'entrée lorsque la taille de la position augmente du même côté (`(oldPrice * oldQty + fillPrice * fillQty) / newQty`). Conserver le prix d'entrée lors de fermetures partielles.
  - [ ] Mettre à jour `fromBroker(...)` pour populer le prix moyen d'entrée depuis l'objet `Position`.

- [ ] **Task 3: Exposition API REST (/api/control/summary et /api/runs/:runId)**
  - [ ] Dans [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java) et [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java), modifier la méthode `getPositions` pour mapper :
    - `"brokerTradeId"` -> `pos.brokerTradeId()`
    - `"clientTag"` -> `pos.clientTag()`
    - `"unrealizedPnl"` -> `pos.unrealizedPnl()` (ou 0.0)
    - `"entryPrice"` -> `pos.entryPrice()`
  - [ ] S'assurer que le fallback journal retourne également `"entryPrice"`, `"brokerTradeId"` (vide), `"clientTag"` (vide) et `"unrealizedPnl"` (0.0).

- [ ] **Task 4: Intégration et affichage dynamique Vue/TypeScript (Frontend)**
  - [ ] Dans [control-plane.ts](file:///Volumes/T7/src/trading-bridge/desktop/src/types/control-plane.ts), ajouter l'interface `Position` décrivant les champs ci-dessus, et mettre à jour `RunResult` / `RunSummary` pour l'utiliser.
  - [ ] Dans [LiveTradingView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/LiveTradingView.vue) :
    - Ajouter les en-têtes de colonnes "Ticket", "PnL ($)", "PnL (%)", "Metadata".
    - Implémenter une fonction réactive `getPositionPnL(pos)` qui utilise `pos.unrealizedPnl` ou calcule le PnL en fonction du `side`, de `pos.entryPrice`, du dernier prix de marché (`currentBid` / `currentAsk`) et du multiplicateur de la stratégie (`multiplier` du snapshot de config).
    - Formater et colorer dynamiquement les gains/pertes (vert pour les gains, rouge pour les pertes).
    - Rendre le numéro de ticket (`brokerTradeId`) et le tag (`clientTag`) visibles.

- [ ] **Task 5: Validation et Tests**
  - [ ] Écrire un test unitaire dans `JournalPositionsTest.java` (ou adapter les tests existants de reconciliation) pour valider le calcul correct du prix moyen d'entrée pondéré.
  - [ ] Lancer un build global via `mvn clean install` pour s'assurer qu'aucune compilation Java n'est affectée.
  - [ ] Tester le bon rendu visuel en mode dev sous Electron via `npm run electron:dev`.

## Dev Notes

- Le calcul en temps réel du PnL dans le frontend en mode sans-connexion (repli journal) garantit une réactivité optimale sans surcharger le serveur d'appels API répétitifs.
- Pour les devises Forex, l'affichage utilise le taux de conversion par défaut si l'instrument de cotation diffère de la devise du compte, mais l'approximation simple ou le PnL brut suffit pour le tableau.
- Respecter les styles existants (glassmorphism, couleurs et badges) pour l'affichage de la table des positions de [LiveTradingView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/LiveTradingView.vue).

### References

- Contrat `Position` : [Position.java](file:///Volumes/T7/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/Position.java)
- Restitution des données de runs / positions : [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java#L283-L360) et [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java#L984-L1060)
- Composant Vue de surveillance : [LiveTradingView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/LiveTradingView.vue#L836-L878)
- Modèles OANDA snapshot : [OandaPositionSnapshot.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/OandaPositionSnapshot.java)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash

### Debug Log References

### Completion Notes List

### File List
