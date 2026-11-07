package com.martinfou.trading.runtime.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.runtime.generator.StrategyGeneratorService;
import com.martinfou.trading.strategies.live.LiveStrategyRunner;
import com.martinfou.trading.strategies.StrategyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Stateful per-symbol strategy evaluator for the IBKR futures lane (Story 46.10).
 *
 * <p>Resolves a {@link Strategy} instance from {@link LiveStrategyRunner}'s canonical
 * {@code getStrategyMap()} registry, feeds historical warmup bars without dispatching stale
 * orders, and evaluates newly closed bars at most once per {@code (barTimestamp, strategy, symbol)}
 * with cursor persistence under {@code TB_STATE_DIR}.</p>
 */
public final class SqStrategyEvaluator implements Function<List<Bar>, List<Order>> {

    private static final Logger log = LoggerFactory.getLogger(SqStrategyEvaluator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String strategyName;
    private final String symbol;
    private final String timeframe;
    private final Strategy strategy;
    private final Path stateDir;
    private final Path cursorFile;
    private final boolean dryRun;

    private volatile Instant lastProcessedBarTimestamp;
    private volatile boolean warmedUp;

    public SqStrategyEvaluator(
        String strategyName,
        String symbol,
        Strategy strategy,
        Path stateDir,
        boolean dryRun
    ) {
        this(strategyName, symbol, resolveTimeframeForStrategy(strategyName, "1 hour"), strategy, stateDir, dryRun);
    }

    public SqStrategyEvaluator(
        String strategyName,
        String symbol,
        String timeframe,
        Strategy strategy,
        Path stateDir,
        boolean dryRun
    ) {
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName is required").trim();
        this.symbol = Objects.requireNonNull(symbol, "symbol is required").trim().toUpperCase();
        this.timeframe = (timeframe != null && !timeframe.isBlank()) ? timeframe.trim() : "1 hour";
        this.strategy = Objects.requireNonNull(strategy, "strategy is required");
        this.strategy.reconcileInstrument(this.symbol);
        this.stateDir = stateDir;
        this.cursorFile = stateDir != null
            ? stateDir.resolve(this.strategyName + "-" + this.symbol + "-cursor.json")
            : null;
        this.dryRun = dryRun;
        loadCursor();
    }

    private static final Map<String, String> LEGACY_ALIASES = Map.of(
        "sq-mgc-m30-breakout-0-24542", "MgcM30Breakout49205",
        "sq-mgc-m30-breakout-1-29-102", "MgcM30Breakout89854",
        "sq-mgc-m30-breakout-0-32921", "MgcM30Breakout92728"
    );

    private static final Map<String, String> REVERSE_LEGACY_ALIASES = Map.of(
        "MgcM30Breakout49205", "sq-mgc-m30-breakout-0-24542",
        "MgcM30Breakout89854", "sq-mgc-m30-breakout-1-29-102",
        "MgcM30Breakout92728", "sq-mgc-m30-breakout-0-32921"
    );

    /**
     * Resolves the natural IBKR bar timeframe (e.g. {@code "30 mins"}, {@code "1 hour"}, {@code "15 mins"})
     * for a strategy name when running a multi-timeframe container, falling back to {@code defaultTimeframe}.
     */
    public static String resolveTimeframeForStrategy(String strategyName, String defaultTimeframe) {
        String fallback = (defaultTimeframe != null && !defaultTimeframe.isBlank())
            ? defaultTimeframe.trim()
            : "1 hour";
        if (strategyName == null || strategyName.isBlank()) {
            return fallback;
        }
        String key = strategyName.trim();
        String resolvedKey = LEGACY_ALIASES.getOrDefault(key, key);
        String upper = resolvedKey.toUpperCase();
        if (upper.contains("M15") || upper.contains("-M15-")) {
            return "15 mins";
        }
        if (upper.contains("M30") || upper.contains("-M30-")) {
            return "30 mins";
        }
        if (upper.contains("H1") || upper.contains("-H1-")) {
            return "1 hour";
        }
        if (upper.contains("H4") || upper.contains("-H4-")) {
            return "4 hours";
        }
        if (upper.contains("D1") || upper.contains("-D1-")) {
            return "1 day";
        }
        if (upper.contains("M5") || upper.contains("-M5-")) {
            return "5 mins";
        }
        if (upper.contains("M1") || upper.contains("-M1-")) {
            return "1 min";
        }
        StrategyGeneratorService.ensureCatalogLoaded();
        if (StrategyCatalog.contains(resolvedKey)) {
            StrategyCatalog.Entry entry = StrategyCatalog.entries().stream()
                .filter(e -> e.id().equals(resolvedKey))
                .findFirst()
                .orElse(null);
            if (entry != null && entry.timeframeSuitability() != null && !entry.timeframeSuitability().isEmpty()) {
                String primaryTf = entry.timeframeSuitability().get(0).trim().toUpperCase();
                return switch (primaryTf) {
                    case "M1" -> "1 min";
                    case "M5" -> "5 mins";
                    case "M15" -> "15 mins";
                    case "M30" -> "30 mins";
                    case "H1" -> "1 hour";
                    case "H4" -> "4 hours";
                    case "D1" -> "1 day";
                    default -> fallback;
                };
            }
        }
        return fallback;
    }

    /**
     * Resolves the natural CME futures symbol ({@code MGC}, {@code MNQ}, {@code MES}, {@code EMD}, {@code M2K})
     * for a strategy name when running a multi-symbol container, falling back to {@code defaultSymbol}.
     */
    public static String resolveSymbolForStrategy(String strategyName, String defaultSymbol) {
        String fallback = (defaultSymbol != null && !defaultSymbol.isBlank())
            ? defaultSymbol.trim().toUpperCase()
            : "MGC";
        if (strategyName == null || strategyName.isBlank()) {
            return fallback;
        }
        String key = strategyName.trim();
        String resolvedKey = LEGACY_ALIASES.getOrDefault(key, key);
        String upper = resolvedKey.toUpperCase();
        if (upper.startsWith("MGC") || upper.contains("-MGC-") || upper.contains("GOLD")) {
            return "MGC";
        }
        if (upper.startsWith("MNQ") || upper.contains("-MNQ-")) {
            return "MNQ";
        }
        if (upper.startsWith("MES") || upper.contains("-MES-")) {
            return "MES";
        }
        if (upper.startsWith("EMD") || upper.contains("-EMD-")) {
            return "EMD";
        }
        if (upper.startsWith("M2K") || upper.contains("-M2K-")) {
            return "M2K";
        }
        StrategyGeneratorService.ensureCatalogLoaded();
        if (StrategyCatalog.contains(resolvedKey)) {
            String catSym = StrategyCatalog.defaultSymbol(resolvedKey);
            if (catSym != null && !catSym.isBlank() && !catSym.contains("_")) {
                return catSym.trim().toUpperCase();
            }
        }
        return fallback;
    }

    public static Set<String> validStrategyIds() {
        StrategyGeneratorService.ensureCatalogLoaded();
        Set<String> ids = new LinkedHashSet<>(StrategyCatalog.ids());
        ids.addAll(LEGACY_ALIASES.keySet());
        try {
            Method m = LiveStrategyRunner.class.getDeclaredMethod("getStrategyMap");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Class<? extends Strategy>> map =
                (Map<String, Class<? extends Strategy>>) m.invoke(null);
            ids.addAll(map.keySet());
        } catch (Exception ignored) {
        }
        return ids;
    }

    /**
     * Instantiates a {@link Strategy} by id from {@link StrategyCatalog} (including SQLite-backed
     * generated strategies) or {@code LiveStrategyRunner.getStrategyMap()}.
     * Fails closed with {@link IllegalArgumentException} on an unknown strategy name.
     */
    @SuppressWarnings("unchecked")
    public static Strategy instantiateStrategy(String strategyName, String symbol) {
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("STRATEGY is required and must not be blank");
        }
        String key = strategyName.trim();
        String targetSymbol = (symbol != null && !symbol.isBlank()) ? symbol.trim().toUpperCase() : "MGC";
        StrategyGeneratorService.ensureCatalogLoaded();

        String resolvedKey = LEGACY_ALIASES.getOrDefault(key, key);
        if (StrategyCatalog.contains(resolvedKey)) {
            Strategy instance = StrategyCatalog.create(resolvedKey, targetSymbol);
            instance.reconcileInstrument(targetSymbol);
            return instance;
        }
        if ((LEGACY_ALIASES.containsKey(key) || LEGACY_ALIASES.containsValue(key))
            && StrategyCatalog.contains("LtRangeBreakout")) {
            Strategy instance = StrategyCatalog.create("LtRangeBreakout", targetSymbol);
            instance.reconcileInstrument(targetSymbol);
            return instance;
        }

        try {
            Method m = LiveStrategyRunner.class.getDeclaredMethod("getStrategyMap");
            m.setAccessible(true);
            Map<String, Class<? extends Strategy>> map =
                (Map<String, Class<? extends Strategy>>) m.invoke(null);
            Class<? extends Strategy> clazz = map.get(key);
            if (clazz == null) {
                throw new IllegalArgumentException(
                    "Unknown strategy '" + key + "'. Valid strategies: " + validStrategyIds());
            }
            Strategy instance = clazz.getDeclaredConstructor().newInstance();
            instance.reconcileInstrument(targetSymbol);
            return instance;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate strategy '" + key + "': " + e.getMessage(), e);
        }
    }

