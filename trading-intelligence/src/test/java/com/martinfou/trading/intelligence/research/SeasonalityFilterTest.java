package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de non-régression du SeasonalityFilter — audit du 30 septembre 2026.
 *
 * Trois bugs silencieux corrigés ce jour-là :
 *  1. les clés de PATTERNS (« USDCAD ») ne matchaient pas les symboles des runners
 *     (« USD_CAD ») → 2/8 patterns morts ;
 *  2. le comptage des jours se faisait en America/New_York (04:00Z) au lieu d'UTC ;
 *  3. aucun test ne couvrait ce fichier — ces trois tests ferment la classe de bug.
 */
class SeasonalityFilterTest {

    private static Instant utc(int y, int m, int d, int h) {
        return LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC);
    }

    /** 1. Le symbole du runner (underscore) doit déclencher le pattern écrit sans underscore. */
    @Test
    void underscoreSymbolMatchesPatternKey() {
        Instant midOct = utc(2020, 10, 20, 12);
        assertEquals(Order.Side.BUY, SeasonalityFilter.getBias("USD_CAD", midOct),
            "USD_CAD (convention runner) doit matcher la clé USDCAD");
        assertEquals(Order.Side.BUY, SeasonalityFilter.getBias("USDCAD", midOct),
            "la clé historique sans underscore doit continuer de fonctionner");
        assertNotNull(SeasonalityFilter.getPattern("USD_CAD", midOct));
    }

    /** 2. Les fenêtres s'ouvrent à 00:00 UTC, pas à 04:00Z (fuseau New York). */
    @Test
    void windowOpensAtUtcMidnight() {
        Instant justAfterMidnight = utc(2020, 10, 12, 1);
        assertEquals(Order.Side.BUY, SeasonalityFilter.getBias("USD_CAD", justAfterMidnight),
            "le 12 oct 01:00Z est DANS la fenêtre 10/12→11/26");
        assertNull(SeasonalityFilter.getBias("USD_CAD", utc(2020, 10, 11, 23)),
            "le 11 oct 23:00Z est HORS fenêtre");
    }

    /** 3. Bornes de fenêtre et paires non couvertes. */
    @Test
    void windowBoundsAndNeutral() {
        assertEquals(Order.Side.BUY, SeasonalityFilter.getBias("USD_CAD", utc(2020, 11, 26, 12)),
            "dernier jour de la fenêtre inclus");
        assertNull(SeasonalityFilter.getBias("USD_CAD", utc(2020, 11, 27, 12)),
            "lendemain exclu");
        assertNull(SeasonalityFilter.getBias("XAU_USD", utc(2020, 10, 20, 12)),
            "aucun pattern or → neutre");
        assertNull(SeasonalityFilter.getBias(null, utc(2020, 10, 20, 12)),
            "symbole null → neutre, pas de NPE");
    }
}
