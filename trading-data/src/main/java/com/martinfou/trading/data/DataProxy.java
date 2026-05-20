package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import java.util.List;

/**
 * Data proxy abstraction. Used identically by backtesting and live trading —
 * only the implementation differs.
 *
 * <pre>
 *   Strategy (knows nothing about data source)
 *     │
 *     └─ DataProxy.getCandles(instrument, granularity, count)
 *           │
 *           ├── LocalDataProxy  →  .bars files  (backtesting)
 *           └── OandaDataProxy  →  OANDA REST   (live trading)
 * </pre>
 *
 * The backtest engine drives a Strategy through the same DataProxy interface
 * that the live runner uses. The strategy code is identical in both modes.
 */
public interface DataProxy {

    /**
     * Fetches historical OHLCV candles.
     *
     * @param instrument  instrument pair (e.g. "EUR_USD", "GBP_JPY")
     * @param granularity OANDA-style granularity (e.g. "H1", "M15", "D")
     * @param count       maximum number of candles to return (newest first)
     * @return list of bars, newest-first per OANDA convention
     * @throws Exception if the data source is unreachable or corrupt
     */
    List<Bar> getCandles(String instrument, String granularity, int count) throws Exception;

    /** Short human-readable tag for display (e.g. "local", "oanda"). */
    default String tag() { return getClass().getSimpleName(); }
}
