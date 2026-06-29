package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Trade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Story 37-11 / 37-12 / 37-13 / 37-14.
 * Statistical comparison engine comparing backtest baseline trades vs actual paper/live trades.
 */
public final class ComparisonEngine {

    public record ComparisonResult(
        double pearsonCorrelation,
        double ksStatistic,
        double ksPValue,
        boolean ksSignificant05, // p < 0.05
        boolean ksSignificant01, // p < 0.01
        boolean costDriftExceeded
    ) {}

    private ComparisonEngine() {}

    public static ComparisonResult compare(
        List<Trade> backtestTrades,
        List<Trade> actualTrades,
        double backtestCommission,
        double backtestSlippage,
        double actualCommissionPerTrade,
        double actualSlippagePct
    ) {
        if (backtestTrades == null) backtestTrades = List.of();
        if (actualTrades == null) actualTrades = List.of();

        // 1. Pearson Correlation of Equity Curves
        double pearson = calculatePearsonCorrelation(backtestTrades, actualTrades);

        // 2. Kolmogorov-Smirnov Distribution Test
        double ksStat = calculateKSStatistic(backtestTrades, actualTrades);
        int n1 = backtestTrades.size();
        int n2 = actualTrades.size();
        boolean ksSig05 = false;
        boolean ksSig01 = false;
        if (n1 > 0 && n2 > 0) {
            double factor = Math.sqrt((double) (n1 + n2) / (n1 * n2));
            ksSig05 = ksStat > (1.36 * factor);
            ksSig01 = ksStat > (1.63 * factor);
        }

        // 3. Cost Drift Tracking (commission & slippage)
        boolean costDrift = false;
        if (!backtestTrades.isEmpty() && !actualTrades.isEmpty()) {
            double btCommPerTrade = backtestCommission / backtestTrades.size();
            double btSlipPerTrade = backtestSlippage / backtestTrades.size();

            double actSlippageTotal = 0.0;
            for (Trade t : actualTrades) {
                actSlippageTotal += t.quantity() * t.entryPrice() * actualSlippagePct;
            }
            double actCommPerTrade = actualCommissionPerTrade;
            double actSlipPerTrade = actSlippageTotal / actualTrades.size();

            if (actCommPerTrade > 2 * btCommPerTrade || actSlipPerTrade > 2 * btSlipPerTrade) {
                costDrift = true;
            }
        }

        return new ComparisonResult(pearson, ksStat, ksSig01 ? 0.01 : (ksSig05 ? 0.05 : 0.50), ksSig05, ksSig01, costDrift);
    }

    private static double calculatePearsonCorrelation(List<Trade> bt, List<Trade> act) {
        if (bt.isEmpty() || act.isEmpty()) {
            return 0.0;
        }

        List<Double> btEquity = new ArrayList<>();
        double btCurrent = 10000.0;
        btEquity.add(btCurrent);
        for (Trade t : bt) {
            btCurrent += t.pnl();
            btEquity.add(btCurrent);
        }

        List<Double> actEquity = new ArrayList<>();
        double actCurrent = 10000.0;
        actEquity.add(actCurrent);
        for (Trade t : act) {
            actCurrent += t.pnl();
            actEquity.add(actCurrent);
        }

        int targetSize = Math.min(btEquity.size(), actEquity.size());
        if (targetSize < 2) {
            return 1.0;
        }

        List<Double> sampledBt = new ArrayList<>();
        double factor = (double) btEquity.size() / targetSize;
        for (int i = 0; i < targetSize; i++) {
            int idx = (int) Math.min(btEquity.size() - 1, Math.round(i * factor));
            sampledBt.add(btEquity.get(idx));
        }

        List<Double> sampledAct = new ArrayList<>();
        double actFactor = (double) actEquity.size() / targetSize;
        for (int i = 0; i < targetSize; i++) {
            int idx = (int) Math.min(actEquity.size() - 1, Math.round(i * actFactor));
            sampledAct.add(actEquity.get(idx));
        }

        double meanBt = sampledBt.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanAct = sampledAct.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double num = 0.0;
        double denBt = 0.0;
        double denAct = 0.0;
        for (int i = 0; i < targetSize; i++) {
            double d1 = sampledBt.get(i) - meanBt;
            double d2 = sampledAct.get(i) - meanAct;
            num += d1 * d2;
            denBt += d1 * d1;
            denAct += d2 * d2;
        }

        if (denBt == 0.0 || denAct == 0.0) {
            return 0.0;
        }
        return num / Math.sqrt(denBt * denAct);
    }

    private static double calculateKSStatistic(List<Trade> bt, List<Trade> act) {
        if (bt.isEmpty() || act.isEmpty()) {
            return 0.0;
        }
        List<Double> x1 = new ArrayList<>(bt.stream().map(Trade::pnl).toList());
        List<Double> x2 = new ArrayList<>(act.stream().map(Trade::pnl).toList());
        Collections.sort(x1);
        Collections.sort(x2);

        double maxDiff = 0.0;
        int i = 0;
        int j = 0;
        while (i < x1.size() || j < x2.size()) {
            double val;
            if (i < x1.size() && j < x2.size()) {
                val = Math.min(x1.get(i), x2.get(j));
            } else if (i < x1.size()) {
                val = x1.get(i);
            } else {
                val = x2.get(j);
            }

            while (i < x1.size() && x1.get(i) <= val) i++;
            while (j < x2.size() && x2.get(j) <= val) j++;

            double cdf1 = (double) i / x1.size();
            double cdf2 = (double) j / x2.size();
            maxDiff = Math.max(maxDiff, Math.abs(cdf1 - cdf2));
        }
        return maxDiff;
    }
}
