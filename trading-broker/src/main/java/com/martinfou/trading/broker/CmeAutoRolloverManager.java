package com.martinfou.trading.broker;

import com.martinfou.trading.core.CmeContractExpiryCalculator;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Autonomous CME quarterly futures rollover manager (Story 46.4).
 *
 * <p>Monitors volume migration between expiring front-month and incoming quarterly contracts,
 * generates calendar spread execution plans, and updates position cost basis without human intervention.</p>
 */
public final class CmeAutoRolloverManager {

    private static final Logger log = LoggerFactory.getLogger(CmeAutoRolloverManager.class);

    public record RolloverPlan(
        boolean shouldRoll,
        String currentSymbol,
        String targetSymbol,
        double calendarSpreadPrice,
        double adjustedEntryPrice,
        String reason
    ) {}

    private int consecutiveCrossoverBars = 0;

    /**
     * Evaluates whether an open futures position should execute a quarterly rollover.
     */
    public RolloverPlan evaluateRollover(
        Position currentPosition,
        LocalDate currentDate,
        double frontMonthVolume,
        double nextMonthVolume,
        double calendarSpreadMidPrice,
        String targetContractSymbol
    ) {
        Objects.requireNonNull(currentPosition, "currentPosition is required");
        if (currentDate == null) currentDate = LocalDate.now();

        boolean inWindow = CmeContractExpiryCalculator.isRollWindow(currentDate);
        if (!inWindow) {
            consecutiveCrossoverBars = 0;
            return new RolloverPlan(false, currentPosition.symbol(), null, 0.0, currentPosition.entryPrice(), "OUTSIDE_ROLL_WINDOW");
        }

        if (nextMonthVolume > frontMonthVolume) {
            consecutiveCrossoverBars++;
            log.info("Volume crossover detected for {}: Next month volume ({}) > Front ({}) [Consecutive bars: {}]",
                currentPosition.symbol(), nextMonthVolume, frontMonthVolume, consecutiveCrossoverBars);
        } else {
            consecutiveCrossoverBars = 0;
        }

        if (consecutiveCrossoverBars >= 2) {
            String target = (targetContractSymbol != null && !targetContractSymbol.isBlank())
                ? targetContractSymbol
                : currentPosition.symbol() + "_NEXT";

            double adjustedPrice = FuturesRegistry.quantizePrice(
                currentPosition.symbol(),
                currentPosition.entryPrice() + calendarSpreadMidPrice
            );

            log.info("ROLLOVER TRIGGERED for {}: Rolling to {} @ spread differential {} (New entry basis: {})",
                currentPosition.symbol(), target, calendarSpreadMidPrice, adjustedPrice);

            return new RolloverPlan(true, currentPosition.symbol(), target, calendarSpreadMidPrice, adjustedPrice, "VOLUME_CROSSOVER_CONFIRMED");
        }

        return new RolloverPlan(false, currentPosition.symbol(), null, 0.0, currentPosition.entryPrice(), "AWAITING_VOLUME_CROSSOVER_CONFIRMATION");
    }

    public void reset() {
        consecutiveCrossoverBars = 0;
    }
}
