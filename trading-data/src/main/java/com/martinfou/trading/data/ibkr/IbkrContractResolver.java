package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.martinfou.trading.core.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves trading symbols into Interactive Brokers contract parameters:
 * CME-group Futures (FUT on the contract's listing exchange), US Equities (STK on SMART), and Forex (CASH on IDEALPRO).
 *
 * <p>Resolution order: explicit prefix ({@code FUT:}, {@code STK:}, {@code CASH:}) → {@link FuturesRegistry}
 * → known/short ticker (STK) → forex pair.</p>
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
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');

        String forced = null;
        int colon = symbol.indexOf(':');
        if (colon > 0) {
            forced = symbol.substring(0, colon);
            symbol = symbol.substring(colon + 1).trim();
        }

        if (forced == null || forced.equals("FUT")) {
            Optional<FuturesContract> futOpt = FuturesRegistry.find(symbol);
            if (futOpt.isPresent()) {
                return future(futOpt.get(), tradeDate);
            }
            if ("FUT".equals(forced)) {
                throw new IllegalArgumentException("Unknown futures symbol (register it in FuturesRegistry): " + symbol);
            }
        }

        boolean looksLikeStock = KNOWN_EQUITIES.contains(symbol) || (!symbol.contains("_") && symbol.length() <= 5);
        if ("STK".equals(forced) || (forced == null && looksLikeStock)) {
            // primaryExchange intentionally null: SMART routing + USD is unambiguous for US listings, and a wrong
            // primary exchange (e.g. ISLAND for an ARCA ETF) makes IB return "No security definition".
            return new IbkrContractDetails(symbol, SecType.STK, "SMART", null, "USD", 1.0, null);
        }

        if (forced != null && !forced.equals("CASH")) {
            throw new IllegalArgumentException("Unknown security type prefix: " + forced);
        }
        String base = symbol.contains("_") ? symbol.split("_")[0] : symbol.substring(0, 3);
        String quote = symbol.contains("_") ? symbol.split("_")[1] : symbol.substring(3);
        return new IbkrContractDetails(base, SecType.CASH, "IDEALPRO", null, quote, 1.0, null);
    }

    private static IbkrContractDetails future(FuturesContract contract, LocalDate tradeDate) {
        CmeFuturesCalendar.ContractSpec spec = CmeFuturesCalendar.activeContract(contract.symbol(), tradeDate);
        String yyyymm = String.format("%04d%02d", spec.year(), spec.quarterMonth().monthValue());
        String exchange = contract.exchange() == null || contract.exchange().isBlank() ? "CME" : contract.exchange();
        String currency = contract.currency() == null || contract.currency().isBlank() ? "USD" : contract.currency();
        return new IbkrContractDetails(
            contract.symbol(), SecType.FUT, exchange, null, currency, contract.multiplier(), yyyymm);
    }

    /** Builds an (unqualified) IB {@link Contract}; qualify it with {@code reqContractDetails} before trading. */
    public static Contract toIbContract(IbkrContractDetails details) {
        Contract c = new Contract();
        c.symbol(details.symbol());
        c.secType(details.secType().name());
        c.exchange(details.exchange());
        c.currency(details.currency());
        if (details.primaryExchange() != null) {
            c.primaryExch(details.primaryExchange());
        }
        if (details.secType() == SecType.FUT) {
            c.lastTradeDateOrContractMonth(details.lastTradeDateOrContractMonth());
            c.multiplier(formatMultiplier(details.multiplier()));
        }
        return c;
    }

    /**
     * Maps an IB contract (from positions / executions) back to the trading-bridge symbol:
     * FUT → root ({@code MES}), STK → ticker ({@code AAPL}), CASH → {@code EUR_USD}.
     */
    public static String toTradingSymbol(Contract contract) {
        if (contract == null || contract.symbol() == null) {
            return null;
        }
        String secType = contract.getSecType();
        if ("CASH".equals(secType)) {
            return contract.symbol() + "_" + contract.currency();
        }
        return contract.symbol();
    }

    /** IB reports futures {@code avgCost} including the multiplier; returns the per-unit price. */
    public static double perUnitPrice(Contract contract, double avgCost) {
        if (contract != null && "FUT".equals(contract.getSecType())) {
            double multiplier = parseMultiplier(contract.multiplier());
            if (multiplier > 0) {
                return avgCost / multiplier;
            }
        }
        return avgCost;
    }

    static double parseMultiplier(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatMultiplier(double multiplier) {
        return multiplier == Math.rint(multiplier) ? String.valueOf((long) multiplier) : String.valueOf(multiplier);
    }
}
