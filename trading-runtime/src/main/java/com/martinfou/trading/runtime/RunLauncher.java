package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.util.List;
import java.util.function.Consumer;

/** Builds {@link RunContext} with catalog resolution (composition layer). */
final class RunLauncher {

    private RunLauncher() {}

    static RunContext create(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double capital,
        EventStore eventStore
    ) {
        Consumer<RunEvent> listener = event -> eventStore.append(runId, event);
        return RunContext.forStrategy(
            strategyId,
            StrategyCatalog.create(strategyId, symbol),
            symbol,
            mode,
            bars,
            capital,
            listener);
    }
}
