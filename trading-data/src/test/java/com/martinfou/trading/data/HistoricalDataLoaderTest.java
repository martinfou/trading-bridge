package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HistoricalDataLoaderTest {

    @Test
    void loadFile_dukascopyCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("eurusd-h1-bid-2012-01-01-2012-12-31.csv");
        long millis = Instant.parse("2012-01-01T00:00:00Z").toEpochMilli();
        Files.writeString(csv, """
            timestamp,open,high,low,close
            %d,1.30,1.31,1.29,1.305
            """.formatted(millis));

        var loaded = HistoricalDataLoader.loadFile(csv, "EUR_USD");

        assertEquals("EUR_USD", loaded.symbol());
        assertEquals(1, loaded.bars().size());
        Bar bar = loaded.bars().getFirst();
        assertEquals(millis, bar.timestamp().toEpochMilli());
        assertEquals(1.305, bar.close(), 1e-9);
    }

    @Test
    void loadFile_barStoreRoundtrip(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2012-01-01T00:00:00Z");
        List<Bar> original = List.of(new Bar("EUR_USD", t0, 1.1, 1.2, 1.0, 1.15, 100));
        var writer = new BarStore("EUR_USD", "H1_2012", dir);
        writer.write(original);
        Path barsFile = dir.resolve("EUR_USD_H1_2012.bars");

        var loaded = HistoricalDataLoader.loadFile(barsFile, "EUR_USD");

        assertFalse(loaded.bars().isEmpty());
        assertEquals(t0, loaded.bars().getFirst().timestamp());
    }

    @Test
    void inferSymbol_fromBarStoreFilename() {
        Path path = Path.of("data/historical/bars/EUR_USD_H1_2012.bars");
        assertEquals("EUR_USD", HistoricalDataLoader.inferSymbol(path, "FALLBACK"));
    }
}
