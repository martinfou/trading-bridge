package com.martinfou.trading.runtime.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.LiveStrategyRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final Strategy strategy;
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
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName is required").trim();
        this.symbol = Objects.requireNonNull(symbol, "symbol is required").trim().toUpperCase();
        this.strategy = Objects.requireNonNull(strategy, "strategy is required");
        this.strategy.reconcileInstrument(this.symbol);
        this.cursorFile = stateDir != null
            ? stateDir.resolve(this.strategyName + "-" + this.symbol + "-cursor.json")
            : null;
        this.dryRun = dryRun;
        loadCursor();
    }

    /**
     * Instantiates a {@link Strategy} by short name from {@code LiveStrategyRunner.getStrategyMap()}.
     * Fails closed with {@link IllegalArgumentException} on an unknown strategy name.
     */
    @SuppressWarnings("unchecked")
    public static Strategy instantiateStrategy(String strategyName, String symbol) {
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("STRATEGY is required and must not be blank");
        }
        String key = strategyName.trim();
        try {
            Method m = LiveStrategyRunner.class.getDeclaredMethod("getStrategyMap");
            m.setAccessible(true);
            Map<String, Class<? extends Strategy>> map =
                (Map<String, Class<? extends Strategy>>) m.invoke(null);
            Class<? extends Strategy> clazz = map.get(key);
            if (clazz == null) {
                throw new IllegalArgumentException(
                    "Unknown strategy '" + key + "'. Valid strategies: " + map.keySet());
            }
            Strategy instance = clazz.getDeclaredConstructor().newInstance();
            instance.reconcileInstrument(symbol);
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
        if (cursorFile == null || !Files.exists(cursorFile)) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(cursorFile));
            if (node.hasNonNull("lastProcessedBarTimestamp")) {
                this.lastProcessedBarTimestamp = Instant.parse(node.get("lastProcessedBarTimestamp").asText());
                log.info("Loaded bar cursor for {}/{}: lastProcessedBarTimestamp={}",
                    strategyName, symbol, lastProcessedBarTimestamp);
            }
        } catch (Exception e) {
            log.warn("Failed to read bar cursor from {}: {}", cursorFile, e.getMessage());
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
