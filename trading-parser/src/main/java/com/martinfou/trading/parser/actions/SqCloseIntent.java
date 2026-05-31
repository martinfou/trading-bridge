package com.martinfou.trading.parser.actions;

import java.util.Optional;

/** Parsed {@code CloseAllPositions} action (story 2-8). */
public record SqCloseIntent(
    String actionKey,
    SqCloseDirection direction,
    Optional<String> magicNumberVariable
) {}
