package com.martinfou.trading.genetics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates compilable Java source code for a trading strategy class
 * from a {@link Chromosome} (the DNA of a strategy).
 *
 * <p>The generated class implements {@code com.martinfou.trading.core.Strategy}
 * and is placed in the package {@code com.martinfou.trading.strategies.generated}.
 * It hardcodes all indicator periods, fields, entry/exit logic, and
 * risk-management parameters so that the result is a standalone,
 * self-contained trading strategy.</p>
 *
 * <p><b>Generation rules:</b></p>
 * <ul>
 *   <li>For each unique indicator gene, a compute method is generated.</li>
 *   <li>Entry conditions are evaluated from {@link Chromosome#entryGenes()}:</li>
 *   <ul>
 *     <li>Two or more entry genes {@literal →} trigger when {@code gene[0] > gene[1]}.</li>
 *     <li>Single entry gene (RSI) {@literal →} trigger when {@code RSI < 30}.</li>
 *     <li>Single entry gene (other) {@literal →} trigger when value {@literal >} previous value.</li>
 *   </ul>
 *   <li>Exit conditions are evaluated from {@link Chromosome#exitGenes()} (inverse logic).</li>
 *   <li>Stop-loss and take-profit offsets are read from the chromosome
 *       and applied to each BUY order.</li>
 * </ul>
 */
public final class StrategyCodeGen {

    private static final String PACKAGE = "com.martinfou.trading.strategies.generated";
    private static final String INDENT = "    ";

    /**
     * Generates a complete, compilable Java class that implements
     * {@code com.martinfou.trading.core.Strategy} from the given chromosome.
     *
     * @param chromosome the strategy DNA (must not be null)
     * @param className  the desired class name (must not be blank)
     * @return the Java source code as a string
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if className is blank
     */
    public String generate(Chromosome chromosome, String className) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        Objects.requireNonNull(className, "className must not be null");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }

        List<Gene> entryGenes = chromosome.entryGenes();
        List<Gene> exitGenes  = chromosome.exitGenes();
        int stopLoss   = chromosome.stopLoss();
        int takeProfit = chromosome.takeProfit();

        // Collect all unique genes (preserving insertion order)
        Set<Gene> allGenes = new LinkedHashSet<>();
        allGenes.addAll(entryGenes);
        allGenes.addAll(exitGenes);

        int maxPeriod = allGenes.stream()
            .mapToInt(Gene::period)
            .max()
            .orElse(2);

        StringBuilder sb = new StringBuilder(4096);

        // ============================================================
        //  Package & imports
        // ============================================================
        sb.append("package ").append(PACKAGE).append(";\n\n");
        sb.append("import com.martinfou.trading.core.Bar;\n");
        sb.append("import com.martinfou.trading.core.Order;\n");
        sb.append("import com.martinfou.trading.core.Strategy;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.List;\n\n");

        // ============================================================
        //  Class Javadoc
        // ============================================================
        sb.append("/**\n");
        sb.append(" * ").append(className).append(" — auto-generated from a genetic-algorithm chromosome.\n");
        sb.append(" *\n");
        sb.append(" * <p>Entry conditions:\n");
        sb.append(" * <ul>\n");
        for (Gene g : entryGenes) {
            sb.append(" *   <li>").append(describeGene(g)).append("</li>\n");
        }
        sb.append(" * </ul>\n");
        sb.append(" * <p>Exit conditions:\n");
        sb.append(" * <ul>\n");
        for (Gene g : exitGenes) {
            sb.append(" *   <li>").append(describeGene(g)).append("</li>\n");
        }
        sb.append(" * </ul>\n");
        sb.append(" */\n");

        // ============================================================
        //  Class declaration
        // ============================================================
        sb.append("public class ").append(className).append(" implements Strategy {\n\n");

        // ---- Fields ----
        sb.append(INDENT).append("private final String name;\n");
        sb.append(INDENT).append("private final String symbol;\n");
        sb.append(INDENT).append("private final List<Bar> history = new ArrayList<>();\n");
        sb.append(INDENT).append("private final List<Order> pendingOrders = new ArrayList<>();\n");
        sb.append(INDENT).append("private boolean hasPosition = false;\n\n");
        sb.append(INDENT).append("private static final int STOP_LOSS_POINTS = ").append(stopLoss).append(";\n");
        sb.append(INDENT).append("private static final int TAKE_PROFIT_POINTS = ").append(takeProfit).append(";\n");
        sb.append(INDENT).append("private static final double PRICE_SCALE = 0.0001;\n\n");

        // ---- Constructor ----
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Creates a new ").append(className).append(" strategy for the given symbol.\n");
        sb.append(INDENT).append(" *\n");
        sb.append(INDENT).append(" * @param symbol the trading symbol (e.g. \"EUR_USD\")\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT).append("public ").append(className).append("(String symbol) {\n");
        sb.append(INDENT).append(INDENT).append("this.name = \"").append(className).append("\";\n");
        sb.append(INDENT).append(INDENT).append("this.symbol = symbol;\n");
        sb.append(INDENT).append("}\n\n");

        // ---- name() ----
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public String name() { return name; }\n\n");

        // ============================================================
        //  onBar() — the core strategy logic
        // ============================================================
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public void onBar(Bar bar) {\n");
        sb.append(INDENT).append(INDENT).append("history.add(bar);\n\n");
        sb.append(INDENT).append(INDENT)
            .append("if (history.size() < ").append(maxPeriod).append(") return;\n\n");

        // --- Compute indicator values ---
        sb.append(INDENT).append(INDENT).append("// --- Compute indicator values ---\n");
        for (Gene g : allGenes) {
            String varName  = geneVarName(g);
            String method   = computeMethodName(g);
            String args     = computeArgs(g);
            sb.append(INDENT).append(INDENT)
                .append("double ").append(varName).append(" = ")
                .append(method).append("(history, ").append(args).append(");\n");
        }
        sb.append('\n');

        // Compute previous indicator values for crossover / trend-following conditions
        if (needsPreviousValues(entryGenes, exitGenes)) {
            sb.append(INDENT).append(INDENT).append("// --- Previous indicator values ---\n");
            for (Gene g : allGenes) {
                String varName  = geneVarName(g);
                String prevVar  = "prev" + capitalise(varName);
                String method   = computeMethodName(g) + "Prev";
                String args     = computeArgs(g);
                sb.append(INDENT).append(INDENT)
                    .append("double ").append(prevVar).append(" = ")
                    .append(method).append("(history, ").append(args).append(");\n");
            }
            sb.append('\n');
        }

        // --- Entry conditions ---
        sb.append(INDENT).append(INDENT).append("// --- Entry conditions ---\n");
        sb.append(INDENT).append(INDENT).append("if (!hasPosition) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("boolean entryTriggered = false;\n");

        generateCondition(sb, INDENT + INDENT + INDENT, entryGenes, true);
        sb.append('\n');

        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("if (entryTriggered) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("double price = bar.close();\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("Order order = new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price);\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("if (STOP_LOSS_POINTS > 0) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("order.withStopLoss(price - STOP_LOSS_POINTS * PRICE_SCALE);\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("if (TAKE_PROFIT_POINTS > 0) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("order.withTakeProfit(price + TAKE_PROFIT_POINTS * PRICE_SCALE);\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("pendingOrders.add(order);\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("hasPosition = true;\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append("}\n");
        sb.append(INDENT).append(INDENT).append("}\n\n");

        // --- Exit conditions ---
        sb.append(INDENT).append(INDENT).append("// --- Exit conditions ---\n");
        sb.append(INDENT).append(INDENT).append("if (hasPosition) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("boolean exitTriggered = false;\n");

        generateCondition(sb, INDENT + INDENT + INDENT, exitGenes, false);
        sb.append('\n');

        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("if (exitTriggered) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, bar.close()));\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("hasPosition = false;\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append("}\n");
        sb.append(INDENT).append(INDENT).append("}\n");
        sb.append(INDENT).append("}\n\n");

        // ---- onTick() ----
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public void onTick(double bid, double ask, long volume) {\n");
        sb.append(INDENT).append(INDENT)
            .append("// Bar-based strategy; tick data not used\n");
        sb.append(INDENT).append("}\n\n");

        // ---- getPendingOrders() ----
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public List<Order> getPendingOrders() {\n");
        sb.append(INDENT).append(INDENT)
            .append("List<Order> copy = new ArrayList<>(pendingOrders);\n");
        sb.append(INDENT).append(INDENT)
            .append("pendingOrders.clear();\n");
        sb.append(INDENT).append(INDENT)
            .append("return copy;\n");
        sb.append(INDENT).append("}\n\n");

        // ---- reset() ----
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public void reset() {\n");
        sb.append(INDENT).append(INDENT)
            .append("history.clear();\n");
        sb.append(INDENT).append(INDENT)
            .append("pendingOrders.clear();\n");
        sb.append(INDENT).append(INDENT)
            .append("hasPosition = false;\n");
        sb.append(INDENT).append("}\n\n");

        // ============================================================
        //  Helper methods for indicator computation
        // ============================================================
        generateIndicatorMethods(sb, allGenes);

        sb.append("}\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Code generation helpers
    // ---------------------------------------------------------------

    /**
     * Generates the entry or exit condition evaluation code.
     *
     * @param sb          the StringBuilder
     * @param prefix      indentation prefix
     * @param genes       the entry or exit genes
     * @param isEntry     {@code true} for entry, {@code false} for exit
     */
    private void generateCondition(StringBuilder sb, String prefix,
                                    List<Gene> genes, boolean isEntry) {
        if (genes.size() >= 2) {
            Gene g0 = genes.get(0);
            Gene g1 = genes.get(1);
            String v0 = geneVarName(g0);
            String v1 = geneVarName(g1);

            String prevV0 = "prev" + capitalise(v0);
            String prevV1 = "prev" + capitalise(v1);

            if (isEntry) {
                // Entry: cross above
                sb.append(prefix)
                    .append("if (").append(prevV0).append(" <= ").append(prevV1)
                    .append(" && ").append(v0).append(" > ").append(v1).append(") {\n");
                sb.append(prefix).append(INDENT)
                    .append("entryTriggered = true;\n");
                sb.append(prefix).append("}\n");
            } else {
                // Exit: cross below (or inverse)
                sb.append(prefix)
                    .append("if (").append(prevV0).append(" >= ").append(prevV1)
                    .append(" && ").append(v0).append(" < ").append(v1).append(") {\n");
                sb.append(prefix).append(INDENT)
                    .append("exitTriggered = true;\n");
                sb.append(prefix).append("}\n");
            }
        } else if (genes.size() == 1) {
            Gene g = genes.get(0);
            String v = geneVarName(g);
            String prevV = "prev" + capitalise(v);

            if (isEntry) {
                if (g.indicatorType() == Gene.IndicatorType.RSI) {
                    // RSI oversold
                    sb.append(prefix)
                        .append("if (").append(v).append(" < 30.0) {\n");
                    sb.append(prefix).append(INDENT)
                        .append("entryTriggered = true;\n");
                    sb.append(prefix).append("}\n");
                } else {
                    // Trend up: current > previous
                    sb.append(prefix)
                        .append("if (").append(v).append(" > ").append(prevV).append(") {\n");
                    sb.append(prefix).append(INDENT)
                        .append("entryTriggered = true;\n");
                    sb.append(prefix).append("}\n");
                }
            } else {
                if (g.indicatorType() == Gene.IndicatorType.RSI) {
                    // RSI overbought
                    sb.append(prefix)
                        .append("if (").append(v).append(" > 70.0) {\n");
                    sb.append(prefix).append(INDENT)
                        .append("exitTriggered = true;\n");
                    sb.append(prefix).append("}\n");
                } else {
                    // Trend down: current < previous
                    sb.append(prefix)
                        .append("if (").append(v).append(" < ").append(prevV).append(") {\n");
                    sb.append(prefix).append(INDENT)
                        .append("exitTriggered = true;\n");
                    sb.append(prefix).append("}\n");
                }
            }
        }
    }

    /**
     * Generates the static helper methods for computing indicators from
     * the bar history.
     */
    private void generateIndicatorMethods(StringBuilder sb, Set<Gene> allGenes) {
        boolean hasSma = false, hasEma = false, hasRsi = false, hasAtr = false, hasAdx = false;
        for (Gene g : allGenes) {
            switch (g.indicatorType()) {
                case SMA -> hasSma = true;
                case EMA -> hasEma = true;
                case RSI -> hasRsi = true;
                case ATR -> hasAtr = true;
                case ADX -> hasAdx = true;
            }
        }

        // ---- getFieldValue helper (always generated) ----
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Extracts the named price field from a Bar.\n");
        sb.append(INDENT).append(" *\n");
        sb.append(INDENT).append(" * @param bar   the bar\n");
        sb.append(INDENT).append(" * @param field field name (CLOSE, OPEN, HIGH, LOW)\n");
        sb.append(INDENT).append(" * @return the field value\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT).append("private static double getFieldValue(Bar bar, String field) {\n");
        sb.append(INDENT).append(INDENT).append("return switch (field) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("case \"CLOSE\" -> bar.close();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("case \"OPEN\"  -> bar.open();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("case \"HIGH\"  -> bar.high();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("case \"LOW\"   -> bar.low();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("default -> throw new IllegalArgumentException(\"Unknown field: \" + field);\n");
        sb.append(INDENT).append(INDENT).append("};\n");
        sb.append(INDENT).append("}\n\n");

        if (hasSma) {
            generateSmaMethods(sb);
        }
        if (hasEma) {
            generateEmaMethods(sb);
        }
        if (hasRsi) {
            generateRsiMethods(sb);
        }
        if (hasAtr) {
            generateAtrMethods(sb);
        }
        if (hasAdx) {
            generateAdxMethods(sb);
        }
    }

    /** Generates the {@code sma} and {@code smaPrev} methods. */
    private void generateSmaMethods(StringBuilder sb) {
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes a Simple Moving Average from the bar history.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double sma(List<Bar> data, int period, String field) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double sum = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = n - period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sum += getFieldValue(data.get(i), field);\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("return sum / period;\n");
        sb.append(INDENT).append("}\n\n");

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the SMA as of the previous bar (one bar ago).\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double smaPrev(List<Bar> data, int period, String field) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size() - 1;\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double sum = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = n - period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sum += getFieldValue(data.get(i), field);\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("return sum / period;\n");
        sb.append(INDENT).append("}\n\n");
    }

    /** Generates the {@code ema} and {@code emaPrev} methods. */
    private void generateEmaMethods(StringBuilder sb) {
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes an Exponential Moving Average from the bar history.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double ema(List<Bar> data, int period, String field) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double multiplier = 2.0 / (period + 1);\n");
        sb.append(INDENT).append(INDENT)
            .append("// Seed with SMA of the first 'period' values\n");
        sb.append(INDENT).append(INDENT)
            .append("double sum = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = 0; i < period; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sum += getFieldValue(data.get(i), field);\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("double ema = sum / period;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double price = getFieldValue(data.get(i), field);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("ema = (price - ema) * multiplier + ema;\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("return ema;\n");
        sb.append(INDENT).append("}\n\n");

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the EMA as of the previous bar.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double emaPrev(List<Bar> data, int period, String field) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n <= period) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("return ema(data.subList(0, n - 1), period, field);\n");
        sb.append(INDENT).append("}\n\n");
    }

    /** Generates the {@code rsi} and {@code rsiPrev} methods. */
    private void generateRsiMethods(StringBuilder sb) {
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the Relative Strength Index from the bar history.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double rsi(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period + 1) return 50.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double avgGain = 0.0, avgLoss = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = n - period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double change = data.get(i).close() - data.get(i - 1).close();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("avgGain += Math.max(change, 0.0);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("avgLoss += Math.max(-change, 0.0);\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("avgGain /= period;\n");
        sb.append(INDENT).append(INDENT)
            .append("avgLoss /= period;\n");
        sb.append(INDENT).append(INDENT)
            .append("if (avgLoss == 0.0) return 100.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double rs = avgGain / avgLoss;\n");
        sb.append(INDENT).append(INDENT)
            .append("return 100.0 - (100.0 / (1.0 + rs));\n");
        sb.append(INDENT).append("}\n\n");

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the RSI as of the previous bar.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double rsiPrev(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n <= period + 1) return 50.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("return rsi(data.subList(0, n - 1), period);\n");
        sb.append(INDENT).append("}\n\n");
    }

    /** Generates the {@code atr} and {@code atrPrev} methods. */
    private void generateAtrMethods(StringBuilder sb) {
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the Average True Range from the bar history.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double atr(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period + 1) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double sum = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = n - period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("Bar b = data.get(i);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("Bar prev = data.get(i - 1);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double tr = Math.max(b.high() - b.low(),\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("Math.max(Math.abs(b.high() - prev.close()),\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("Math.abs(b.low() - prev.close())));\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sum += tr;\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("return sum / period;\n");
        sb.append(INDENT).append("}\n\n");

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the ATR as of the previous bar.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double atrPrev(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n <= period + 1) return 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("return atr(data.subList(0, n - 1), period);\n");
        sb.append(INDENT).append("}\n\n");
    }

    /** Generates the {@code adx} and {@code adxPrev} methods (simplified). */
    private void generateAdxMethods(StringBuilder sb) {
        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes a simplified ADX (Average Directional Index) from bar history.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double adx(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n < period + 1) return 25.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double sumPlusDM = 0.0, sumMinusDM = 0.0, sumTr = 0.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("for (int i = n - period; i < n; i++) {\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("Bar cur = data.get(i);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("Bar prev = data.get(i - 1);\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double upMove = cur.high() - prev.high();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double downMove = prev.low() - cur.low();\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0.0;\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0.0;\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("double tr = Math.max(cur.high() - cur.low(),\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("Math.max(Math.abs(cur.high() - prev.close()),\n");
        sb.append(INDENT).append(INDENT).append(INDENT).append(INDENT).append(INDENT)
            .append("Math.abs(cur.low() - prev.close())));\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sumPlusDM += plusDM;\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sumMinusDM += minusDM;\n");
        sb.append(INDENT).append(INDENT).append(INDENT)
            .append("sumTr += tr;\n");
        sb.append(INDENT).append(INDENT)
            .append("}\n");
        sb.append(INDENT).append(INDENT)
            .append("if (sumTr == 0.0) return 25.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("double pdi = 100.0 * sumPlusDM / sumTr;\n");
        sb.append(INDENT).append(INDENT)
            .append("double mdi = 100.0 * sumMinusDM / sumTr;\n");
        sb.append(INDENT).append(INDENT)
            .append("double dx = Math.abs(pdi - mdi) / (pdi + mdi) * 100.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("return dx;\n");
        sb.append(INDENT).append("}\n\n");

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * Computes the ADX as of the previous bar.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT)
            .append("private static double adxPrev(List<Bar> data, int period) {\n");
        sb.append(INDENT).append(INDENT)
            .append("int n = data.size();\n");
        sb.append(INDENT).append(INDENT)
            .append("if (n <= period + 1) return 25.0;\n");
        sb.append(INDENT).append(INDENT)
            .append("return adx(data.subList(0, n - 1), period);\n");
        sb.append(INDENT).append("}\n\n");
    }

    // ---------------------------------------------------------------
    //  Simple utility helpers
    // ---------------------------------------------------------------

    private static String describeGene(Gene g) {
        return g.indicatorType() + "(" + g.period() + ", " + g.field() + ")";
    }

    private static String geneVarName(Gene g) {
        return g.indicatorType().name().toLowerCase() + "_"
            + g.period() + "_"
            + g.field().name().toLowerCase();
    }

    private static String computeMethodName(Gene g) {
        return switch (g.indicatorType()) {
            case SMA -> "sma";
            case EMA -> "ema";
            case RSI -> "rsi";
            case ATR -> "atr";
            case ADX -> "adx";
        };
    }

    private static String computeArgs(Gene g) {
        return switch (g.indicatorType()) {
            case SMA, EMA -> g.period() + ", \"" + g.field().name() + "\"";
            case RSI, ATR, ADX -> String.valueOf(g.period());
        };
    }

    /**
     * Returns {@code true} when genes need previous indicator values:
     * two or more genes for crossover comparison, or a single non-RSI
     * gene for trend-following comparison.
     */
    private static boolean needsPreviousValues(List<Gene> entryGenes, List<Gene> exitGenes) {
        if (entryGenes.size() >= 2 || exitGenes.size() >= 2) return true;
        // Single non-RSI genes need prev values for trend comparison
        if (entryGenes.size() == 1 && entryGenes.get(0).indicatorType() != Gene.IndicatorType.RSI) return true;
        if (exitGenes.size() == 1 && exitGenes.get(0).indicatorType() != Gene.IndicatorType.RSI) return true;
        return false;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
