package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.config.GlobalExitConfig;
import com.martinfou.trading.parser.config.PositionSizingConfig;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.config.StrategyParameter;

import java.util.List;
import java.util.Map;

final class StrategyConfigTestSupport {

    private StrategyConfigTestSupport() {}

    static StrategyConfig configWithBoolean(String name, String value) {
        return new StrategyConfig(
            "1", "", "MetaTrader",
            new PositionSizingConfig("FixedSize", Map.of()),
            new GlobalExitConfig(true, 0, 0),
            Map.of(name, new StrategyParameter(name, name, "boolean", value, "")),
            List.of(), List.of(), List.of(), List.of()
        );
    }
}
