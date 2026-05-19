package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategyCodeGen}.
 */
class StrategyCodeGenTest {

    private final StrategyCodeGen codeGen = new StrategyCodeGen();

    @Test
    void testGenerateReturnsValidJavaCode() {
        Chromosome chromosome = createSmaCrossoverChromosome();
        String code = codeGen.generate(chromosome, "TestStrategy001");

        assertNotNull(code, "Generated code must not be null");
        assertFalse(code.isBlank(), "Generated code must not be blank");

        // Verify essential structural elements
        assertTrue(code.contains("package com.martinfou.trading.strategies.generated;"),
            "Must contain expected package declaration");
        assertTrue(code.contains("public class TestStrategy001 implements Strategy"),
            "Must declare class implementing Strategy");
        assertTrue(code.contains("@Override"),
            "Must contain @Override annotations");
        assertTrue(code.contains("public void onBar(Bar bar)"),
            "Must contain onBar method");
        assertTrue(code.contains("public List<Order> getPendingOrders()"),
            "Must contain getPendingOrders method");
        assertTrue(code.contains("public void reset()"),
            "Must contain reset method");
        assertTrue(code.contains("private static double sma(List<Bar> data, int period, String field)"),
            "Must contain sma helper method for SMA genes");
        assertTrue(code.contains("private static double smaPrev(List<Bar> data, int period, String field)"),
            "Must contain smaPrev helper method");

        // Verify entry/exit logic is present
        assertTrue(code.contains("BUY"), "Must contain BUY order creation");
        assertTrue(code.contains("SELL"), "Must contain SELL order creation");
        assertTrue(code.contains("STOP_LOSS_POINTS"), "Must reference stop-loss");
        assertTrue(code.contains("TAKE_PROFIT_POINTS"), "Must reference take-profit");
        assertTrue(code.contains("stopLoss = 30") || code.contains("STOP_LOSS_POINTS = 30"),
            "Must contain the stop-loss value from the chromosome");
        assertTrue(code.contains("takeProfit = 60") || code.contains("TAKE_PROFIT_POINTS = 60"),
            "Must contain the take-profit value from the chromosome");
    }

