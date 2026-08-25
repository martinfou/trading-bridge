package com.martinfou.trading.strategies;

import com.martinfou.trading.core.FuturesContract;
import com.martinfou.trading.core.FuturesRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * Volatility-adaptive position sizer and stop-loss calculator for CME Futures (MES, MNQ, M2K, EMD, ES, NQ).
 *
 * <p>Calculates dynamic point stops based on Average True Range (ATR) multiples and snaps them
 * to the contract's discrete exchange tick boundaries. Sizes discrete integer contracts to keep
 * account risk bounded by the target risk budget.</p>
 */
public final class AtrFuturesPositionSizer {

    public record SizingResult(
        int contracts,
        double stopDistancePoints,
        double riskAmountUsd,
        double requiredInitialMarginUsd,
        boolean marginFeasible
    ) {}

    private AtrFuturesPositionSizer() {}

    /**
     * Calculates volatility-adaptive stop distance in index points, quantized to the nearest valid tick.
     *
     * @param symbol     futures symbol (e.g. "MES")
     * @param atrValue   current ATR value in points
     * @param atrMultiple multiplier (e.g. 1.5x ATR, 2.0x ATR)
     * @return quantized stop distance in index points (minimum 1 tick)
     */
    public static double calculateStopDistance(String symbol, double atrValue, double atrMultiple) {
        if (atrValue <= 0 || atrMultiple <= 0) {
            return FuturesRegistry.find(symbol).map(FuturesContract::minTick).orElse(0.25);
        }
        Optional<FuturesContract> fut = FuturesRegistry.find(symbol);
        double rawStop = atrValue * atrMultiple;
        if (fut.isPresent()) {
            FuturesContract contract = fut.get();
            double quantized = contract.quantizePrice(rawStop);
            return Math.max(contract.minTick(), quantized);
        }
        return Math.max(0.0001, rawStop);
    }

    /**
     * Calculates discrete integer contracts and margin requirements for a given risk budget.
     *
     * @param symbol             futures symbol (e.g. "MES")
     * @param accountEquity      available equity in USD
     * @param riskPercentage     fraction of equity to risk (e.g. 0.01 for 1%)
     * @param stopDistancePoints quantized stop distance in index points
     * @return {@link SizingResult} with sizing metrics and margin feasibility
     */
    public static SizingResult calculatePositionSize(
        String symbol,
        double accountEquity,
        double riskPercentage,
        double stopDistancePoints
    ) {
        FuturesContract contract = FuturesRegistry.find(symbol)
            .orElse(new FuturesContract("MES", "Micro E-mini S&P 500", "CME", "USD", 5.0, 0.25, 1.25, 1200.0, 1000.0));

        double riskBudgetUsd = Math.max(0.0, accountEquity * Math.max(0.0, riskPercentage));
        double dollarRiskPerContract = Math.max(contract.tickValue(), stopDistancePoints * contract.multiplier());

        int contracts = (int) Math.floor(riskBudgetUsd / dollarRiskPerContract);
        // Minimum 1 contract if risk budget allows or clamped to 1
        if (contracts < 1 && accountEquity >= contract.initialMargin()) {
            contracts = 1;
        }

        // Cap by available initial margin so integer sizing can never violate CME margin
        // (e.g. a wide risk budget with a tight stop would otherwise size contracts whose
        //  required initial margin exceeds account equity).
        int marginCapped = (int) Math.floor(accountEquity / contract.initialMargin());
        if (marginCapped < 1) {
            contracts = 0; // cannot afford even 1 contract of initial margin
        } else {
            contracts = Math.min(contracts, marginCapped);
        }

        double totalRiskUsd = contracts * dollarRiskPerContract;
        double requiredMargin = contracts * contract.initialMargin();
        boolean marginFeasible = contracts > 0 && accountEquity >= requiredMargin;

        return new SizingResult(
            contracts,
            stopDistancePoints,
            totalRiskUsd,
            requiredMargin,
            marginFeasible
        );
    }
}
