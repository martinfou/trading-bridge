package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;

import java.io.IOException;
import java.util.List;

/** Loads bar data for validation modules (Epic 19). */
final class ValidationBarLoader {

    private ValidationBarLoader() {}

    static List<Bar> load(RunConfigSnapshot snapshot) throws IOException {
        Integer year = snapshot.barsSourceYear() != null
            ? Integer.parseInt(snapshot.barsSourceYear())
            : null;
        return BarSourceResolver.load(
            new BarSourceResolver.BarsSource(
                snapshot.barsSourceType(),
                snapshot.barsSourceCount(),
                year),
            snapshot.symbol());
    }
}
