package com.martinfou.trading.parser.bridge;

import java.io.IOException;

/** StrategyQuant CLI binary or SQ_HOME could not be resolved (story 21-4). */
public final class SqCliNotFoundException extends IOException {

    public SqCliNotFoundException(String message) {
        super(message);
    }
}
