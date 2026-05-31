package com.martinfou.trading.parser.actions;

import java.util.List;

/** Pending SQ actions for one bar evaluation (story 2-8). */
public record SqBarActions(
    List<SqOrderIntent> entryOrders,
    List<SqCloseIntent> closeActions
) {
    public static SqBarActions empty() {
        return new SqBarActions(List.of(), List.of());
    }

    public boolean hasEntries() {
        return !entryOrders.isEmpty();
    }

    public boolean hasCloses() {
        return !closeActions.isEmpty();
    }
}
