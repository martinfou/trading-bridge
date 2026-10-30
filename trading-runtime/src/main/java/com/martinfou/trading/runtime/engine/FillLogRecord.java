package com.martinfou.trading.runtime.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Single structured fill record emitted in both {@code backtest} and {@code paper}/{@code live}
 * execution modes (PRD §5 F4, Decision D10).
 *
 * <p>Schema matches PRD §5 F4 verbatim:
 * {@code strategy}, {@code symbol}, {@code side}, {@code quantity}, {@code requestedPrice},
 * {@code filledPrice}, {@code computedSlippage}, {@code commission}, {@code timestamp}, {@code mode}.</p>
 */
public record FillLogRecord(
    @JsonProperty("strategy") String strategy,
    @JsonProperty("symbol") String symbol,
    @JsonProperty("side") String side,
    @JsonProperty("quantity") double quantity,
    @JsonProperty("requestedPrice") double requestedPrice,
    @JsonProperty("filledPrice") double filledPrice,
    @JsonProperty("computedSlippage") double computedSlippage,
    @JsonProperty("commission") double commission,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("mode") String mode
) {
    public FillLogRecord {
        Objects.requireNonNull(strategy, "strategy is required");
        Objects.requireNonNull(symbol, "symbol is required");
        Objects.requireNonNull(side, "side is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(mode, "mode is required");
    }

    /**
     * Computes signed price slippage from the trader's perspective (positive = worse fill than requested,
     * negative = price improvement, 0 = exact fill).
     *
     * <ul>
     *   <li>{@code BUY}: {@code filledPrice - requestedPrice}</li>
     *   <li>{@code SELL}: {@code requestedPrice - filledPrice}</li>
     * </ul>
     */
    public static double computeSlippage(String side, double requestedPrice, double filledPrice) {
        if (requestedPrice <= 0.0 || filledPrice <= 0.0 || !Double.isFinite(requestedPrice) || !Double.isFinite(filledPrice)) {
            return 0.0;
        }
        if ("BUY".equalsIgnoreCase(side)) {
            return roundPriceDelta(filledPrice - requestedPrice);
        } else if ("SELL".equalsIgnoreCase(side)) {
            return roundPriceDelta(requestedPrice - filledPrice);
        }
        return roundPriceDelta(Math.abs(filledPrice - requestedPrice));
    }

    private static double roundPriceDelta(double delta) {
        return Math.round(delta * 1_000_000.0) / 1_000_000.0;
    }
}