    @Test
    void testGenerateWithAllIndicatorTypes() {
        // Chromosome with all 5 indicator types to exercise all code paths
        List<Gene> entries = List.of(
            new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.EMA, 20, Gene.Field.HIGH)
        );
        List<Gene> exits = List.of(
            new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.ATR, 10, Gene.Field.LOW),
            new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE)
        );
        Chromosome chromosome = new Chromosome(entries, exits, 50, 100);
        String code = codeGen.generate(chromosome, "AllIndicatorsStrategy");

        assertTrue(code.contains("sma("), "Must call sma for SMA gene");
        assertTrue(code.contains("ema("), "Must call ema for EMA gene");
        assertTrue(code.contains("rsi("), "Must call rsi for RSI gene");
        assertTrue(code.contains("atr("), "Must call atr for ATR gene");
        assertTrue(code.contains("adx("), "Must call adx for ADX gene");
        assertTrue(code.contains("private static double ema("),
            "Must contain ema helper method");
        assertTrue(code.contains("private static double rsi("),
            "Must contain rsi helper method");
        assertTrue(code.contains("private static double atr("),
            "Must contain atr helper method");
        assertTrue(code.contains("private static double adx("),
            "Must contain adx helper method");
    }

    @Test
    void testGenerateWithSingleEntryGene() {
        // Single RSI entry → threshold-based (30.0 oversold)
        // Single SMA exit → trend-based (current < previous)
        Chromosome chromosome = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)),
            20, 40
        );
        String code = codeGen.generate(chromosome, "SingleEntryStrategy");

        assertTrue(code.contains("30.0"), "Single RSI entry should use 30.0 oversold threshold");
        // Exit is SMA: should use trend-following comparison with prev value
        assertTrue(code.contains("sma_50_close") && code.contains("prevSma_50_close"),
            "SMA exit should compare current vs previous values");
    }

    @Test
    void testGenerateCodeCompilesWithJavac(@TempDir Path tempDir) throws Exception {
        // Generate code from a chromosome
        Chromosome chromosome = createSmaCrossoverChromosome();
        String code = codeGen.generate(chromosome, "CompilableStrategy");

        // Write to a .java file in the temp directory
        Path pkgDir = tempDir.resolve("com/martinfou/trading/strategies/generated");
        Files.createDirectories(pkgDir);
        Path sourceFile = pkgDir.resolve("CompilableStrategy.java");
        Files.writeString(sourceFile, code);

        // Compile with javac
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System JavaCompiler must be available");

        // Build classpath: need trading-core and trading-genetics jars
        String classpath = buildClasspath();

        List<String> options = List.of(
            "-d", tempDir.resolve("classes").toString(),
            "-cp", classpath,
            sourceFile.toString()
        );

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, null, errStream, options.toArray(String[]::new));
        String compilerOutput = errStream.toString("UTF-8");

        assertEquals(0, exitCode,
            "Generated code must compile without errors. Compiler output:\n" + compilerOutput);

        // Verify the compiled .class file exists
        Path classFile = tempDir.resolve("classes/com/martinfou/trading/strategies/generated/CompilableStrategy.class");
        assertTrue(Files.exists(classFile), "Compiled .class file must exist");
    }

    @Test
    void testGeneratedCodeImplementsStrategy(@TempDir Path tempDir) throws Exception {
        // Generate and compile a strategy
        Chromosome chromosome = createSmaCrossoverChromosome();
        String code = codeGen.generate(chromosome, "StrategyImpTest");

        // Write and compile
        compileInTempDir(tempDir, "StrategyImpTest", code);

        // Load the class and verify it implements Strategy
        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()},
                getClass().getClassLoader())) {

            Class<?> loaded = loader.loadClass("com.martinfou.trading.strategies.generated.StrategyImpTest");
            assertTrue(Strategy.class.isAssignableFrom(loaded),
                "Generated class must implement " + Strategy.class.getName());

            // Instantiate via reflection
            var constructor = loaded.getConstructor(String.class);
            Strategy strategy = (Strategy) constructor.newInstance("EUR_USD");

            assertEquals("StrategyImpTest", strategy.name(),
                "name() should return the class name");

            // Verify getPendingOrders returns empty list initially
            List<Order> orders = strategy.getPendingOrders();
            assertNotNull(orders, "getPendingOrders must not return null");
            assertTrue(orders.isEmpty(), "Pending orders should be empty initially");

            // Verify reset works
            strategy.reset();
            assertTrue(strategy.getPendingOrders().isEmpty(), "After reset, pending orders must be empty");
        }
    }

    @Test
    void testSmaCrossoverProducesOrders(@TempDir Path tempDir) throws Exception {
        // Chromosome where SMA(14) crossing above SMA(50) triggers BUY,
        // and SMA(14) crossing below SMA(50) triggers SELL.
        Chromosome chromosome = new Chromosome(
            List.of(
                new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
                new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)
            ),
            List.of(
                new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
                new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)
            ),
            30, 60
        );

        String code = codeGen.generate(chromosome, "SmaCrossoverLive");
        compileInTempDir(tempDir, "SmaCrossoverLive", code);

        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[]{tempDir.resolve("classes").toUri().toURL()},
                getClass().getClassLoader())) {

            Class<?> loaded = loader.loadClass(
                "com.martinfou.trading.strategies.generated.SmaCrossoverLive");
            Strategy strategy = (Strategy) loaded.getConstructor(String.class)
                .newInstance("EUR_USD");

            String symbol = "EUR_USD";
            double basePrice = 1.10000;

            // Phase 1: 50 bars of downtrend → SMA(14) stays below SMA(50)
            for (int i = 0; i < 50; i++) {
                double price = basePrice - (i * 0.0002);
                strategy.onBar(new Bar(symbol, Instant.now(), price - 0.0002,
                    price + 0.0002, price - 0.0002, price, 1000));
            }

            // Check that no entry was triggered during downtrend
            List<Order> orders = strategy.getPendingOrders();
            assertTrue(orders.isEmpty(),
                "No BUY should be generated during downtrend (SMA(14) < SMA(50))");

            // Phase 2: 30 bars of sharp uptrend → SMA(14) crosses above SMA(50)
            for (int i = 0; i < 30; i++) {
                double price = (basePrice - 50 * 0.0002) + (i * 0.0010);
                strategy.onBar(new Bar(symbol, Instant.now(), price - 0.0002,
                    price + 0.0002, price - 0.0002, price, 1000));
            }

            orders = strategy.getPendingOrders();
            assertFalse(orders.isEmpty(),
                "Should have generated a BUY after SMA(14) crosses above SMA(50)");

            boolean hasBuy = orders.stream().anyMatch(o -> o.side() == Order.Side.BUY);
            assertTrue(hasBuy, "Should contain at least one BUY order");
        }
    }

    @Test
    void testClassNameRejectsBlank() {
        Chromosome c = createSmaCrossoverChromosome();
        assertThrows(IllegalArgumentException.class,
            () -> codeGen.generate(c, ""));
        assertThrows(IllegalArgumentException.class,
            () -> codeGen.generate(c, "   "));
    }

    @Test
    void testChromosomeNullRejected() {
        assertThrows(NullPointerException.class,
            () -> codeGen.generate(null, "TestStrategy"));
    }

    // ---------------------------------------------------------------
    //  Helper methods
    // ---------------------------------------------------------------

    /** Creates a simple SMA crossover chromosome for testing. */
    private static Chromosome createSmaCrossoverChromosome() {
        return new Chromosome(
            List.of(
                new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
                new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)
            ),
            List.of(
                new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
                new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)
            ),
            30, 60
        );
    }

    /**
     * Builds the classpath for javac by extracting the trading-core JAR
     * (and other trading-* JARs) from the current JVM's classpath.
     */
    private static String buildClasspath() {
        String cp = System.getProperty("java.class.path", "");
        StringBuilder sb = new StringBuilder();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains("trading-core")
                || entry.contains("trading-genetics")
                || entry.contains("trading-backtest")) {
                if (!sb.isEmpty()) sb.append(File.pathSeparator);
                sb.append(entry);
            }
        }
        // Fallback: try target/classes directories if JARs weren't found
        if (sb.isEmpty()) {
            sb.append("trading-core/target/classes")
              .append(File.pathSeparator)
              .append("trading-genetics/target/classes");
        }
        return sb.toString();
    }

    /** Writes, compiles, and places generated code in {@code tempDir/classes}. */
    private void compileInTempDir(Path tempDir, String className, String code) throws Exception {
        Path pkgDir = tempDir.resolve("com/martinfou/trading/strategies/generated");
        Files.createDirectories(pkgDir);
        Path sourceFile = pkgDir.resolve(className + ".java");
        Files.writeString(sourceFile, code);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System JavaCompiler must be available");

        String cp = buildClasspath();

        List<String> options = List.of(
            "-d", tempDir.resolve("classes").toString(),
            "--release", "21",
            "-cp", cp,
            sourceFile.toString()
        );

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, null, err, options.toArray(String[]::new));
        assertEquals(0, exitCode,
            "Compilation failed for generated " + className + ":\n" + err);
    }
}
