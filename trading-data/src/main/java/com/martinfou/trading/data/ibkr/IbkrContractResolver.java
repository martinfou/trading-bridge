package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves trading symbols into Interactive Brokers contract parameters:
 * CME Futures (FUT on CME), US Equities (STK on SMART), and Forex (CASH on IDEALPRO).
 */
public final class IbkrContractResolver {

    public enum SecType {
        FUT,
        STK,
        CASH
    }

    public record IbkrContractDetails(
        String symbol,
        SecType secType,
        String exchange,
        String primaryExchange,
        String currency,
        double multiplier,
        String lastTradeDateOrContractMonth // YYYYMM for futures, null for stocks/forex
    ) {}

    private static final Set<String> KNOWN_EQUITIES = Set.of(
        "IWM", "MDY", "AAPL", "MSFT", "SPY", "QQQ", "AMZN", "NVDA", "TSLA", "GOOGL", "META"
    );

    private IbkrContractResolver() {}

    public static IbkrContractDetails resolve(String rawSymbol) {
        return resolve(rawSymbol, LocalDate.now(ZoneOffset.UTC));
    }

    public static IbkrContractDetails resolve(String rawSymbol, LocalDate tradeDate) {
        Objects.requireNonNull(rawSymbol, "rawSymbol");
        String symbol = rawSymbol.trim().toUpperCase().replace('/', '_');

        // Check if CME Futures
        Optional<FuturesContract> futOpt = FuturesRegistry.find(symbol);
        if (futOpt.isPresent()) {
            FuturesContract contract = futOpt.get();
            CmeFuturesCalendar.ContractSpec spec = CmeFuturesCalendar.activeContract(contract.symbol(), tradeDate);
            String yyyymm = String.format("%04d%02d", spec.year(), spec.quarterMonth().monthValue());
            return new IbkrContractDetails(
                contract.symbol(),
                SecType.FUT,
                "CME",
                "CME",
                "USD",
                contract.multiplier(),
                yyyymm
            );
        }

        // Check if US Equity
        if (KNOWN_EQUITIES.contains(symbol) || (!symbol.contains("_") && symbol.length() <= 5)) {
            return new IbkrContractDetails(
                symbol,
                SecType.STK,
                "SMART",
                "ISLAND",
                "USD",
                1.0,
                null
            );
        }

        // Default to Forex CASH on IDEALPRO
        String base = symbol.contains("_") ? symbol.split("_")[0] : symbol.substring(0, 3);
        String quote = symbol.contains("_") ? symbol.split("_")[1] : symbol.substring(3);
        return new IbkrContractDetails(
            base,
            SecType.CASH,
            "IDEALPRO",
            null,
            quote,
            1.0,
            null
        );
    }
}
