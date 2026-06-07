# MON BACKTEST HEDGE MAINTENANT — Comment j'ai rendu mon algo de trading aussi intelligent qu'OANDA

Durée estimée: 12 min
Audience: développeurs / algo traders / devs finance
Source: trading-bridge #aea009a

## INTRO (0:00 - 1:30)

**[HOOK — Rossman style]**
« Ton backtest te dit que t'es rentable. Tu passes en live. Et là... tout pète. Pourquoi ? Parce que ton engin de backtest ne se comporte pas comme ton broker. Let me show you what I mean. »

**[Contexte — Primeagen energy]**
« J'ai un moteur de backtest Java. Il fait des trades, il calcule du PnL, tout va bien. Sauf que je pose une question simple : est-ce que mon backtest supporte le hedging comme OANDA ? »

« La réponse courte : non. Et c'était un problème. Parce que si t'as deux stratégies — une qui achete, une qui vend — sur le même symbole, mon backtest les fermait au lieu de les laisser coexister. C'est pas comme ça que OANDA marche. »

**[Ce que la vidéo va montrer]**
« On va plonger dans le code, voir le bug, le fix, et les tests qui prouvent que maintenant ça marche. Spoiler : 1500 lignes de changements, 6 nouveaux tests, et 45 stratégies qui continuent de fonctionner sans changer une ligne. »

**[CTA]**
« Si t'es dans le trading algorithmique, abonne-toi. On va voir du vrai code, pas de bullshit. »

---

## CONTENU

### Section 1: Le problème (1:30 - 4:00)

**[Eli style — poser le cadre]**
« Pour comprendre le problème, faut comprendre comment un backtest engine gère les positions. La plupart des engines — et le mien incluait — utilisent une Map<String, Position>. Un symbole, une position. Point. »

« Si t'as un BUY sur EUR/USD et que ton système dit "now SELL", l'engine regarde ta position existante et dit "ah, sens inverse, je close". »

« Le problème ? C'est pas comme ça que OANDA marche avec hedgingEnabled=true. Sur OANDA, tu peux avoir 10K EUR/USD long ET 5K EUR/USD short en même temps. C'est deux positions indépendantes. »

**[Primeagen — montrer le vrai code]**
« Let me show you the actual code. Voici l'ancien engine : »

```java
// AVANT — une position par symbole
Map<String, Position> positions = new HashMap<>();
```

« Regarde ça. C'est mignon. C'est simple. C'est WRONG. Si ta stratégie ferme avec closeOnly() comme OANDA le fait avec REDUCE_ONLY — très bien, ça marchait. Mais si une stratégie ouvrait un ordre de sens opposé sans closeOnly()... BOOM. »

« Résultat : mes backtests ne reflétaient pas la réalité du broker. Des stratégies qui semblaient bonnes en backtest pètent en live parce que l'engine ne gère pas les positions comme le broker. »

**[Transition]**
« So here's what I did. »

### Section 2: La solution — Map<String, List<Position>> (4:00 - 7:00)

**[Rossman — direct, factuel]**
« La solution est triviale en théorie, douloureuse en pratique. J'ai changé la structure de données centrale du BacktestEngine : »

```java
// APRÈS — plusieurs positions par symbole
Map<String, List<Position>> positions = new HashMap<>();
```

« Ça a l'air simple dit comme ça. Mais c'est 202 lignes modifiées dans BacktestEngine.java. Pourquoi ? Parce que tout — le calcul du PnL, les stops, les reports — suppose qu'un symbole = une position. »

**[Eli — expliquer la logique]**
« Voici comment ça marche maintenant : »

1. **Même sens** (BUY + BUY) → scale-in : on ajoute à la position existante
2. **Sens opposé + closeOnly=true** → reduce : on réduit la position opposée (OANDA REDUCE_ONLY)
3. **Sens opposé + closeOnly=false** → nouvelle position hedge : on crée une position indépendante

« La clé, c'est closeOnly(). Sans ça, les 45 stratégies existantes qui utilisent .closeOnly() pour fermer leurs positions continuent de marcher exactement pareil. »

