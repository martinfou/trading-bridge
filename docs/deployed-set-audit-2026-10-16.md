# Audit d'évidence du set déployé — 2026-10-16 (décisions D43 et D55)

But : répondre à « le risque déployé est-il adossé à une preuve ? » et à « le backtest est-il proche de la
production ? ». Le livrable est un TABLEAU, pas un récit. Deux moitiés, lues séparément, avec des outils
différents : le **prévu** (famille de re-baseline sous le vrai modèle de coûts) et le **réalisé** (fills du
courtier, plage de la fenêtre).

## 1. Tableau d'évidence

Prévu = `RunCostModelReal`, JDK 21, `RealCostModel` (0 commission + demi-spread mesuré par jambe + swap
corrigé), capital du harnais 10 000, fenêtres du playbook §4.2 (FULL 2010-2025, IS 2010-2018,
OOS1 2019-2022, OOS2 2023-2025). Porte §4.3 : PF ≥ 1.05, Sharpe ≥ 0.3, DD ≤ 35 %, et **OOS1 ou OOS2 PF < 1.0
invalide**. Réalisé = fills du courtier sur la fenêtre 1 (`tx >= 219`), en CAD, au 2026-10-17 00:33 UTC
(NAV 1911.92, réalisé de fenêtre **−68.95**, 65 fills, **0 fuite de contrôle**).

| Stratégie | Instrument | Risk % | Provenance déclarée (`live-config.json`) | Rapport `wfa-*.json` | PF réel par fenêtre (FULL / IS / OOS1 / OOS2) | Verdict | Réalisé fenêtre |
|---|---|---|---|---|---|---|---|
| consecbar | GBP_JPY | 0.75 | « preliminary — needs full backtest » | **aucun** | 0.80 / 0.80 / 0.81 / 0.76 | **ÉCHEC**, toutes les fenêtres ; coût total = 741 % du prix pur (spread 93 %) | −23.02 (16 fills) |
| vwpreversion | USD_CHF | 0.6 | « 20y H1 multi-asset backtest » | **aucun** | 0.00 / 0.00 / 0.00 / 0.00 (**0 trade**) | **AUCUNE ATTENTE MESURABLE** | −38.98 (22 fills), la pire perte |
| monthweekphase | USD_JPY | 0.25 | « paper — OOS PF 2.33 / DD 4.86 % (docker-compose note; no full walk-forward report) » | **aucun** | 0.87 / 0.88 / 1.36 / 0.69 | **ÉCHEC** (FULL et OOS2 sous 1.0) | 0.00 (aucun fill) |
| compmomentum | USD_JPY | 0.4 | « paper — OOS PF 2.24 / Sharpe 1.00 / DD 8.6 % (… no full walk-forward report) » | **aucun** | 1.02 / 0.84 / 1.36 / 1.30 | **RÉGIME-DÉPENDANT, non validé** : les fenêtres OOS passent, l'IS est sous 1.0 et FULL sous la porte (1.05) | 0.00 (aucun fill) |
| ltrsi3 | EUR_USD | 0.3 | « paper — Sharpe>1.0 post look-ahead fix (… no full walk-forward report) » | **aucun** | 0.76 / 0.81 / 0.64 / 0.79 | **ÉCHEC** ; DD 381 %, coût total = 751 % du prix pur (spread 100 %) | +8.34 (21 fills), le seul positif |

Colonne « rapport `wfa-*.json` » : **0 des 47 rapports walk-forward du dépôt ne nomme l'une des cinq
stratégies déployées.** C'est le critère : sous la barre D46 (walk-forward passant sous le vrai modèle de
coûts ET fenêtre paper positive), **aucune des cinq n'est établie**, quel que soit le PF affiché ailleurs.

## 2. Ce que ces deux moitiés disent ensemble

**Le backtest est proche de la production, et les deux perdent.** Les remplissages réalisés suivent le sens
annoncé par la re-baseline : les trois stratégies à PF < 1 perdent ou ne tradent pas, et le seul côté positif
(ltrsi3, +8.34) est aussi celui dont la re-baseline affiche le Sharpe non négatif (0.37) tout en explosant en
drawdown (381 %) sur 15 ans. Rien dans les paramètres n'est à chercher : c'est le jeu déployé qui n'a pas
d'edge sous les coûts réels.

**Un zéro en backtest n'est pas un zéro en production, et l'inverse est vrai aussi.** `vwpreversion` affiche
**0 trade** sur les 4 fenêtres du harnais alors qu'il a **22 fills** sur le chemin réel, la pire perte de la
fenêtre. La donnée H1 du backtest ne porte aucun volume (les CSV Dukascopy n'en ont pas, les `.bars` en
portent un à 0), alors que les bougies OANDA du runner portent un volume de ticks : la stratégie n'a jamais
été jugée par la chose qui la juge en réel. Les deux côtés sont des RÉSULTATS à écrire, pas des cases vides.

**Le balayage de swap borne l'erreur sans changer le verdict.** PF identique sur ×0/×1/×2 pour consecbar
(0.80), monthweekphase (0.87) et ltrsi3 (0.76) ; compmomentum ne bouge qu'en dollars (FULL net +52.12 → −35.99).

## 3. Limites honnêtes

- **Sizing : le capital du harnais est 10 000 et les unités viennent des stratégies elles-mêmes, alors que la
  fenêtre tourne à ~2 000 CAD avec un sizing piloté par `computedRiskPct`.** PF, Sharpe et DD sont sans
  échelle, donc les verdicts pass/fail tiennent ; les **montants** en dollars ne sont pas comparables tels
  quels. C'est la raison de la décision D55 : un harnais au sizing de la fenêtre reste à produire pour donner
  une magnitude comparable.
- `backtest_runs` ne peut pas répondre au walk-forward (pas de colonnes IS/OOS) : l'absence de `wfa-*.json`
  est le critère, pas l'absence de lignes.
- Lane news de la semaine : 3 setups sur 4 ont tradé, **0 sur 3**, −15.29 CAD au total. Playbook
  discrétionnaire, donc aucune attente chiffrée et aucun walk-forward : elle n'est pas jugée par le harnais.
- Aucune modification de sizing, de logique ni de liste d'instruments n'a été faite dans la fenêtre pour
  « agir » sur cet audit (règle du dépôt) : on produit l'évidence et on alloue à la prochaine fenêtre.

## 4. Reproductibilité

```bash
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/21.0.2
./mvnw -q -B -DskipTests -Dspotbugs.skip=true -pl trading-examples -am install
./mvnw -q -B -pl trading-examples dependency:build-classpath -Dmdep.outputFile=/tmp/ccp.txt
CP="trading-examples/target/classes:$(cat /tmp/ccp.txt)"
java -cp "$CP" com.martinfou.trading.examples.RunCostModelReal > out.txt 2> err.stderr   # stderr séparé
```

Sortie brute conservée : `~/.hermes/cache/scratch/costmodel-real-20261016.txt` (138 lignes).
