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
            boolean wasInTrade = readInTrade();
            boolean inTrade = (side != null && quantity > 0);
            // The in-trade flag goes through the hierarchy-aware pair, so a strategy that inherits its
            // flag is flattened too; the read and the write share one scope on purpose (see readInTrade).
            // The other six fields keep the original concrete-class scope: widening THOSE would change
            // instrument reconciliation and stop/target handling, which is not what this change is about.
            writeBooleanOpt(fieldInHierarchy("inTrade"), inTrade);
            setFieldValueOpt("positionSide", side);
            setFieldValueOpt("tradeDirection", side);
            setFieldValueOpt("positionUnits", quantity);
            setFieldValueOpt("units", quantity);
            setFieldValueOpt("stopLoss", sl);
            setFieldValueOpt("takeProfit", tp);
            if (!inTrade && wasInTrade) {
                // The strategy believed it held a position and the execution layer says it does not:
                // the exit came from the venue, not from this strategy. Arm the cooldown the strategy's
                // own exit path would have armed (see onExternalClose). Guarded on that transition on
                // purpose: a flat notification that only confirms an exit the strategy decided itself
                // must not reset or extend a cooldown that is already running.
                onExternalClose();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Called when the execution layer learns from the BROKER that a position this strategy believed it
     * held is gone (stop-loss fill, take profit, manual close), i.e. the strategy did not decide the
     * exit itself.
     *
     * <p>A strategy arms its cooldown inside its own exit path, so an exit it did not decide used to
     * leave {@code cooldownBars} at zero and let it re-enter on the very next bar — the one moment the
     * cooldown exists for. Measured on the paper window (2026-10-02): {@code vwpreversion} was stopped
     * out by the broker at 07:57:37 UTC, flattened by the runner at 07:58:28, and re-entered at
     * 08:00:29 on the next H1 bar, same direction, at a fresh 0.6 % risk budget. A backtest never
     * produces that sequence, because every backtest exit goes through the strategy's own close, so
     * the window was observing a behaviour that had never been measured.
     *
     * <p>The default arms the cooldown from the strategy's own {@code COOLDOWN_BARS} constant when the
     * class declares one — the convention across this codebase (38 strategy classes) — and does
     * nothing otherwise, because a strategy without a cooldown has nothing to arm. The value is read
     * from the strategy rather than passed in, so there is exactly one source of truth per strategy.
     *
     * <p>The counter field is resolved by name, because this codebase uses two spellings for the same
     * thing: {@code cooldownBars} (38 classes) and {@code cooldownCounter} (1 class,
     * {@code ATRExpansionMomentumStrategy}). A third spelling is the one case this default cannot see,
     * which is why it is overridable: a strategy with a differently named counter, or a computed
     * cooldown, implements this method itself.
     */
    default void onExternalClose() {
        Integer declared = declaredCooldownBars();
        if (declared == null) return;
        // The counter can be declared on a base class, so resolve the FIELD across the hierarchy here.
        // setFieldValueOpt deliberately keeps its narrower scope: it also writes inTrade and symbol, and
        // widening those two is a behaviour change this change must not smuggle in (see readBooleanFieldOpt).
        java.lang.reflect.Field counter = fieldInHierarchy("cooldownBars");
        if (counter == null) counter = fieldInHierarchy("cooldownCounter");
        if (counter == null) return;
        writeIntOpt(counter, declared);
    }

    /** The declared field of that name on this class or any superclass, or {@code null} when absent. */
    private java.lang.reflect.Field fieldInHierarchy(String fieldName) {
        for (Class<?> c = this.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // Not on this class: keep walking up.
            }
        }
        return null;
    }

    /** Writes an {@code int}-backed field, reporting whether it landed; a non-int field is a silent no-op. */
    private boolean writeIntOpt(java.lang.reflect.Field field, int value) {
        try {
            field.setInt(this, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads the strategy's own {@code COOLDOWN_BARS} constant, walking up the hierarchy so an inherited
     * constant is found. Returns {@code null} when the class declares none (or declares a non-positive
     * one): "no cooldown" and "cooldown of zero bars" both mean nothing to arm. Read tolerantly on
     * purpose: {@code field.get(this)} also works for a static constant, and a {@code Number} box
     * ({@code Integer}, {@code long}) is accepted instead of assuming a primitive {@code int}.
     */
    private Integer declaredCooldownBars() {
        for (Class<?> c = this.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField("COOLDOWN_BARS");
                field.setAccessible(true);
                Object raw = field.get(this);
                if (!(raw instanceof Number number)) return null;
                int value = number.intValue();
                return value > 0 ? value : null;
            } catch (NoSuchFieldException e) {
                // Not on this class: a strategy may inherit the constant from a base class.
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Reads the strategy's in-trade flag, wherever it is declared — this class or a base class — or
     * {@code false} when the strategy keeps no flag under that name.
     *
     * <p>The scope is the whole hierarchy, and {@link #syncPosition} writes the flag through the same
     * scope: {@code false} here therefore means "the strategy did not believe it was in a position", and
     * the arming guard fires exactly when this call flipped that flag to false. Read and write must move
     * together — a narrower write would leave a strategy latched in-trade forever while the guard stayed
     * silent, and a narrower read would arm nothing for a strategy that IS flattened.
     */
    private boolean readInTrade() {
        java.lang.reflect.Field field = fieldInHierarchy("inTrade");
        if (field == null) return false;
        try {
            return field.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes a boolean field, doing nothing when the field is absent or is not a {@code boolean}. */
    private void writeBooleanOpt(java.lang.reflect.Field field, boolean value) {
        if (field == null || field.getType() != boolean.class) return;
        try {
            field.setBoolean(this, value);
        } catch (Exception e) {
            // A boolean field this instance refuses to write: leave it alone rather than half-apply.
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
