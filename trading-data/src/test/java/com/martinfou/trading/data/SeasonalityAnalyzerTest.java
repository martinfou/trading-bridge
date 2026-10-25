package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de l'audit du 2 octobre 2026 (48e) : {@code SeasonalityAnalyzer} rendait une sortie
 * DÉGÉNÉRÉE (best = worst = JANUARY, hit rates à 0) parce que {@code loadBars()} décodait des
 * horodatages en millisecondes comme des nanosecondes — toutes les barres tombaient en janvier
 * 1970. La mesure mensuelle ajoutait par ailleurs le rendement d'UNE barre à la bascule de mois.
 */
class SeasonalityAnalyzerTest {

    private static Bar bar(String sym, Instant t, double o, double h, double l, double c) {
        return new Bar(sym, t, o, h, l, c, 0);
    }

    private static Bar real(String sym, String isoDay, double close) {
        Instant t = ZonedDateTime.parse(isoDay + "T12:00:00Z").toInstant();
        return bar(sym, t, close, close + 0.0005, close - 0.0005, close);
    }

    @Test
    @DisplayName("decodeEpochSeconds : millisecondes (convention BarStore), secondes et nanosecondes")
    void decodeEpochSeconds_units() {
        // 2006-01-01T00:00:00Z
        long ms = 1_136_073_600_000L;
        assertEquals(1_136_073_600L, SeasonalityAnalyzer.decodeEpochSeconds(ms));
        assertEquals(Instant.parse("2006-01-01T00:00:00Z"),
            Instant.ofEpochSecond(SeasonalityAnalyzer.decodeEpochSeconds(ms)));
        // le bug du 48e : la division par 1e6 (convention nanosecondes) datait la barre de 1970
        assertEquals(1970, Instant.ofEpochSecond(ms / 1_000_000L).atZone(ZoneOffset.UTC).getYear());
        // secondes : passthrough
        assertEquals(1_136_073_600L, SeasonalityAnalyzer.decodeEpochSeconds(1_136_073_600L));
        // nanosecondes
        assertEquals(1_136_073_600L, SeasonalityAnalyzer.decodeEpochSeconds(1_136_073_600_000_000_000L));
    }

    @Test
    @DisplayName("realBars : les barres de carry (high == low) sont exclues")
    void realBars_excludesFlatCarry() {
        List<Bar> bars = new ArrayList<>();
        bars.add(real("EUR_USD", "2020-01-31", 1.10));
        Instant sat = ZonedDateTime.parse("2020-02-01T12:00:00Z").toInstant();
        bars.add(bar("EUR_USD", sat, 1.10, 1.10, 1.10, 1.10));   // barre plate de week-end
        assertEquals(1, SeasonalityAnalyzer.realBars(bars).size());
    }

    @Test
    @DisplayName("monthlyReturnsFromBars : vrai rendement de fin de mois à fin de mois")
    void monthlyReturns_trueMonthOverMonth() {
        String s = "EUR_USD";
        List<Bar> bars = List.of(
            real(s, "2020-01-31", 100.0),
            real(s, "2020-02-28", 110.0),   // février : +10 %
            real(s, "2020-03-31", 104.5)    // mars : −5 %
        );
        Map<Integer, List<Double>> ret = SeasonalityAnalyzer.monthlyReturnsFromBars(bars);
        assertEquals(10.0, ret.get(2).get(0), 1e-9);
        assertEquals(-5.0, ret.get(3).get(0), 1e-9);
        assertFalse(ret.containsKey(1), "janvier n'a pas de mois précédent dans l'échantillon");
    }

    @Test
    @DisplayName("monthEndCloses : la dernière barre réelle du mois gagne, en UTC")
    void monthEndCloses_utcLastBarWins() {
        String s = "EUR_USD";
        List<Bar> bars = new ArrayList<>();
        bars.add(real(s, "2020-01-15", 100.0));
        bars.add(real(s, "2020-01-31", 105.0));
        // 2020-02-01T00:30Z reste en janvier en heure de New York : le bucket doit être UTC
        Instant t = Instant.parse("2020-02-01T00:30:00Z");
        bars.add(bar(s, t, 106, 106.0005, 105.9995, 106));
        var me = SeasonalityAnalyzer.monthEndCloses(SeasonalityAnalyzer.realBars(bars));
        assertEquals(106.0, me.get(java.time.YearMonth.of(2020, 2)), 1e-9);
        assertEquals(105.0, me.get(java.time.YearMonth.of(2020, 1)), 1e-9);
    }

    @Test
    @DisplayName("analyze() sur données réelles : sortie non dégénérée (régression du bug 1970)")
    void analyze_realData_isMeaningful() throws Exception {
        Path dataDir = Path.of(System.getProperty("user.dir")).getParent()
            .resolve("data/historical/bars/EUR_USD_H1_H1.bars");
        Assumptions.assumeTrue(Files.exists(dataDir), "données .bars absentes de cet environnement");

        var profile = new SeasonalityAnalyzer().analyze("EUR/USD");

        // Avant le fix : best = worst = JANUARY, taux de réussite 0 et monthlyVol 0.
        assertNotEquals(profile.bestMonth(), profile.worstMonth(), "best == worst = buckets vides");
        assertTrue(profile.totalYears() >= 15, "années agrégées = " + profile.totalYears());
        double amp = profile.monthlyReturns().values().stream().mapToDouble(Math::abs).average().orElse(0);
        assertTrue(amp > 0.05 && amp < 5.0, "amplitude mensuelle non plausible : " + amp + " %");
        assertTrue(profile.monthlyWinRate().values().stream().anyMatch(w -> w > 20),
            "tous les taux de réussite mensuels sont à 0 — buckets vides");
        assertTrue(Math.abs(profile.avgYearlyReturn()) < 15.0,
            "rendement annuel non plausible : " + profile.avgYearlyReturn() + " %");
    }
}
