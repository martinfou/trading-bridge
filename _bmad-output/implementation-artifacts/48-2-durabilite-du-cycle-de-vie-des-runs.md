# Story 48.2 — Deux défauts de durabilité du cycle de vie des runs

- **Status : done** (déployée le 2026-10-09 06:07 EDT : fusion `f363c6da` sur master, image vérifiée,
état de la fenêtre préservé ; garde-fous `c4973635` déployés dans le même geste. Preuves : `docs/paper-window-log.md`, section du 2026-10-09)
- **Date :** 2026-10-02
- **Propriétaire :** Martin
- **baseline_commit :** 39991ee3
- **Origine :** trouvés par une revue indépendante (agy) sur un correctif de test sans rapport, qui a lu le
  code de production autour du test. Aucun des deux n'est introduit par ce correctif : les deux sont
  préexistants.

## Défaut 1 — une erreur du courtier laisse un run « actif » en base pour toujours

**Où :** `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`, `stop()` cas `RUNNING`,
lignes 558 à 584.

**Mécanisme, vérifié dans le code le 2026-10-02 — la première description était fausse :** `cancelAllAtBroker`
(l.608-620) et `flattenAtBroker` (l.623-637) **avalent déjà leurs exceptions** avec un `catch (Exception e)`
interne. Une panne courtier ordinaire (HTTP 503, timeout, `BrokerException`) n'interrompt donc PAS `stop()` :
seule une `java.lang.Error` (OOM, StackOverflow) s'échapperait, et aucun incident réel du courtier ne produit
ça. Le défaut initialement décrit (la persistance sautée par une panne du courtier) n'est pas atteignable en
pratique.

**Le défaut réellement atteignable, lui, est pire :** quand `flattenAtBroker` constate qu'il reste des
positions chez le courtier, il écrit `CRITICAL: N position(s) remain at broker after flatten for run ...
Manual intervention required.` **dans un log**, puis `stop()` persiste le run en `COMPLETED` avec le payload
fixe `{"message": "stopped by operator"}`. L'incident est donc **totalement invisible** pour tout outil qui
interroge la base. Une revue indépendante a même montré que le script de déploiement ne l'attrape pas non plus :
`scripts/deploy-paper.sh` (l.162-166) ne teste que `[ "$n" -gt "$broker" ]`, soit « les trades locaux ne
doivent pas excéder ceux du courtier ». Une position orpheline chez le courtier avec zéro run local donne
`$n = 0 > 0` = faux : le script affiche `✓ deployment verified` sans rien voir. Cette vérification
unidirectionnelle est un défaut de détection distinct, à corriger séparément (décision propriétaire).

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

## Revue indépendante du 2026-10-02 et décisions prises

Verdict NEEDS_FIX, quatre constats, tous corrigés dans le même worktree avant fusion :

1. **BLOCKER — `stop()` persistait `COMPLETED` avant la liquidation.** La revue a jugé le compromis dangereux
   en production, et le propriétaire a suivi : une position ouverte non surveillée coûte plus cher qu'un run
   restauré au redémarrage, parce que le runner resynchronise la position. **Décision : LIQUIDER D'ABORD**
   (best-effort, résultat capturé), **puis persister et publier l'état terminal, inconditionnellement.**
   Résidu assumé et documenté : pendant la liquidation la base reste `RUNNING`, donc un arrêt brutal à cet
   instant précis fait restaurer un run que l'opérateur venait d'arrêter. La solution complète serait un état
   transitoire persistant `STOPPING` avant liquidation, puis terminal après confirmation du courtier : le modèle
   de statuts ne l'a pas aujourd'hui, c'est hors périmètre et noté ici.
2. **MAJOR — l'échec de liquidation n'existait que dans un log.** **Décision :** statut `FAILED`, `errorMessage`
   explicite, et `"liquidation_failed": true` dans le payload persisté. Le statut `FAILED` a été choisi parce
   qu'il est le plus visible pour tout outil qui interroge la base sans connaître nos conventions de payload.
