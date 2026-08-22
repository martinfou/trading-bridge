package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.CmeFuturesCalendar;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Builds continuous price series for CME futures across quarterly contract cycles,
 * with support for unadjusted, Panama Canal additive, or ratio adjustments.
 */
public final class FuturesContinuousSeriesBuilder {

    public enum AdjustmentMode {
        NONE,
        PANAMA_CANAL,
        RATIO
    }

    private FuturesContinuousSeriesBuilder() {}

    public static List<Bar> buildContinuous(
        String rootSymbol,
        Map<String, List<Bar>> contractBars,
        AdjustmentMode mode
    ) {
        if (contractBars == null || contractBars.isEmpty()) {
            return List.of();
        }

        // Sort contracts chronologically by first bar timestamp
        List<Map.Entry<String, List<Bar>>> sortedContracts = new ArrayList<>(contractBars.entrySet());
        sortedContracts.removeIf(e -> e.getValue().isEmpty());
        sortedContracts.sort(Comparator.comparing(e -> e.getValue().getFirst().timestamp()));

        if (sortedContracts.isEmpty()) {
            return List.of();
        }

        if (sortedContracts.size() == 1 || mode == AdjustmentMode.NONE) {
            List<Bar> result = new ArrayList<>();
            for (var entry : sortedContracts) {
                for (Bar b : entry.getValue()) {
                    result.add(new Bar(rootSymbol, b.timestamp(), b.open(), b.high(), b.low(), b.close(), b.volume()));
                }
            }
            result.sort(Comparator.comparing(Bar::timestamp));
            return List.copyOf(result);
        }

        // Multi-contract stitching with backward adjustment
        List<Bar> continuous = new ArrayList<>();
        double cumulativeOffset = 0.0;
        double cumulativeRatio = 1.0;

        // Traverse backwards from newest contract to oldest contract
        for (int i = sortedContracts.size() - 1; i >= 0; i--) {
            var currentEntry = sortedContracts.get(i);
            List<Bar> currentBars = currentEntry.getValue();

            if (i < sortedContracts.size() - 1) {
                var nextEntry = sortedContracts.get(i + 1);
                List<Bar> nextBars = nextEntry.getValue();

                // Compute gap between current contract close and next contract open/close on rollover day
                if (!currentBars.isEmpty() && !nextBars.isEmpty()) {
                    double currentClose = currentBars.getLast().close();
                    double nextClose = nextBars.getFirst().open();
                    double diff = nextClose - currentClose;
                    cumulativeOffset += diff;
                    if (currentClose > 0) {
                        cumulativeRatio *= (nextClose / currentClose);
                    }
                }
            }

            for (Bar b : currentBars) {
                double adjOpen, adjHigh, adjLow, adjClose;
                if (mode == AdjustmentMode.PANAMA_CANAL) {
                    adjOpen = b.open() + cumulativeOffset;
                    adjHigh = b.high() + cumulativeOffset;
                    adjLow = b.low() + cumulativeOffset;
                    adjClose = b.close() + cumulativeOffset;
                } else {
                    adjOpen = b.open() * cumulativeRatio;
                    adjHigh = b.high() * cumulativeRatio;
                    adjLow = b.low() * cumulativeRatio;
                    adjClose = b.close() * cumulativeRatio;
                }
                continuous.add(new Bar(rootSymbol, b.timestamp(), adjOpen, adjHigh, adjLow, adjClose, b.volume()));
            }
        }

        continuous.sort(Comparator.comparing(Bar::timestamp));
        return List.copyOf(continuous);
    }
}
