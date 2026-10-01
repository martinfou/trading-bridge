package com.martinfou.trading.core;

import java.util.List;
import java.util.Optional;

public interface Strategy {
    String name();

    /**
     * Called on every completed bar.
     * Use {@code bar.close()} for entry/exit conditions — the engine guarantees
     * it will fill orders at the next bar's open, so current bar data is safe.
     *
     * For indicator computation, use the HISTORY passed by the engine via
     * {@link #getEngineHistory()}. This history NEVER includes the current bar,
     * making look-ahead bias architecturally impossible.
     */
    void onBar(Bar bar);
    void onTick(double bid, double ask, long volume);
    List<Order> getPendingOrders();
    void reset();

    /**
     * Returns the engine-managed bar history for safe indicator computation.
     * This history CONTAINS the bar passed to {@link #onBar(Bar)} — meaning
     * you ALREADY have the current bar. Compute indicators on the ENGINE's
     * history or on your own history BEFORE adding the current bar.
     *
     * @deprecated Use engineHistory instead of {@code history.add(bar)}.
     *   Engine history is populated BEFORE onBar() is called, so indicators
     *   computed on engineHistory WILL include the current bar — you must
     *   still call {@code history.add(bar)} AFTER indicator computation.
     *   To use the engine's past-only history for look-ahead-safe computation,
     *   compute on {@code engineHistory.subList(0, engineHistory.size() - 1)}.
     */
    default List<Bar> getEngineHistory() { return List.of(); }

    default void onSentiment(java.util.Map<String, Object> sentiment) {
        // Default no-op for backward compatibility
    }

    @SuppressWarnings("unchecked")
    default Optional<List<Bar>> getHistory() {
        try {
            java.lang.reflect.Field field = this.getClass().getDeclaredField("history");
            field.setAccessible(true);
            return Optional.ofNullable((List<Bar>) field.get(this));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    default void syncPosition(Order.Side side, double quantity, double sl, double tp) {
        try {
            boolean inTrade = (side != null && quantity > 0);
            setFieldValueOpt("inTrade", inTrade);
            setFieldValueOpt("positionSide", side);
            setFieldValueOpt("tradeDirection", side);
            setFieldValueOpt("positionUnits", quantity);
            setFieldValueOpt("units", quantity);
            setFieldValueOpt("stopLoss", sl);
            setFieldValueOpt("takeProfit", tp);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Reconcile this strategy's internal instrument with the one the runner resolved from
     * {@code live-config.json}. A strategy that filters bars on {@code bar.symbol()} (or pins its own
     * pair in an order) must track the resolved instrument, otherwise it silently drops every bar or
     * trades a stale pair — the "new silent failure" that would otherwise be introduced by resolving
     * the instrument from config instead of the display name. The default mirrors
     * {@link #syncPosition}: it reflection-sets a {@code symbol} field when one exists. A {@code final}
     * field is not settable here (the reflective set is a no-op); such a strategy is made non-final or
     * overrides this method. Backward compatible: strategies without a {@code symbol} field, or that do
     * not filter bars, are unaffected.
     */
    default void reconcileInstrument(String oandaSymbol) {
        if (oandaSymbol == null || oandaSymbol.isBlank()) return;
        setFieldValueOpt("symbol", oandaSymbol);
    }

    private void setFieldValueOpt(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = this.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            if (value == null && field.getType().isPrimitive()) return;
            if (field.getType() == double.class && value instanceof Number) {
                field.setDouble(this, ((Number)value).doubleValue());
            } else if (field.getType() == int.class && value instanceof Number) {
                field.setInt(this, ((Number)value).intValue());
            } else if (field.getType() == long.class && value instanceof Number) {
                field.setLong(this, ((Number)value).longValue());
            } else {
                field.set(this, value);
            }
        } catch (Exception e) {
            // Ignore if field doesn't exist
        }
    }
}
