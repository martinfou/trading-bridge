# Story 48.2 — Deux défauts de durabilité du cycle de vie des runs

- **Status : draft** (propose-only : attend la décision de Martin, voir « Décision demandée »)
- **Date :** 2026-10-02
- **Propriétaire :** Martin
- **baseline_commit :** 39991ee3
- **Origine :** trouvés par une revue indépendante (agy) sur un correctif de test sans rapport, qui a lu le
  code de production autour du test. Aucun des deux n'est introduit par ce correctif : les deux sont
  préexistants.

## Défaut 1 — une erreur du courtier laisse un run « actif » en base pour toujours

**Où :** `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`, `stop()` cas `RUNNING`,
lignes 558 à 584.

**Mécanisme :** la séquence est `record.markCompleted(...)` (l.559), puis `cancelAllAtBroker(runId)` (l.561)
**hors de tout `try/catch`**, puis la liquidation et la fermeture d'exécuteur (l.563-582, elles protégées), puis
`notifyTransition(before, record, RunTransition.STOP)` (l.583) qui persiste. Si `cancelAllAtBroker` lève une
exception, l'exécution quitte la méthode avant la ligne 583 : **le statut d'arrêt n'est jamais persisté**, la
ligne en base reste `RUNNING` alors que le run est arrêté.

**Impact :** au redémarrage, le plan de contrôle restaure les runs `RUNNING` de la base (voir
`testControlPlaneRestoreActiveRuns`) et traite donc un run terminé comme actif. Un arrêt d'urgence qui échoue
une fois au niveau du courtier crée un run fantôme qui survit à tous les redémarrages.

**Correctif proposé :** rendre la persistance du statut terminal inconditionnelle. Concrètement, entourer la
partie courtier d'un `try/catch` qui journalise, et placer `markCompleted` + `notifyTransition` dans un chemin
qui s'exécute dans tous les cas (par exemple un `try/finally`, ou en sortant la persistance du bloc métier).

**Test qui prouve le défaut, et qui doit exister avant le correctif :** un test qui fait lever
`cancelAllAtBroker` (courtier en échec) sur un run `RUNNING`, appelle `stop()`, et vérifie que le statut
persisté est terminal. Il doit échouer sur le code actuel, et le test doit garder sa puissance (il doit
échouer si on retire la persistance).

## Défaut 2 — l'état terminal est publié en mémoire avant d'être persisté

**Où :** `RunManager.run()`, lignes 1023 et 1024.

**Mécanisme :** `record.markCompleted(...)` (l.1023) fait passer l'état EN MÉMOIRE à `COMPLETED`, puis
`notifyTransition(before, record, RunTransition.COMPLETE)` (l.1024) appelle `runRecordStore.save(after)` de
façon synchrone. Entre les deux instructions, la mémoire dit `COMPLETED` et la base dit encore `RUNNING`.

**Impact :** deux conséquences distinctes, à ne pas confondre.

1. Côté test, cette fenêtre produit une course : c'est exactement ce qui a fait échouer le gate deux fois le
   2026-10-02 (« expected: <COMPLETED> but was: <RUNNING> »). Corrigé côté test (branche
   `fix/runmanager-persisted-status-race`, attente bornée sur le statut persisté).
2. Côté production, tout lecteur de la base dans cette fenêtre voit un run terminé comme actif, et un arrêt
   brutal du processus entre les deux instructions laisse la base définitivement en `RUNNING`.

**Correctif proposé :** persister AVANT de publier, c'est-à-dire que la transition devienne visible (mémoire)
seulement une fois la ligne écrite, ou que l'écriture et la publication soient dans le même chemin atomique au
niveau du store. À évaluer : l'ordre inverse peut affecter les listeners (`listeners` sont notifiés après la
persistance, donc l'ordre actuel vis-à-vis d'eux ne changerait pas).

**Attention :** ce correctif touche le chemin de complétion de TOUS les runs, backtest comme paper. Il demande
une revue indépendante et une preuve : le test de persistance corrigé doit rester vert, et la preuve de
puissance doit être refaite.

## Hors périmètre

- Ne pas modifier le comportement fonctionnel d'un run, seulement l'ordre et la garantie de la persistance.
- Ne pas toucher au modèle de coûts, au sizing, ni aux stratégies.
- La fenêtre 1 continue : ce n'est pas un changement de stratégie, d'instrument ni de liste de services, donc
  la règle D38 sur la continuité de la fenêtre n'est pas brisée par un correctif de durabilité. À confirmer
  par Martin.

## Décision demandée

Une ligne suffit : `48.2 maintenant` (je corrige, revue, gate, déploiement), `48.2 plus tard` (je laisse la
story en draft et on la traite après la fenêtre), ou `défaut 1 seulement` si seul l'arrêt qui rate est
prioritaire.
