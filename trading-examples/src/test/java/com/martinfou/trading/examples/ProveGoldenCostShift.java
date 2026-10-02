package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RealCostModel;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.nio.file.Path;
import java.util.List;

/**
 * Dev utility — attributes the golden baseline shift to its two causes, on the golden windows themselves.
 *
 * <p>The golden baseline was last re-captured 2026-09-30 (fees/swaps realism). The cost model was corrected
 * again 2026-10-01 ({@code RealCostModel}, swap accounting), which moved the numbers again. This runner
 * decomposes the move so the re-capture is a measured decision, not a blessed diff.</p>
 *
 * <p>Five profiles are replayed on each window. The one that reproduces the golden is
 * <b>{@code legacyCostCorrectedSwap}</b> — the legacy execution cost with the corrected swap — which is
 * what {@code RunContexts.backtest} resolves. The explicit {@code RealCostModel} profile (measured
 * half-spread) is also printed, and does <b>not</b> reproduce the golden: the default path does not apply
 * the measured spread.</p>
 *
 * <p>Run it from {@code trading-examples}. Do <b>not</b> add {@code -am}: with {@code -am} the reactor
 * includes the aggregator and {@code exec:java} runs there, where the class is not compiled
 * ({@code ClassNotFoundException}).</p>
 *
 * <pre>{@code
 * mvn -q test-compile exec:java -pl trading-examples \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.martinfou.trading.examples.ProveGoldenCostShift
 * }</pre>
 */
public final class ProveGoldenCostShift {

    private ProveGoldenCostShift() {}

    private static final String SYMBOL = GoldenBacktestBaseline.SYMBOL;

    public static void main(String[] args) throws Exception {
        printAppliedCosts();

        List<Bar> subset = HistoricalDataLoader.loadPath(Path.of("data/ci/EUR_USD_H1_subset.csv"), SYMBOL);
        attribute("CI subset", subset);

        try {
            List<Bar> year = HistoricalDataLoader.loadYear(
                SYMBOL, GoldenBacktestBaseline.FULL_YEAR, HistoricalDataLoader.DEFAULT_BARS_DIR);
            if (!year.isEmpty()) {
                attribute("Full " + GoldenBacktestBaseline.FULL_YEAR, year);
            }
        } catch (Exception ex) {
            System.err.println("Skipping full year — " + ex.getMessage());
        }
    }

    /** The parameters actually applied to this symbol by the authoritative model. */
    private static void printAppliedCosts() {
        Double halfSpread = RealCostModel.HALF_SPREAD_PRICE.get(SYMBOL);
        Double mid = RealCostModel.REFERENCE_MIDS.get(SYMBOL);
        System.out.println("=== coûts appliqués à " + SYMBOL + " (RealCostModel, 2026-10-01) ===");
        System.out.println("  commission par trade : 0 (compte spread-only)");
        System.out.printf("  demi-spread par jambe: %s (unités de prix, mid de référence %s)%n", halfSpread, mid);
        double[] annual = RealCostModel.FINANCING_ANNUAL.get(SYMBOL);
        if (annual != null && mid != null) {
            System.out.printf("  swap annuel          : long %+.4f  short %+.4f (fraction annuelle)%n",
                annual[0], annual[1]);
            System.out.printf("  swap en pips/jour    : long %+.4f  short %+.4f%n",
                RealCostModel.swapPipsPerDay(annual[0], mid, SYMBOL),
                RealCostModel.swapPipsPerDay(annual[1], mid, SYMBOL));
        }
        System.out.println("  profil AVANT (celui des anciennes constantes) : commission 0.07 + slippageFixed 0.00005"
            + " (BacktestExecutionCost.DEFAULT, @Deprecated) et table de swap legacy");
    }

