package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarStoreTest {

    @Test
    void writeAndRead_preservesEpochMillis(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2012-01-01T00:00:00Z");
        List<Bar> bars = List.of(
            new Bar("EUR_USD", t0, 1.30, 1.31, 1.29, 1.305, 1000),
            new Bar("EUR_USD", t0.plusSeconds(3600), 1.305, 1.32, 1.30, 1.31, 1200)
        );

        var store = new BarStore("EUR_USD", "H1_2012", dir);
        store.write(bars);
        store.open();

        assertEquals(2, store.count());
        assertEquals(t0.toEpochMilli(), store.get(0).timestamp().toEpochMilli());
        assertEquals(t0.plusSeconds(3600).toEpochMilli(), store.get(1).timestamp().toEpochMilli());
    }

    @Test
    void read_supportsLegacyEpochSeconds(@TempDir Path dir) throws Exception {
        // Simulate legacy download script output (seconds, not millis)
        Path file = dir.resolve("EUR_USD_H1_2012.bars");
        long epochSec = Instant.parse("2012-06-01T12:00:00Z").getEpochSecond();
        var bytes = java.nio.ByteBuffer.allocate(BarStore.BAR_SIZE);
        bytes.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.putLong(epochSec);
        bytes.putDouble(1.1);
        bytes.putDouble(1.2);
        bytes.putDouble(1.0);
        bytes.putDouble(1.15);
        bytes.putInt(500);
        Files.write(file, bytes.array());

        var store = new BarStore("EUR_USD", "H1_2012", dir);
        store.open();

        assertEquals(1, store.count());
        assertEquals(epochSec, store.get(0).timestamp().getEpochSecond());
    }
}
