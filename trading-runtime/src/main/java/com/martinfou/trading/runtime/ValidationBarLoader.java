package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;

import java.io.IOException;
import java.util.List;

/** Loads bar data for validation modules (Epic 19). */
final class ValidationBarLoader {

    private ValidationBarLoader() {}

    static List<Bar> load(RunConfigSnapshot snapshot) throws IOException {
        return BarSourceResolver.load(
            new BarSourceResolver.BarsSource(
                snapshot.barsSourceType(),
                snapshot.barsSourceCount(),
                snapshot.barsSourceYear(),
                snapshot.barsSourcePath()),
            snapshot.symbol());
    }
}
