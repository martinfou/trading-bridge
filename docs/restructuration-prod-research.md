# Restructuration : un axe prod / research au lieu de cinq conteneurs par stratégie

> Rédigé le 2026-09-30 après la purge de `events.db` (18,86 Go → 1,37 Go) et la découverte que trois
> des quatre conteneurs actifs tournent des images vieilles de deux mois.

## 1. Le problème, mesuré

Cinq services dans `docker-compose.yml` : `trader`, `comp-momentum`, `month-week`, `lt-rsi3`, `nfp-week`.
Quatre tournent, tous sur le **même compte OANDA** (`101-002-4729622-012`), tous construits depuis le
**même code**, et chacun a ses propres fichiers d'état dans un `/tmp` non monté.

| Mesure | Valeur | Lecture |
|---|---|---|
| Mémoire des 4 conteneurs | 253 MiB au total | négligeable, ce n'est pas l'argument |
| CPU | 0,06 % chacun | au repos |
| Images | 5 images du même code, 464 à 554 MB | ~2,5 Go dupliqués |
| Âge des images | trader 3 h, lt-rsi3 2 mois, month-week 2 mois, comp-momentum 2 mois | **le vrai coût** |

Le découpage ne protège pas les conteneurs les uns des autres : ils partagent le compte, le code et
`events.db`. Ce qu'il isole réellement, c'est **le code que personne ne regarde**. Trois conteneurs
exécutent du code vieux de deux mois sur un compte actif, et leur séparation est exactement ce qui a
empêché qu'on s'en aperçoive. C'est aussi pourquoi le correctif de sizing a été déployé sur le mauvais
conteneur pendant que les trois autres continuaient avec l'ancien comportement.

Coûts opérationnels qui en découlent, tous vécus aujourd'hui :
- quatre danses de sauvegarde et restauration d'état à chaque déploiement, chacune capable de perdre
  une position ouverte ;
- quatre points de surveillance au lieu d'un ;
- un build Maven par service pour le même code.

## 2. La cible

**Deux conteneurs, une seule image.**

```
trading-bridge:latest        (un seul build, une seule version de code)

  service prod        -> compte -012   STRATEGY=vwpreversion consecbar
                      -> état sur volume monté   strategy-state-prod:/app/state

  service research    -> compte séparé (-011 ou un nouveau sous-compte)
                      -> STRATEGY=<expériences>
                      -> état sur volume monté   strategy-state-research:/app/state
                      -> rebuild libre, sans conséquence sur prod
```

Pourquoi cet axe et pas un autre :
- Un test ne peut pas toucher le compte de production : l'isolation devient structurelle au lieu d'être
  une convention. C'est la réponse au vrai risque, pas au risque imaginaire.
- Une seule image supprime la dérive de versions par construction. Il devient impossible d'oublier un
  conteneur, parce qu'il n'y a plus qu'une version.
- Le conteneur `research` peut être cassé, redémarré et reconstruit sans cérémonie, ce qui est
  précisément ce qu'on veut d'un environnement de recherche.

## 3. Ce que ça ne règle pas, dit franchement

- **Le blast radius.** Si le JVM de prod meurt, toutes les stratégies de prod meurent ensemble. Le
  découpage actuel protège de ça en théorie. Le compromis est assumé : le runner exécute déjà deux
  stratégies par conteneur (`vwpreversion consecbar`), donc ce risque existe déjà aujourd'hui, et il
  est moins coûteux que trois conteneurs qui dérivent.
- **Deux conteneurs restent deux processus à surveiller.** On passe de quatre à deux, pas à zéro.

## 4. Étapes, dans l'ordre, chacune réversible

1. **Sortir l'état de `/tmp`.** Monter un volume sur le répertoire d'état et pointer le runner dessus.
   C'est la correction qui rend tous les déploiements suivants non délicats, et elle vaut la peine
   même sans restructuration. À faire avec la position `2082` ouverte, donc : sauvegarder, recréer,
   vérifier que le runner réadopte la position, sinon revenir en arrière.
2. **Un seul build partagé.** Les services qui restent référencent `image: trading-bridge:latest` au
   lieu de chacun son `build:`. Vérifier qu'un `docker compose build` ne construit qu'une fois.
3. **Séparer le compte de research.** Pointer le service research sur un autre sous-compte OANDA.
   Vérifier par une lecture du compte que les ordres de research ne touchent pas `-012`.
4. **Migrer les stratégies une à une** de l'ancien découpage vers `prod` ou `research`, en vérifiant
   après chacune que le compte est cohérent et qu'aucune position n'est restée orpheline.

## 5. Ce qui doit être vrai avant de toucher à quoi que ce soit

- La position ouverte est identifiée et sauvegardée (`2082 GBP_JPY SELL 92600`, SL 209.320, TP 207.976).
- On sait quelle stratégie appartient à prod et laquelle appartient à research. `lt-rsi3`,
  `comp-momentum` et `month-week` sont des expériences par leur nom, mais elles tradent le compte de
  production : cette ambiguïté est la première chose à trancher, et elle t'appartient.
- Un retour arrière testé : ancien `docker-compose.yml` conservé, images actuelles non supprimées.

## 6. Ordre recommandé

1. Finir le déploiement en cours (les correctifs sur le trader, position restaurée et réadoptée).
2. Faire l'étape 1 seule (état sur volume), qui supprime le risque à chaque déploiement futur.
3. Décider le partage prod / research stratégie par stratégie.
4. Faire les étapes 2 à 4 dans un moment calme, marché fermé.
