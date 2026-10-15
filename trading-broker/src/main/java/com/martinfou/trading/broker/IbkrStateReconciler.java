package com.martinfou.trading.broker;

import com.martinfou.trading.core.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Post-reconnect state reconciler (Story 46.1).
 *
 * <p>Synchronizes open broker positions and active orders with local memory/database
 * after socket drops or gateway daily reboots.</p>
 */
public final class IbkrStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(IbkrStateReconciler.class);

    public record ReconciliationReport(
        int brokerPositionCount,
        int localPositionCount,
        int matchedPositions,
        List<Position> reconciledPositions,
        boolean inSync
    ) {}

    private final Supplier<List<Position>> brokerPositionSupplier;
    private final Supplier<List<Position>> localPositionSupplier;
    private final Consumer<List<Position>> stateUpdateConsumer;

    public IbkrStateReconciler(
        Supplier<List<Position>> brokerPositionSupplier,
        Supplier<List<Position>> localPositionSupplier,
        Consumer<List<Position>> stateUpdateConsumer
    ) {
        this.brokerPositionSupplier = Objects.requireNonNull(brokerPositionSupplier, "brokerPositionSupplier required");
        this.localPositionSupplier = Objects.requireNonNull(localPositionSupplier, "localPositionSupplier required");
        this.stateUpdateConsumer = Objects.requireNonNull(stateUpdateConsumer, "stateUpdateConsumer required");
    }

    public ReconciliationReport reconcile() {
        log.info("Starting IBKR state reconciliation...");
        List<Position> brokerPositions = brokerPositionSupplier.get();
        List<Position> localPositions = localPositionSupplier.get();

        if (brokerPositions == null) brokerPositions = List.of();
        if (localPositions == null) localPositions = List.of();

        List<Position> out = new ArrayList<>(brokerPositions);
        int matched = 0;

        for (Position bp : brokerPositions) {
            for (Position lp : localPositions) {
                if (bp.symbol().equalsIgnoreCase(lp.symbol()) && bp.side() == lp.side()) {
                    matched++;
                    break;
                }
            }
        }

        boolean inSync = (brokerPositions.size() == localPositions.size()) && (matched == brokerPositions.size());

        if (!inSync) {
            log.warn("State mismatch detected (Broker pos: {}, Local pos: {}). Syncing local state to broker...",
                brokerPositions.size(), localPositions.size());
            stateUpdateConsumer.accept(out);
        } else {
            log.info("State perfectly in sync ({} active positions).", matched);
        }

        return new ReconciliationReport(brokerPositions.size(), localPositions.size(), matched, out, inSync);
    }
}
