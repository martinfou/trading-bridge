package com.martinfou.trading.data.ibkr;

import java.util.Map;

/**
 * Full account reading for reporting ({@code reqAccountSummary} with {@code $LEDGER:ALL}).
 *
 * <p>Every amount in {@code values} is in {@code baseCurrency}; a tag IB did not send is absent, never 0.
 * {@code exchangeRates} maps a currency to its rate into the base currency (USD -&gt; 1.37 for a CAD account),
 * which is how a USD-denominated realized P&amp;L is converted without a second FX source.</p>
 */
public record IbkrAccountDetails(
    String accountId,
    String baseCurrency,
    Map<String, Double> values,
    Map<String, Double> exchangeRates
) {

    public static final String NET_LIQUIDATION = "NetLiquidation";
    public static final String TOTAL_CASH = "TotalCashValue";
    public static final String BUYING_POWER = "BuyingPower";
    public static final String AVAILABLE_FUNDS = "AvailableFunds";
    public static final String INIT_MARGIN = "InitMarginReq";
    public static final String MAINT_MARGIN = "MaintMarginReq";
    public static final String GROSS_POSITION_VALUE = "GrossPositionValue";
    public static final String UNREALIZED_PNL = "UnrealizedPnL";
    public static final String REALIZED_PNL = "RealizedPnL";

    public IbkrAccountDetails {
        values = values == null ? Map.of() : Map.copyOf(values);
        exchangeRates = exchangeRates == null ? Map.of() : Map.copyOf(exchangeRates);
    }

    /** The value of {@code tag} in base currency, or {@code null} when IB did not report it. */
    public Double value(String tag) {
        return values.get(tag);
    }

    /**
     * Rate converting {@code currency} into the base currency, or {@code null} when unknown.
     * The base currency itself always converts at 1.
     */
    public Double rateToBase(String currency) {
        if (currency == null || currency.isBlank() || currency.equalsIgnoreCase(baseCurrency)) {
            return 1.0;
        }
        return exchangeRates.get(currency.toUpperCase());
    }
}
