package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Stockage binaire de donnees historiques pour backtest ultra-rapide.
 * 
 * Format: Chaque barre = 40 bytes (fixe → acces O(1) par index)
 * ┌──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐
 * │ timestamp│   open   │   high   │   low    │  close   │  volume  │
 * │ (long,8) │ (double) │ (double) │ (double) │ (double) │ (int,4)  │
 * └──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘
 * 
 * Fichier: data/historical/bars/<SYMBOL>_<TF>.bars
 * Index:   data/historical/bars/index.json  (metadonnees)
 *
 * Timestamps are stored as epoch millis ({@link Bar#timestamp()}).
 * Legacy files written in epoch seconds are still readable.
 * 
 * Performance:
 * - Chargement de 1M barres avec MappedByteBuffer: < 50ms
 * - Acces par index: < 1μs
 * - Memoire: ~40 MB pour 1M barres (jamais en memoire, toujours mapped)
 * 
 * @see BarStore
 */
public class BarStore {

    public static final int BAR_SIZE = 44; // bytes per bar
    
    private final Path filePath;
    private final String symbol;
    private final String timeframe;
    private MappedByteBuffer buffer;
    private int barCount;

    /**
     * Byte order used by the download scripts (Python struct.pack '&lt;qddddi').
     * On x86-64 Linux, the default JVM byte order is LITTLE_ENDIAN anyway,
     * but we assert it explicitly so the contract with the Python pipeline
     * is self-documenting and portable.
     */
    private static final ByteOrder DATA_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    public BarStore(String symbol, String timeframe, Path dataDir) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.filePath = dataDir.resolve(symbol + "_" + timeframe + ".bars");
    }

    // ──────── Ecriture ────────

    /** Convertit des CSV bars en format binaire et les sauvegarde */
    public void write(List<Bar> bars) throws IOException {
        Files.createDirectories(filePath.getParent());
        try (var file = new RandomAccessFile(filePath.toFile(), "rw")) {
            var channel = file.getChannel();
            buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, 
                (long) bars.size() * BAR_SIZE);
            buffer.order(DATA_BYTE_ORDER);
            
            for (var bar : bars) {
                buffer.putLong(bar.timestamp().toEpochMilli());
                buffer.putDouble(bar.open());
                buffer.putDouble(bar.high());
                buffer.putDouble(bar.low());
                buffer.putDouble(bar.close());
                buffer.putInt((int) bar.volume());
            }
            this.barCount = bars.size();
            channel.close();
        }
    }

    /** Convertit un CSV en binaire */
    public void writeFromCSV(Path csvPath) throws IOException {
        var bars = new ArrayList<Bar>();
        try (var reader = Files.newBufferedReader(csvPath)) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                var p = line.split(",");
                if (p.length >= 5) {
                    Instant ts;
                    try {
                        ts = Instant.parse(p[0]);
                    } catch (Exception e) {
                        ts = Instant.ofEpochSecond(Long.parseLong(p[0]));
                    }
                    bars.add(new Bar(symbol, ts,
                        Double.parseDouble(p[1]), // open
                        Double.parseDouble(p[2]), // high
                        Double.parseDouble(p[3]), // low
                        Double.parseDouble(p[4]), // close
                        p.length >= 6 ? Long.parseLong(p[5]) : 0));
                }
            }
        }
        write(bars);
    }

    // ──────── Lecture (memory-mapped, zero-copy) ────────

    public void open() throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("BarStore file not found: " + filePath);
        }
        try (var channel = (FileChannel) Files.newByteChannel(filePath, StandardOpenOption.READ)) {
            long size = channel.size();
            this.barCount = (int) (size / BAR_SIZE);
            this.buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            this.buffer.order(DATA_BYTE_ORDER);
        }
    }

    /** Nombre de barres dans le fichier */
    public int count() { return barCount; }

    /** Reads epoch from buffer — supports both seconds (legacy convert script) and millis. */
    private static Instant readTimestamp(long raw) {
        return raw > 1_000_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
    }

    /** Lit UNE barre a l'index i (acces O(1)) */
    public Bar get(int i) {
        if (i < 0 || i >= barCount) throw new IndexOutOfBoundsException("Bar " + i + "/" + barCount);
        int pos = i * BAR_SIZE;
        var ts = readTimestamp(buffer.getLong(pos));
        return new Bar(symbol, ts,
            buffer.getDouble(pos + 8),
            buffer.getDouble(pos + 16),
            buffer.getDouble(pos + 24),
            buffer.getDouble(pos + 32),
            buffer.getInt(pos + 40));
    }

    /** Toutes les barres (utile pour StrategyTemplate.onBar()) */
    public List<Bar> toList() {
        var list = new ArrayList<Bar>(barCount);
        for (int i = 0; i < barCount; i++) {
            list.add(get(i));
        }
        return list;
    }

    /** Sous-liste: barres dans un range de dates */
    public List<Bar> range(Instant from, Instant to) {
        // Binary search for start/end indices
        int start = findFirstAfter(from);
        int end = findLastBefore(to);
        if (start < 0) start = 0;
        if (end >= barCount) end = barCount - 1;
        
        var result = new ArrayList<Bar>(end - start + 1);
        for (int i = start; i <= end; i++) {
            result.add(get(i));
        }
        return result;
    }

    private long barEpochMs(int index) {
        long raw = buffer.getLong(index * BAR_SIZE);
        return raw > 1_000_000_000_000L ? raw : raw * 1000L;
    }

    private int findFirstAfter(Instant t) {
        long target = t.toEpochMilli();
        int lo = 0, hi = barCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (barEpochMs(mid) < target) lo = mid + 1;
            else hi = mid - 1;
        }
        return lo;
    }

    private int findLastBefore(Instant t) {
        long target = t.toEpochMilli();
        int lo = 0, hi = barCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (barEpochMs(mid) <= target) lo = mid + 1;
            else hi = mid - 1;
        }
        return hi;
    }

    // ──────── Utilitaires ────────

    /** Convertit les CSVs du dossier data/historical en format binaire */
    public static void convertAllFromCSV(Path csvDir, Path outputDir) throws IOException {
        try (var files = Files.list(csvDir)) {
            for (var csv : files.toList()) {
                if (!csv.toString().endsWith(".csv")) continue;
                var name = csv.getFileName().toString().replace(".csv", "");
                System.out.printf("Converting %s... ", name);
                var store = new BarStore(name, "H1", outputDir);
                store.writeFromCSV(csv);
                System.out.printf("%d bars ✅\n", store.count());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--convert")) {
            convertAllFromCSV(Path.of(args[1]), Path.of(args.length > 2 ? args[2] : "data/historical/bars"));
        } else {
            System.out.println("Usage: BarStore --convert <csv_dir> [output_dir]");
            System.out.println("Convertit les CSV en format binaire pour backtest rapide");
        }
    }
}