3. **MAJOR — `persistThenNotify` ne publiait ni ne notifiait quand l'écriture échouait.** Conséquence réelle
   mesurée par la revue : le thread se termine, le record reste `RUNNING` en mémoire (run fantôme dans
   `/api/runs`) et le listener de `ControlPlaneServer` n'étant pas appelé, `activeSubscriptions` garde des
   abonnements SSE orphelins. **Décision :** l'écriture est toujours tentée d'abord, mais en cas d'échec du
   disque on publie et on notifie quand même. La cohérence du processus vivant gagne sur l'écriture.
4. **MINOR — setter mutable pour la couture de test.** Rétabli en champ `final` plus une surcharge de
   constructeur package-private, comme les autres coutures de test du fichier.

**Test remplacé, parce qu'il était faux :** le test « une panne du courtier saute la persistance » n'avait
qu'une puissance ARTIFICIELLE, atteignable seulement en forçant une `java.lang.Error` dans le mock. Il est
remplacé par le défaut réellement atteignable : un courtier dont `flattenAllPositions` « réussit » mais qui
continue de renvoyer des positions ensuite, donc dont la liquidation a échoué → le record persisté doit être
`FAILED` avec une trace durable. Ce test échoue sur master, où le run finissait `COMPLETED` sans aucune trace.

**Défaut de détection distinct, trouvé par la même revue :** `scripts/deploy-paper.sh` (l.162-166) ne teste que
`[ "$n" -gt "$broker" ]`. Une position orpheline chez le courtier avec zéro run local passe le contrôle et le
script annonce `✓ deployment verified`. Vérification unidirectionnelle à corriger avec l'accord de Martin, en
même temps que le verrou `flock` proposé pour empêcher deux déploiements simultanés.

### Passe de confirmation, même journée

Deuxième revue indépendante, sur les deux changements (le Java et le script). Verdict NEEDS_FIX des deux
côtés, trois constats chacun, tous traités.

**Côté script** (branche `fix/deploy-guardrails`, commit `8d233bdb`) :

- le contrôle global `local_total == broker` **interdisait les déploiements partiels** : `--apply trader`
  comparait les positions des seuls services déployés à toutes celles du compte partagé, donc échouait à
  tort. Le contrôle porte maintenant sur **tous les services vivants de la pile**, pas sur la liste déployée ;
- un `sleep 25` fixe pouvait faire échouer un déploiement légitime quand quatre JVM démarrent ensemble :
  remplacé par une attente bornée de 60 s, sondage toutes les 3 s, qui s'arrête dès que tous les runners ont
  parlé ;
- le chemin du verrou porte maintenant l'UID dans son nom, pour que deux utilisateurs de la machine ne se
  bloquent pas mutuellement.

**Côté Java** (branche `fix/run-48-2-durability`) :

- **BLOCKER : la branche `OandaStreamingExecutor` de `liquidate()` n'appelait jamais `flattenAtBroker`** et ne
  regardait pas le résultat de son propre arrêt, donc une position résiduelle derrière l'exécuteur de
  streaming finissait en `COMPLETED`. C'est le défaut de cette story, caché dans une branche voisine ;
- **MAJOR : faux `FAILED` pour les runs sans courtier.** Un run de backtest, ou un run courtier arrêté avant
  que son exécuteur n'enregistre le broker, aurait reçu une trace de liquidation imaginaire. Il faut un
  prédicat explicite « run adossé à un courtier » avant de conclure à un échec ;
- **MINOR :** `stagedFailed(message, payload)` plutôt que l'enchaînement de deux états intermédiaires.

## Hors périmètre

- Ne pas modifier le comportement fonctionnel d'un run, seulement l'ordre et la garantie de la persistance.
- Ne pas toucher au modèle de coûts, au sizing, ni aux stratégies.
- La fenêtre 1 continue : ce n'est pas un changement de stratégie, d'instrument ni de liste de services, donc
  la règle D38 sur la continuité de la fenêtre n'est pas brisée par un correctif de durabilité.
  **Confirmé par Martin le 2026-10-09 (D39)** : la fenêtre 1 continue à travers ce déploiement, horloge et fin
  prévue inchangées.

## Décision demandée

Une ligne suffit : `48.2 maintenant` (je corrige, revue, gate, déploiement), `48.2 plus tard` (je laisse la
story en draft et on la traite après la fenêtre), ou `défaut 1 seulement` si seul l'arrêt qui rate est
prioritaire.
