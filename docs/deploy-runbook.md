# Runbook : déployer le trader sans perdre la position ouverte

> Écrit le 2026-09-30 après un déploiement réussi, parce que la séquence a fonctionné et que
> l'erreur commise en la suivant mérite d'être écrite noir sur blanc.

## Le problème

L'état des stratégies vit dans `/tmp` **à l'intérieur du conteneur**, et `/tmp` n'est pas monté.
Donc recréer le conteneur efface la mémoire du runner : il redémarre en croyant qu'il n'a aucune
position, alors que le courtier en détient toujours une. Résultat : une position non gérée, protégée
seulement par son SL/TP côté courtier.

## La séquence qui marche

```bash
CID_OLD=$(docker compose ps -q trader)

# 1. Sortir l'état du conteneur VIVANT avant de toucher à quoi que ce soit
mkdir -p /tmp/bridge-state-backup && rm -f /tmp/bridge-state-backup/*
for f in $(docker exec "$CID_OLD" sh -c 'ls /tmp/*.json 2>/dev/null'); do
  docker cp "$CID_OLD:$f" /tmp/bridge-state-backup/
done
# vérifier que la position attendue est bien dedans, tradeId et quantité, avant de continuer
python3 -c "import json;d=json.load(open('/tmp/bridge-state-backup/live-strategy-state-vwpreversion.json'));print(d.get('inTrade'), d.get('activeTrades'))"

# 2. Construire la nouvelle image
docker compose build trader

# 3. CRÉER sans démarrer. C'est l'étape qui rend la restauration fiable :
#    restaurer dans un conteneur déjà démarré fait la course avec le démarrage du runner,
#    et la course est invisible jusqu'à ce que quelqu'un remarque une position orpheline.
docker compose up -d --no-start --force-recreate trader
CID_NEW=$(docker compose ps -aq trader | head -1)
docker inspect "$CID_NEW" --format '{{.State.Status}}'   # doit afficher: created

# 4. Restaurer l'état pendant que le conteneur est ARRÊTÉ
for f in /tmp/bridge-state-backup/*.json; do
  docker cp "$f" "$CID_NEW:/tmp/$(basename $f)"
done

# 5. Démarrer
docker start "$CID_NEW"

# 6. VÉRIFIER, ligne par ligne, dans les 60 premières secondes
docker logs "$CID_NEW" 2>&1 | grep -aE "Resumed state|inTrade|monitor|Permission"
```

## Ce que la vérification doit montrer

- `♻ Resumed state: N active trades` avec **N égal au nombre de positions réellement ouvertes**.
  `0 active trades` alors que le courtier détient une position = arrêt immédiat, on est dans le cas
  de la position orpheline.
- `Risk sizing: BUDGET ... max notional 5.0x NAV`, la preuve que la nouvelle image est bien active.
- `Discarded N order(s) queued by the M warm-up bar(s)`, la preuve que le fix de replay est actif.
- Aucun `Failed to write aggregated monitor`.

## Le piège qui a coûté un défaut réel

**`docker cp` préserve le propriétaire du fichier source.** Les fichiers copiés depuis l'ancien
conteneur sont arrivés avec des propriétaires hétérogènes selon le processus qui les avait écrits
(`ubuntu` pour le moniteur, `root` pour les états). Le nouveau conteneur tourne en `root` et n'a
**pas** pu écraser le fichier appartenant à `ubuntu` :

```
[monitor-writer] WARN Failed to write aggregated monitor:
    /tmp/paper-trading-status.json (Permission denied)
```

`chmod 666` n'a rien réglé, ce qui est l'indice que ce n'était pas un problème de mode.
`rm` du fichier périmé a suffi : le runner l'a recréé lui-même.

Conséquence si on ne lit pas les logs ligne par ligne : le bot trade normalement pendant que le
fichier de monitorage reste figé, et le tableau de bord affiche des chiffres morts sans que rien
ne le signale. **Toujours supprimer les fichiers que le runner va recréer lui-même** (moniteurs,
statuts) au lieu de les restaurer, et ne restaurer que les fichiers d'état dont il a besoin pour
réadopter une position.

## Ce qui rendrait ce runbook inutile

Monter un volume sur le répertoire d'état (`strategy-state:/app/state`) et pointer le runner dessus.
L'état survivrait alors à la recréation, et un déploiement redeviendrait un simple redémarrage.
C'est l'étape 1 du plan de restructuration, et elle vaut la peine même sans le reste.
