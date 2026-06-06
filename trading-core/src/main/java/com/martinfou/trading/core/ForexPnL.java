package com.martinfou.trading.core;

/** Converts raw price-difference P&amp;L into USD account currency for backtests. */
public final class ForexPnL {

    /** Fallback USD/JPY when no live rate is wired into the backtest engine. */
    public static final double DEFAULT_USD_JPY = 150.0;

    private ForexPnL() {}

    public static double pnlUsd(
        String symbol,
        Order.Side side,
        double entryPrice,
        double exitPrice,
        double quantity
    ) {
        return pnlUsd(symbol, side, entryPrice, exitPrice, quantity, DEFAULT_USD_JPY);
    }

    public static double pnlUsd(
        String symbol,
        Order.Side side,
        double entryPrice,
        double exitPrice,
        double quantity,
        double usdJpyRate
    ) {
        double raw = side == Order.Side.BUY
            ? (exitPrice - entryPrice) * quantity
            : (entryPrice - exitPrice) * quantity;
        if (isUsdJpy(symbol)) {
            return exitPrice > 0 ? raw / exitPrice : raw;
        }
        if (isJpyQuotedCross(symbol)) {
            double rate = usdJpyRate > 0 ? usdJpyRate : DEFAULT_USD_JPY;
            return raw / rate;
        }
        return raw;
    }

    /** XXX_JPY cross (e.g. GBP_JPY) — quote currency is JPY. */
    static boolean isJpyQuotedCross(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.toUpperCase().replace('/', '_');
        return normalized.endsWith("_JPY") && !normalized.startsWith("USD_");
    }

    /** USD/JPY — base is USD; raw move is in JPY per unit of base. */
    static boolean isUsdJpy(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.toUpperCase().replace('/', '_');
        return normalized.equals("USD_JPY");
    }
}