    public String strategyName() {
        return strategyName;
    }

    public String symbol() {
        return symbol;
    }

    public String timeframe() {
        return timeframe;
    }

    public Strategy strategy() {
        return strategy;
    }

    public Instant lastProcessedBarTimestamp() {
        return lastProcessedBarTimestamp;
    }

    @Override
    public synchronized List<Order> apply(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            log.warn("No bars provided to SqStrategyEvaluator for {}/{}", strategyName, symbol);
            return List.of();
        }

        Bar latestBar = bars.get(bars.size() - 1);
        Instant latestTs = latestBar.timestamp();

        if (!warmedUp) {
            // Feed all historical bars prior to the latest bar as warm-up and drain any historical signals
            for (int i = 0; i < bars.size() - 1; i++) {
                strategy.onBar(bars.get(i));
            }
            strategy.getPendingOrders(); // discard warm-up signals
            warmedUp = true;
        }

        if (lastProcessedBarTimestamp != null && !latestTs.isAfter(lastProcessedBarTimestamp)) {
            log.info("Bar {} for {}/{} already processed (cursor={}) — skipping duplicate evaluation.",
                latestTs, strategyName, symbol, lastProcessedBarTimestamp);
            return List.of();
        }

        strategy.onBar(latestBar);
        List<Order> pending = strategy.getPendingOrders();
        this.lastProcessedBarTimestamp = latestTs;
        persistCursor();

