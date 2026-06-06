package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detects conflicting signals across COT and OANDA retail positioning. */
public final class ContradictionDetector {

    private static final double EXTREME_COT_LONG = 65.0;
    private static final double EXTREME_RETAIL_LONG = 70.0;

    private static final Map<String, String> COT_TO_OANDA = Map.of(
        "EUR", "EUR_USD",
        "GBP", "GBP_USD",
        "JPY", "USD_JPY",
        "AUD", "AUD_USD",
        "CAD", "USD_CAD"
    );

    public List<WeeklyIntelBrief.ContradictionEntry> detect(
        List<WeeklyIntelBrief.CotSnapshotEntry> cot,
        List<WeeklyIntelBrief.OandaRetailEntry> oanda
    ) {
        List<WeeklyIntelBrief.ContradictionEntry> out = new ArrayList<>();
        for (WeeklyIntelBrief.CotSnapshotEntry cotEntry : cot) {
            String instrumentKey = cotEntry.instrument().toUpperCase(Locale.ROOT);
            String oandaSymbol = COT_TO_OANDA.get(instrumentKey);
            if (oandaSymbol == null) {
                continue;
            }
            WeeklyIntelBrief.OandaRetailEntry retail = oanda.stream()
                .filter(o -> oandaSymbol.equals(o.instrument()))
                .findFirst()
                .orElse(null);
            if (retail == null) {
                continue;
            }
            if (cotEntry.longPct() >= EXTREME_COT_LONG && retail.longPct() >= EXTREME_RETAIL_LONG) {
                out.add(new WeeklyIntelBrief.ContradictionEntry(
                    "COT_RETAIL_CROWDED_LONG",
                    "Speculators and retail both heavily long on " + oandaSymbol
                        + " — contrarian fade risk",
                    List.of(oandaSymbol)
                ));
            }
            if (cotEntry.shortPct() >= EXTREME_COT_LONG && retail.shortPct() >= EXTREME_RETAIL_LONG) {
                out.add(new WeeklyIntelBrief.ContradictionEntry(
                    "COT_RETAIL_CROWDED_SHORT",
                    "Speculators and retail both heavily short on " + oandaSymbol
                        + " — contrarian fade risk",
                    List.of(oandaSymbol)
                ));
            }
        }
        return out;
    }
}
