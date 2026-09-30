# 🛡️ Trading Guardrails — politique d'auto-protection

> **Principe** : Martin ne fait pas la revue du code de trading. Le système doit donc se protéger
> tout seul : invariants dans le code, vérification à l'exécution, limites de risque, porte de
> déploiement automatique, et des chiffres qui viennent **toujours** du courtier.
>
> Dernière mise à jour : 2026-09-29 (créé après l'incident P&L du 29 septembre).

## L'incident qui a créé ce document

Le compteur de P&L interne du runner affichait **+350,50 $** pour la stratégie `consecbar` et
**−116,08 $** pour `vwpreversion`, alors que le compte OANDA avait réalisé environ **±1 $ CAD** sur
les mêmes transactions GBP_JPY.

Cause racine : le chemin de sortie sur signal de la stratégie ajoutait une estimation calculée
dans la **devise de cotation** de l'instrument (`Δprix × unités` = **JPY** pour GBP_JPY) au même
accumulateur qui recevait le `realizedPL` du courtier, **en devise du compte**. Trois défauts :
surdéclaration ~×108, aucune correction ultérieure (la transaction était retirée du suivi avant
réconciliation), et **double comptage** quand la réconciliation asynchrone complétait la même
transaction. La valeur contaminée était persistée puis rechargée à chaque redémarrage.

## Les cinq couches

| # | Couche | Ce qu'elle empêche | État |
|---|---|---|---|
| 1 | **Invariants du code** | La classe de bug elle-même, par construction | en place |
| 2 | **Vérification à l'exécution** | Une dérive silencieuse entre le système et le courtier | en place |
| 3 | **Limites de risque** | Une perte qui dépasse ce qui a été décidé | en place |
| 4 | **Porte de déploiement** | Du code rouge qui part en production | en place |
| 5 | **Rapports courtier** | Un chiffre inventé présenté comme la vérité | en place |

### Couche 1 — Invariants du code (par construction)

1. **Un seul écrivain de P&L.** Le total réalisé n'est alimenté que par la réconciliation avec le
   courtier (`RealizedPnlLedger.recordBrokerPnl`). Aucun autre chemin ne peut le modifier.
2. **Une transaction = une entrée.** L'entrée est indexée par le `tradeId` du courtier : une
   réconciliation répétée est détectée, journalisée et **ignorée** (pas de double comptage).
3. **Une estimation n'entre jamais dans le total.** Les estimations locales sont enregistrées comme
   *ignorées* et restent visibles dans les compteurs d'observabilité uniquement.
4. **Devise explicite.** Le total est en devise du compte (CAD), tel que fourni par OANDA.
5. **Aucune valeur historique contaminée n'est reprise.** Les états persistés antérieurs à la
   version d'écriture courante sont **abandonnés** au redémarrage, jamais transportés.

### Couche 2 — Vérification à l'exécution

- Toutes les **30 minutes**, le runner compare **chaque transaction du registre** avec le
  `realizedPL` d'OANDA pour le même `tradeId`.
- Tolérance de divergence : **max(0,02 $ CAD, 1 %)**. Au-delà : `ERROR` dans le journal, compteur
  `pnlIntegrityMismatches` incrémenté et écrit dans l'état/moniteur.
- Si le courtier est injoignable, la vérification est sautée silencieusement (pas de faux positif),
  et une réconciliation en attente **bloque les nouvelles entrées**.
- Repli sans courtier : le P&L de la transaction reste **inconnu** (jamais faux) et est compté dans
  `ignoredLocalEstimates`.

### Couche 3 — Limites de risque

- **Taille** : plafonnée par stratégie (`live-config.json`, défaut 1,5 % du solde, 0,5 % pour une
  stratégie inconnue du config).
- **Perte journalière** : au-delà de **−2 % de la NAV**, plus aucune nouvelle entrée de la journée
  (les sorties continuent).
- **Une position ouverte par stratégie et par instrument** ; pas d'augmentation de levier sans
  changement explicite de configuration.
- **Compte** : `paper`/`practice` uniquement. Aucun ordre sur un compte réel sans décision
  explicite de Martin, écrite dans ce document.

### Couche 4 — Porte de déploiement

`scripts/pre-deploy-gate.sh` : build + suite de tests complète, validation de `docker-compose`,
et **smoke test OANDA en lecture seule**. Le déploiement (`scripts/deploy-paper.sh`) refuse de
partir si la porte échoue. Une **revue indépendante par un second agent** est exigée avant le
merge dans la branche par défaut — jamais Martin.

### Couche 5 — Rapports courtier

- Le **courtier est la seule source** des chiffres de P&L (positions, transactions, `realizedPL`).
- Les compteurs internes sont de l'**observabilité**, jamais une vérité comptable.
- Toute divergence, tout repli de réconciliation et toute limite atteinte sont **notifiés en une
  ligne** à Martin : visibilité, sans demande d'action.

## Ce que ce document n'est pas

- Pas une garantie de performance : il protège la **vérité comptable** et le **risque décidé**, pas
  contre une stratégie qui perd de l'argent par conception.
- Pas une autorisation d'augmenter le risque : toute hausse de taille, de levier ou de périmètre
  passe par une modification explicite de ce fichier.
