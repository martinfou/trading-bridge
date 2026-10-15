package com.martinfou.trading.core;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Unified resolver for asset valuation models across Forex, CME Futures, and US Equities.
 */
public final class AssetValuationRegistry {

    private static final Set<String> KNOWN_EQUITIES = Set.of(
        "IWM", "MDY", "AAPL", "MSFT", "SPY", "QQQ", "AMZN", "NVDA", "TSLA", "GOOGL", "META"
    );

    private AssetValuationRegistry() {}

    public static AssetValuationModel resolve(String symbol) {
        return resolve(symbol, null);
    }

    public static AssetValuationModel resolve(String symbol, AssetClass explicitClass) {
        if (symbol == null || symbol.isBlank()) {
            return new ForexValuationModel("EUR_USD");
        }
        String normalized = symbol.trim().toUpperCase().replace('/', '_');

        if (explicitClass == AssetClass.FUTURES) {
            FuturesContract contract = FuturesRegistry.find(normalized)
                .orElse(new FuturesContract(normalized, normalized, "CME", "USD", 5.0, 0.25, 1.25, 1000.0, 800.0));
            return new FuturesValuationModel(contract);
        }

        if (explicitClass == AssetClass.EQUITY) {
            return new StockValuationModel(normalized);
        }

        if (explicitClass == AssetClass.FOREX) {
            return new ForexValuationModel(normalized);
        }

        // Auto-detect by symbol
        Optional<FuturesContract> fut = FuturesRegistry.find(normalized);
        if (fut.isPresent()) {
            return new FuturesValuationModel(fut.get());
        }

        if (KNOWN_EQUITIES.contains(normalized)) {
            return new StockValuationModel(normalized);
        }

        return new ForexValuationModel(normalized);
    }

    public static double calculatePnL(
        String symbol,
        Order.Side side,
        double entryPrice,
        double exitPrice,
        double quantity,
        double usdJpyRate
    ) {
        return resolve(symbol).calculatePnL(side, entryPrice, exitPrice, quantity, usdJpyRate);
    }

    public static double calculatePnL(
        String symbol,
        Order.Side side,
        double entryPrice,
        double exitPrice,
        double quantity
    ) {
        return calculatePnL(symbol, side, entryPrice, exitPrice, quantity, ForexPnL.DEFAULT_USD_JPY);
    }
}