**[Primeagen — montrer le commit]**
« Le commit c'est aea009a. 1554 lignes ajoutées, 61 enlevées. 9 fichiers modifiés. Ça inclut BacktestEngine, les tests, et même 3 nouvelles stratégies créatives. »

« La partie que je préfère : les tests d'edging. 6 nouveaux tests spécifiques : »

```
✓ closeOnly réduit la position opposée
✓ closeOnly ne crée PAS de nouvelle position (no-op si rien à réduire)
✓ Long et short coexistent sur le même symbole
✓ SL sur une position hedge ne touche pas l'autre position
✓ Stops indépendants par position (un stop loss sur le long ne touche pas le short)
```

« Chaque test vérifie un edge case. Pas de guessing. »

### Section 3: L'impact réel (7:00 - 9:30)

**[Rossman — connecter à la réalité]**
« Pourquoi c'est important concrètement ? »

« J'ai des stratégies news — CPI, ECB, NFP — qui peuvent générer des signaux opposés sur le même symbole dans la même journée. Exemple : »

« La stratégie **CPI Momentum** regarde le chiffre de l'inflation US. Si le CPI est plus haut que prévu → sell USD. Mais en même temps, ma stratégie **ECB Dovish** peut dire "la BCE va être accommodante" → sell EUR. »

« Avant, ces deux signaux sur EUR/USD se cancellaient ou fermaient la première position. Maintenant, elles coexistent. Chacune avec son SL, son TP, sa gestion de risque. »

« C'est pas du polish. C'est fondamental. Sans ça, tu peux pas run plusieurs stratégies sur les mêmes paires — ce qui est LE cas d'usage du multi-strat. »

**[Primeagen — chiffres]**
« Backtest avant hedging : 0 hedges possibles. Backtest après hedging : positions long ET short simultanées par symbole. 55+ stratégies compatibles. Des centaines de milliers de bars historiques retestés. »

« Et le meilleur ? 45 stratégies existantes — ZERO changements. Parce que j'avais déjà closeOnly() dans le Order model. C'est pas arrivalé par hasard. C'est du design qui paye. »

### Section 4: Lessons learned (9:30 - 11:00)

**[Eli — réflexion]**
« Trois choses que j'ai apprises : »

**1. Modélise ton broker, pas ton imagination**
« J'aurais dû coder le hedging le jour 1. J'ai attendu que OANDA me pose la question en live. Un bon backtest = mirror du broker. »

**2. closeOnly() est ton meilleur ami**
« Si tu fais du trading algorithmique, aie un flag closeOnly/REDUCE_ONLY dans ton order model. Même si tu penses pas en avoir besoin. Le jour où tu lances ta deuxième stratégie, tu seras content de l'avoir. »

**3. Les tests sont la seule vérité**
« Les 6 tests d'edging m'ont sauvé 3 fois pendant la refactor. Sans eux, j'aurais cassé 45 stratégies sans le savoir. »

**[Primeagen — opinion]**
« Et franchement ? Si ton backtest engine ne supporte pas le hedging et que tu trades avec un broker qui le fait... t'es en train de te mentir. Tes résultats de backtest sont des chiffres randoms. »

« J'ai passé 10 ans à voir des gens qui disent "mon backtest est à 80% de win rate" et qui perdent en live parce que leur engine est pas fidèle au broker. Arrêtez ça. »

---

## OUTRO (11:00 - 12:00)

**[Résumé]**
« Donc : Map<String, Position> → Map<String, List<Position>>. 1500 lignes. 6 tests. 45 stratégies inchangées. Mon backtest se comporte maintenant comme OANDA. »

**[CTA final — Rossman]**
« Si t'as aimé le format — vrai code, vrai projet, pas de bullshit — like, commente, abonne-toi. La semaine prochaine : comment j'utilise Monte Carlo pour valider mes backtests sans me mentir à moi-même. »

**[Lien]**
« Le code est open source : github.com/martinfou/trading-bridge. Va voir le commit aea009a. »