        if (pending == null || pending.isEmpty()) {
            log.info("Evaluated {} bars for {}/{} at bar {} -> no signal",
                bars.size(), strategyName, symbol, latestTs);
            return List.of();
        }

        String tag = strategyName + "_" + symbol;
        List<Order> tagged = new ArrayList<>(pending.size());
        for (Order o : pending) {
            Order withTag = o.withStrategyId(tag);
            if (dryRun) {
                log.info("DRY_RUN=true [{}]: evaluated {} bars at bar {} -> would dispatch {} {} {} @ {} (SL={}, TP={})",
                    tag, bars.size(), latestTs, withTag.side(), withTag.quantity(), withTag.symbol(),
                    withTag.price(), withTag.stopLoss(), withTag.takeProfit());
            } else {
                tagged.add(withTag);
            }
        }

        return dryRun ? List.of() : List.copyOf(tagged);
    }

    public synchronized void loadCursor() {
        if (cursorFile == null) {
            return;
        }
        Path fileToRead = cursorFile;
        if (!Files.exists(fileToRead) && stateDir != null) {
            String legacyAlias = REVERSE_LEGACY_ALIASES.get(strategyName);
            if (legacyAlias != null) {
                Path legacyFile = stateDir.resolve(legacyAlias + "-" + symbol + "-cursor.json");
                if (Files.exists(legacyFile)) {
                    fileToRead = legacyFile;
                }
            }
        }
        if (!Files.exists(fileToRead)) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(fileToRead));
            if (node.hasNonNull("lastProcessedBarTimestamp")) {
                this.lastProcessedBarTimestamp = Instant.parse(node.get("lastProcessedBarTimestamp").asText());
                log.info("Loaded bar cursor for {}/{} from {}: lastProcessedBarTimestamp={}",
                    strategyName, symbol, fileToRead.getFileName(), lastProcessedBarTimestamp);
            }
        } catch (Exception e) {
            log.warn("Failed to read bar cursor from {}: {}", fileToRead, e.getMessage());
        }
    }

    public synchronized void persistCursor() {
        if (cursorFile == null || lastProcessedBarTimestamp == null) {
            return;
        }
        try {
            Path parent = cursorFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.put("strategy", strategyName);
            root.put("symbol", symbol);
            root.put("lastProcessedBarTimestamp", lastProcessedBarTimestamp.toString());
            root.put("savedAt", Instant.now().toString());
            Path tmp = cursorFile.resolveSibling(cursorFile.getFileName().toString() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Files.move(tmp, cursorFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Failed to persist bar cursor to {}: {}", cursorFile, e.getMessage());
        }
    }
}
