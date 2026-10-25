# Defect (pre-existing, hors lane P&L) : IbkrBroker perd les fills partiels

## Constat

`IbkrBroker.onExecution` traite chaque `execution` comme un fill complet :

- au premier fill partiel, la commande est retiree des deux maps (`pendingOrdersByClientTag`,
  `orderIdToClientTag`) et `order.fill()` est emis pour la quantite TOTALE de l'ordre, au prix de ce
  premier partiel ;
- les partiels suivants tombent donc dans le log « unknown order » et sont perdus.

Consequences : taille de position fausse, prix moyen faux, et des fills qui n'existent nulle part dans
l'etat du broker. Le P&L IBKR n'est pas touche (il vient de `CommissionReport.realizedPNL`, pas du broker),
mais tout ce qui s'appuie sur `order.fill()` l'est.

## Pourquoi ce n'est pas corrige dans la lane P&L

Le decouverte vient d'une revue de la lane P&L, mais le defaut est anterieur, dans l'accounting du broker,
et le corriger change le contrat de `BrokerOrder` (fills partiels, quantite cumulee). Cela merite sa propre
story avec ses tests, pas un ajout en fin de diff sur une lane d'affichage.

## Correction attendue

1. Cumuler les executions par `orderId` : quantite cumulee, prix moyen pondere.
2. Ne retirer la commande des maps qu'une fois la quantite cumulee >= `order.quantity()` (ou annulation).
3. Emettre `BrokerEvent.fill` par partiel, avec la quantite du partiel et le prix moyen cumule, ou un evenement
   distinct si le contrat actuel l'exige.
4. Tests : 3 partiels -> 3 evenements, somme = quantite de l'ordre, prix moyen pondere, etat final FILLED
   une seule fois ; partiel puis annulation -> etat CANCELLED avec la quantite reellement remplie.

## Aucun chemin live aujourd'hui

`LiveStrategyRunner` instancie `new OandaExecutor(...)` et n'enregistre aucune strategie futures : aucun
ordre IBKR n'est envoye par les bots. La correction peut donc se faire sans fenetre de risque.
