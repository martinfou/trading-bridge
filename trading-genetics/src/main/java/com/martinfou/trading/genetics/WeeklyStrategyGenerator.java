package com.martinfou.trading.genetics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;


/**
 * Reads a JSON configuration of recommended weekly trades (from Saturday analysis)
 * and generates compilable Java strategy files under
 * {@code trading-strategies/.../strategies/weekly/}.
 *
 * <p>Also writes a deployment summary to {@code deploy/weekly-plans/YYYY-MM-DD-summary.json}.</p>
 *
 * <h3>Input JSON format</h3>
 * <pre>{@code
 * {
 *   "week": "2026-05-18",
 *   "generatedAt": "2026-05-16T12:00:00Z",
 *   "analyst": "TradeMaster",
 *   "trades": [
 *     {
 *       "name": "EURUSD_Breakout_Long",
 *       "pair": "EUR_USD",
 *       "direction": "BUY",
 *       "entryPrice": 1.0950,
 *       "stopLoss": 1.0900,
 *       "takeProfit": 1.1050,
 *       "quantity": 0.01,
 *       "orderType": "BUYSTOP",
 *       "maxBars": 120,
 *       "reason": "Bullish breakout above resistance",
 *       "confidence": 75
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class WeeklyStrategyGenerator {

    private static final String WEEKLY_PACKAGE = "com.martinfou.trading.strategies.weekly";
    private static final Path OUTPUT_DIR = Paths.get(
        "trading-strategies/src/main/java/com/martinfou/trading/strategies/weekly"
    );
    private static final Path DEPLOY_DIR = Paths.get("deploy", "weekly-plans");
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<TradeEntry> trades = new ArrayList<>();
    private final List<String> generatedFiles = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    /** A single trade entry parsed from the JSON config. */
    public record TradeEntry(
        String name,
        String pair,
        String direction,
        double entryPrice,
        double stopLoss,
        double takeProfit,
        double quantity,
        String orderType,
        int maxBars,
        String reason,
        int confidence
    ) {}

    /**
     * Reads a JSON config file and generates all strategy files.
     *
     * @param configPath path to the JSON configuration file
     * @return {@code true} if all trades were generated successfully
     * @throws IOException if reading or writing fails
     */
    public boolean generate(String configPath) throws IOException {
        return generate(Paths.get(configPath));
    }

    /**
     * Reads a JSON config file and generates all strategy files.
     *
     * @param configPath path to the JSON configuration file
     * @return {@code true} if all trades were generated successfully
     * @throws IOException if reading or writing fails
     */
    public boolean generate(Path configPath) throws IOException {
        Objects.requireNonNull(configPath);
        if (!Files.exists(configPath)) {
            throw new IOException("Config file not found: " + configPath.toAbsolutePath());
        }

        // Parse input
        JsonNode root = MAPPER.readTree(configPath.toFile());
        String week = root.has("week") ? root.get("week").asText() : LocalDate.now().toString();
        String generatedAt = root.has("generatedAt")
            ? root.get("generatedAt").asText()
            : ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
        String analyst = root.has("analyst") ? root.get("analyst").asText() : "TradeMaster";

        ArrayNode tradesNode = root.withArray("trades");
        if (tradesNode == null || tradesNode.isEmpty()) {
            throw new IOException("No trades found in config: " + configPath);
        }

        trades.clear();
        generatedFiles.clear();
        errors.clear();

        // Parse each trade
        for (JsonNode tradeNode : tradesNode) {
            try {
                TradeEntry entry = parseTrade(tradeNode);
                trades.add(entry);
            } catch (Exception e) {
                errors.add("Failed to parse trade: " + e.getMessage());
            }
        }

        // Generate Java files
        for (TradeEntry entry : trades) {
            try {
                Path filePath = generateStrategyFile(entry);
                generatedFiles.add(filePath.toString());
            } catch (Exception e) {
                errors.add("Failed to generate " + entry.name() + ": " + e.getMessage());
            }
        }

        // Write summary
        writeSummary(week, generatedAt, analyst);

        return errors.isEmpty();
    }

    /**
     * Parses a single trade entry from the JSON node.
     */
    private TradeEntry parseTrade(JsonNode node) {
        String name = node.get("name").asText();
        String pair = node.get("pair").asText();
        String direction = node.get("direction").asText().toUpperCase();
        String orderType = node.get("orderType").asText().toUpperCase();

        // Validate direction
        if (!direction.equals("BUY") && !direction.equals("SELL")) {
            throw new IllegalArgumentException("Invalid direction: " + direction);
        }
        // Validate order type
        if (!orderType.equals("BUYSTOP") && !orderType.equals("SELLSTOP")) {
            throw new IllegalArgumentException("Invalid orderType: " + orderType
                + " (must be BUYSTOP or SELLSTOP)");
        }

        return new TradeEntry(
            name,
            pair,
            direction,
            node.get("entryPrice").asDouble(),
            node.get("stopLoss").asDouble(),
            node.get("takeProfit").asDouble(),
            node.get("quantity").asDouble(),
            orderType,
            node.has("maxBars") ? node.get("maxBars").asInt(120) : 120,
            node.has("reason") ? node.get("reason").asText("") : "",
            node.has("confidence") ? node.get("confidence").asInt(50) : 50
        );
    }

    /**
     * Generates a Java source file for one trade entry.
     *
     * @return the path to the generated file
     */
    private Path generateStrategyFile(TradeEntry entry) throws IOException {
        // Sanitize class name: remove special chars, camel-case
        String className = sanitizeClassName(entry.name());
        if (!className.endsWith("Strategy")) {
            className = className + "Strategy";
        }

        Files.createDirectories(OUTPUT_DIR);

        Path filePath = OUTPUT_DIR.resolve(className + ".java");

        String source = buildSource(className, entry);
        Files.writeString(filePath, source);

        return filePath;
    }

    /**
     * Builds the complete Java source code for a strategy class.
     */
    private String buildSource(String className, TradeEntry entry) {
        String directionField = entry.direction().equals("BUY") ? "BUY" : "SELL";

        return String.format("""
            package %s;

            import com.martinfou.trading.core.Bar;
            import com.martinfou.trading.core.Order;
            import com.martinfou.trading.core.Strategy;
            import com.martinfou.trading.strategies.weekly.WeeklyStrategy;
            import java.util.ArrayList;
            import java.util.List;

            /**
             * %s — generated from weekly analysis.
             *
             * <p>Pair: %s<br>
             * Direction: %s (%s)<br>
             * Entry: %.5f | SL: %.5f | TP: %.5f<br>
             * Quantity: %s | Max bars: %d<br>
             * Confidence: %d%%<br>
             * Reason: %s</p>
             *
             * <p>Auto-expires after %d bars or Friday 23:00 ET.</p>
             */
            public final class %s implements Strategy {

                private final WeeklyStrategy delegate;

                public %s() {
                    this.delegate = new WeeklyStrategy(
                        "%s",
                        "%s",
                        Order.Side.%s,
                        WeeklyStrategy.StopOrderType.%s,
                        %.10f,
                        %.10f,
                        %.10f,
                        %.10f,
                        %d,
                        "%s",
                        %d
                    );
                }

                @Override
                public String name() { return delegate.name(); }

                @Override
                public void onBar(Bar bar) { delegate.onBar(bar); }

                @Override
                public void onTick(double bid, double ask, long volume) { delegate.onTick(bid, ask, volume); }

                @Override
                public List<Order> getPendingOrders() { return delegate.getPendingOrders(); }

                @Override
                public void reset() { delegate.reset(); }
            }
            """,
            WEEKLY_PACKAGE,                                   // package
            className,                                        // class Javadoc title
            escapeHtml(entry.pair()),                           // pair
            escapeHtml(entry.direction()),                      // direction
            escapeHtml(entry.orderType()),                      // orderType
            entry.entryPrice(),                                 // entry
            entry.stopLoss(),                                   // SL
            entry.takeProfit(),                                 // TP
            fmtQuantity(entry.quantity()),                     // quantity
            entry.maxBars(),                                   // max bars
            entry.confidence(),                                // confidence
            escapeHtml(entry.reason()),                        // reason
            entry.maxBars(),                                   // expiry bars (repeat)
            className,                                         // class header
            className,                                         // constructor
            escapeJava(entry.name()),                           // strategy name
            entry.pair(),                                      // symbol
            directionField,                                    // side
            entry.orderType(),                                 // orderType
            entry.entryPrice(),                                // entryPrice
            entry.stopLoss(),                                  // stopLoss
            entry.takeProfit(),                                // takeProfit
            entry.quantity(),                                  // quantity
            entry.maxBars(),                                   // maxBars
            escapeJava(entry.reason()),                        // reason
            entry.confidence()                                 // confidence
        );
    }

    /**
     * Writes the deployment summary JSON.
     */
    private void writeSummary(String week, String generatedAt, String analyst) throws IOException {
        Files.createDirectories(DEPLOY_DIR);

        String dateKey = week.isEmpty() ? LocalDate.now().toString() : week;
        Path summaryPath = DEPLOY_DIR.resolve(dateKey + "-summary.json");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("week", week);
        summary.put("generatedAt", generatedAt);
        summary.put("analyst", analyst);
        summary.put("tradeCount", trades.size());
        summary.put("fileCount", generatedFiles.size());
        summary.put("errorCount", errors.size());
        summary.put("generatedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));

        // Add trade list
        ArrayNode tradesArray = summary.putArray("trades");
        for (TradeEntry entry : trades) {
            ObjectNode t = tradesArray.addObject();
            t.put("name", entry.name());
            t.put("pair", entry.pair());
            t.put("direction", entry.direction());
            t.put("orderType", entry.orderType());
            t.put("entryPrice", entry.entryPrice());
            t.put("stopLoss", entry.stopLoss());
            t.put("takeProfit", entry.takeProfit());
            t.put("quantity", entry.quantity());
            t.put("maxBars", entry.maxBars());
            t.put("reason", entry.reason());
            t.put("confidence", entry.confidence());
        }

        // Add generated files list
        ArrayNode filesArray = summary.putArray("generatedFiles");
        for (String f : generatedFiles) {
            filesArray.add(f);
        }

        // Add errors if any
        if (!errors.isEmpty()) {
            ArrayNode errArray = summary.putArray("errors");
            for (String e : errors) {
                errArray.add(e);
            }
        }

        MAPPER.writeValue(summaryPath.toFile(), summary);
    }

    // ─── Utility ──────────────────────────────────────────────

    /** Sanitizes a trade name into a valid Java class name. */
    static String sanitizeClassName(String raw) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : raw.toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "T_");
        }
        return sb.toString();
    }

    private static String escapeJava(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String fmtQuantity(double qty) {
        return qty == (long) qty ? String.valueOf((long) qty) : String.valueOf(qty);
    }

    // ─── Main (CLI entry point) ───────────────────────────────

    /**
     * CLI entry point: {@code java ... WeeklyStrategyGenerator <config.json>}
     *
     * @param args first arg is the path to the JSON config
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WeeklyStrategyGenerator <config.json>");
            System.exit(1);
        }

        try {
            WeeklyStrategyGenerator gen = new WeeklyStrategyGenerator();
            boolean ok = gen.generate(args[0]);
            if (ok) {
                System.out.println("✅ All " + gen.trades.size() + " strategies generated successfully.");
                gen.generatedFiles.forEach(f -> System.out.println("   📄 " + f));
            } else {
                System.out.println("⚠️  Generated " + gen.generatedFiles.size()
                    + "/" + gen.trades.size() + " strategies with errors:");
                gen.errors.forEach(e -> System.out.println("   ❌ " + e));
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
