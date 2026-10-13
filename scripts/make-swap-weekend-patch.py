#!/usr/bin/env python3
"""Genere le correctif pret a appliquer pour SwapCalculator (moteur, repo trading-bridge).

Le correctif N'EST PAS applique par la session de recherche : il est ecrit ici en tant que
patch unifie, avec son test, pour revue. Regle du worktree de recherche : jamais d'ecriture
dans le repo principal.
"""
import os
import subprocess
import sys

HOME = os.path.expanduser("~")
SRC = f"{HOME}/projects/trading-bridge/trading-backtest/src/main/java/com/martinfou/trading/backtest/SwapCalculator.java"
TEST = f"{HOME}/projects/trading-bridge/trading-backtest/src/test/java/com/martinfou/trading/backtest/SwapCalculatorTest.java"
OUT = f"{HOME}/projects/tb-research/docs/patches/2026-10-13-swap-weekend-rollovers.patch"

OLD_LOOP1 = """        while (current.isBefore(toNY)) {
            DayOfWeek dow = current.getDayOfWeek();
            // Wednesday = triple swap
            if (dow == DayOfWeek.WEDNESDAY) {
                totalDays += 3;
            } else {
                totalDays += 1;
            }
            current = current.plusDays(1);
        }"""

NEW_LOOP1 = """        while (current.isBefore(toNY)) {
            DayOfWeek dow = current.getDayOfWeek();
            // Le marche FX est FERME le week-end : aucun rollover samedi/dimanche. Le week-end
            // est porte par le rollover du MERCREDI (spot T+2 : value date vendredi -> lundi
            // = 3 jours). Compter aussi samedi et dimanche facturait 9 jours pour 7.
            if (dow == DayOfWeek.WEDNESDAY) {
                totalDays += 3;
            } else if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                totalDays += 1;
            }
            current = current.plusDays(1);
        }"""

OLD_LOOP2 = """        double total = 0.0;
        while (current.isBefore(toNY)) {
            int weight = current.getDayOfWeek() == DayOfWeek.WEDNESDAY ? 3 : 1;
            double[] r = byYear.get(current.getYear());
            if (r == null) r = byYear.get(-1);           // repli : dernière année connue
            if (r != null) {
                double pipRate = side == Order.Side.BUY ? r[0] : r[1];
                total += pipRate * pipValueInUSD * weight;
            }
            current = current.plusDays(1);
        }"""

NEW_LOOP2 = """        double total = 0.0;
        while (current.isBefore(toNY)) {
            // Meme regle que countRollovers : pas de rollover le week-end.
            DayOfWeek dow = current.getDayOfWeek();
            int weight = dow == DayOfWeek.WEDNESDAY ? 3
                : (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? 0 : 1;
            double[] r = byYear.get(current.getYear());
            if (r == null) r = byYear.get(-1);           // repli : dernière année connue
            if (r != null && weight > 0) {
                double pipRate = side == Order.Side.BUY ? r[0] : r[1];
                total += pipRate * pipValueInUSD * weight;
            }
            current = current.plusDays(1);
        }"""

NEW_TESTS = """
    @Test
    void weekendRolloverIsNotCounted() {
        // Vendredi 16:00 ET -> lundi 04:00 ET : le marche ne facture QUE le roll de vendredi
        // (value date ven -> mer). L'ancien compteur en facturait 3 (ven + sam + dim).
        assertEquals(1, SwapCalculator.countRollovers(
            Instant.parse("2024-05-03T20:00:00Z"), Instant.parse("2024-05-06T08:00:00Z")));
    }

    @Test
    void fullWeekIsSevenDaysNotNine() {
        // 4 charges ordinaires + 1 triple (mercredi) = 7 nuits, pas 9.
        assertEquals(7, SwapCalculator.countRollovers(
            Instant.parse("2024-05-06T00:00:00Z"), Instant.parse("2024-05-13T00:00:00Z")));
    }

    @Test
    void weekdayRolloversAreUnchanged() {
        // Non-regression : lundi -> mardi = 1, mercredi -> jeudi = 3 (triple).
        assertEquals(1, SwapCalculator.countRollovers(
            Instant.parse("2024-05-06T12:00:00Z"), Instant.parse("2024-05-07T12:00:00Z")));
        assertEquals(3, SwapCalculator.countRollovers(
            Instant.parse("2024-05-08T12:00:00Z"), Instant.parse("2024-05-09T12:00:00Z")));
    }
}"""


def main():
    src = open(SRC, encoding="utf-8").read()
    for old, new, label in ((OLD_LOOP1, NEW_LOOP1, "countRollovers"), (OLD_LOOP2, NEW_LOOP2, "yearlySwap")):
        if old not in src:
            print(f"ECHEC: bloc {label} introuvable (le moteur a change depuis le 13 oct)")
            return 1
        src = src.replace(old, new, 1)
    fixed = "/tmp/SwapCalculator.fixed.java"
    open(fixed, "w", encoding="utf-8").write(src)

    test = open(TEST, encoding="utf-8").read()
    if "weekendRolloverIsNotCounted" in test:
        print("(tests deja presents)")
    else:
        assert test.rstrip().endswith("}"), "fin de fichier de test inattendue"
        test = test.rstrip()[:-1].rstrip() + "\n" + NEW_TESTS + "\n"
    ftest = "/tmp/SwapCalculatorTest.fixed.java"
    open(ftest, "w", encoding="utf-8").write(test)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Correctif propose — SwapCalculator : le week-end n'a pas de rollover\n")
        f.write("# Mesure du 13 oct 2026 (56e) : scripts/rollover-daycount.py\n")
        f.write("# Appliquer depuis la racine de trading-bridge :\n")
        f.write("#   git apply docs/patches/2026-10-13-swap-weekend-rollovers.patch\n")
        f.write("#   ./mvnw -pl trading-backtest test -Dtest=SwapCalculatorTest\n\n")
        subprocess.run(["diff", "-u", "--label", "a/trading-backtest/src/main/java/com/martinfou/trading/backtest/SwapCalculator.java",
                        "--label", "b/trading-backtest/src/main/java/com/martinfou/trading/backtest/SwapCalculator.java",
                        SRC, fixed], stdout=f)
        subprocess.run(["diff", "-u", "--label", "a/trading-backtest/src/test/java/com/martinfou/trading/backtest/SwapCalculatorTest.java",
                        "--label", "b/trading-backtest/src/test/java/com/martinfou/trading/backtest/SwapCalculatorTest.java",
                        TEST, ftest], stdout=f)
    print("PATCH ECRIT ->", OUT)
    print(open(OUT, encoding="utf-8").read()[:2500])
    return 0


if __name__ == "__main__":
    sys.exit(main())