    /** Runs the golden strategy on one window under five cost profiles and prints the decomposition. */
    private static void attribute(String label, List<Bar> bars) {
        BacktestResult purePrice = run(bars, BacktestExecutionCost.ZERO, RunCostModelReal.SwapHypothesis.ZERO);
        BacktestResult spreadOnly = run(bars, RunCostModelReal.AFTER(SYMBOL), RunCostModelReal.SwapHypothesis.ZERO);
        BacktestResult swapOnly = run(bars, BacktestExecutionCost.ZERO, RunCostModelReal.SwapHypothesis.CORRECTED);
        BacktestResult measuredCostCorrectedSwap =
            run(bars, RunCostModelReal.AFTER(SYMBOL), RunCostModelReal.SwapHypothesis.CORRECTED);
        BacktestResult legacyCostCorrectedSwap =
            run(bars, RunCostModelReal.BEFORE(), RunCostModelReal.SwapHypothesis.CORRECTED);
        BacktestResult legacyCostLegacySwap =
            run(bars, RunCostModelReal.BEFORE(), RunCostModelReal.SwapHypothesis.LEGACY);

        double price = purePrice.totalPnl();
        double spreadCost = price - spreadOnly.totalPnl();
        double swapCost = price - swapOnly.totalPnl();

        System.out.printf("%n=== %s (%d barres) ===%n", label, bars.size());
        System.out.printf("  trades : legacy=%d  chemin du golden=%d  %s%n",
            legacyCostLegacySwap.totalTrades(), legacyCostCorrectedSwap.totalTrades(),
            legacyCostLegacySwap.totalTrades() == legacyCostCorrectedSwap.totalTrades()
                ? "(IDENTIQUES : seuls les coûts ont bougé, pas les décisions)"
                : "(DIFFÉRENTS — la stratégie a changé d'avis)");
        System.out.printf("  prix pur (aucun coût)            : PnL %10.4f  trades %d%n",
            price, purePrice.totalTrades());
        System.out.printf("  [ANCIENNES constantes] legacy + swap legacy     : PnL %10.4f%n",
            legacyCostLegacySwap.totalPnl());
        System.out.printf("  [NOUVELLES constantes] legacy + swap corrigé    : PnL %10.4f  <-- chemin par défaut du golden%n",
            legacyCostCorrectedSwap.totalPnl());
        System.out.printf("  profil RealCostModel explicite (spread mesuré)  : PnL %10.4f  <-- PAS le chemin du golden%n",
            measuredCostCorrectedSwap.totalPnl());
        System.out.printf("  coût spread (mesuré, explicite)  : %10.4f%n", spreadCost);
        System.out.printf("  coût swap (corrigé)              : %10.4f%n", swapCost);
        System.out.printf("  coût total (profil explicite)    : %10.4f  (%.0f%% du prix pur)%n",
            spreadCost + swapCost, price != 0 ? (spreadCost + swapCost) / Math.abs(price) * 100 : 0);
        System.out.printf("  écart anciennes/nouvelles const. : %10.4f%n",
            legacyCostCorrectedSwap.totalPnl() - legacyCostLegacySwap.totalPnl());
        System.out.printf("  swap comptabilisé : corrigé %10.4f  legacy %10.4f%n",
            legacyCostCorrectedSwap.totalSwap(), legacyCostLegacySwap.totalSwap());
    }

    private static BacktestResult run(List<Bar> bars, BacktestExecutionCost cost,
                                      RunCostModelReal.SwapHypothesis swap) {
        try {
            switch (swap) {
                case LEGACY -> {
                    double[] legacy = RunCostModelReal.LEGACY_SWAP.get(SYMBOL);
                    if (legacy != null) {
                        SwapCalculator.setRateOverride(SYMBOL, legacy[0], legacy[1]);
                    }
                }
                case ZERO -> SwapCalculator.setRateOverride(SYMBOL, 0.0, 0.0);
                case CORRECTED -> SwapCalculator.clearRateOverride();
            }
            return RunContext.forStrategy(
                null,
                GoldenBacktestBaseline.STRATEGY_ID,
                StrategyCatalog.create(GoldenBacktestBaseline.STRATEGY_ID, SYMBOL),
                SYMBOL,
                RunMode.BACKTEST,
                bars,
                GoldenBacktestBaseline.INITIAL_CAPITAL,
                null,
                cost).run();
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }
}
