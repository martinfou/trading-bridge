package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategyExporter}.
 */
class StrategyExporterTest {

    private StrategyExporter exporter;
    private Chromosome smaCrossover;

    @BeforeEach
    void setUp() {
        exporter = new StrategyExporter();
        smaCrossover = new Chromosome(
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

    // ---------------------------------------------------------------
    //  export() tests
    // ---------------------------------------------------------------

    @Test
    void testExportProducesFile() {
        StrategyExporter.ExportResult result = exporter.export(smaCrossover, "ExportTest001");

        assertNotNull(result, "ExportResult must not be null");
        assertEquals("ExportTest001", result.className(), "Class name must match");
        assertNotNull(result.sourceCode(), "Source code must not be null");
        assertFalse(result.sourceCode().isBlank(), "Source code must not be blank");
        assertNotNull(result.filePath(), "File path must not be null");
        assertFalse(result.compiled(), "compiled must be false (export only)");
        assertNull(result.backtestResult(), "backtestResult must be null for export only");

        // Verify the file was written
        Path filePath = Path.of(result.filePath());
        assertTrue(Files.exists(filePath), "Generated file must exist on disk");
        assertTrue(filePath.toString().endsWith("ExportTest001.java"),
            "File must end with class name");

        // Verify source content
        String content = result.sourceCode();
        assertTrue(content.contains("class ExportTest001 implements Strategy"),
            "Generated code must declare the correct class");
        assertTrue(content.contains("package com.martinfou.trading.strategies.generated;"),
            "Generated code must have correct package");

        // Clean up
        filePath.toFile().delete();
    }

    @Test
    void testExportWithMomentumChromosome() {
        // Use a momentum-style chromosome
        Chromosome momentum = new Chromosome(
            List.of(
                new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE),
                new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)
            ),
            List.of(new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE)),
            60, 200
        );

        StrategyExporter.ExportResult result = exporter.export(momentum, "MomentumExport");

        assertNotNull(result);
        assertTrue(result.sourceCode().contains("ema("), "Should contain EMA indicator code");
        assertTrue(result.sourceCode().contains("rsi("), "Should contain RSI indicator code");
        assertTrue(Files.exists(Path.of(result.filePath())), "File must exist");

        // Clean up
        Path.of(result.filePath()).toFile().delete();
    }

    @Test
    void testExportRejectsNullChromosome() {
        assertThrows(NullPointerException.class,
            () -> exporter.export(null, "Test"));
    }

    @Test
    void testExportRejectsNullClassName() {
        assertThrows(NullPointerException.class,
            () -> exporter.export(smaCrossover, null));
    }

    @Test
    void testExportRejectsBlankClassName() {
        assertThrows(IllegalArgumentException.class,
            () -> exporter.export(smaCrossover, ""));
        assertThrows(IllegalArgumentException.class,
            () -> exporter.export(smaCrossover, "   "));
    }

    // ---------------------------------------------------------------
    //  exportAndTest() tests
    // ---------------------------------------------------------------

    @Test
    void testExportAndTestReturnsValidResult() {
        List<Bar> bars = TestBarData.generateTrendingBars(250, 1.1000);

        StrategyExporter.ExportResult result = exporter.exportAndTest(
            smaCrossover, "ExportAndTest001", bars, 100_000.0);

        assertNotNull(result, "ExportResult must not be null");
        assertEquals("ExportAndTest001", result.className(), "Class name must match");
        assertNotNull(result.sourceCode(), "Source code must not be null");
        assertNotNull(result.filePath(), "File path must not be null");
        assertTrue(result.compiled(), "Strategy must have compiled successfully");

        // Verify backtest result
        BacktestResult bt = result.backtestResult();
        assertNotNull(bt, "BacktestResult must not be null");
        assertEquals("ExportAndTest001", bt.strategyName(), "Strategy name must match");
        assertEquals(100_000.0, bt.initialCapital(), 0.001, "Initial capital must match");
        assertTrue(bt.totalTrades() >= 0, "Total trades must be non-negative");
        assertTrue(bt.finalEquity() >= 0.0, "Final equity must not be negative");
        assertTrue(bt.totalPnl() >= -100_000.0, "P&L must not exceed initial capital loss");
        // Sharpe / other metrics may be NaN with insufficient trades on trending data;
        // this is a valid backtest result, not an export failure

        // Verify summary doesn't throw
        result.printSummary();

        // Clean up
        Path.of(result.filePath()).toFile().delete();
    }

    @Test
    void testExportAndTestBacktestMetricsPopulated() {
        List<Bar> bars = TestBarData.generateSineBars(500, 1.1000, 0.01);

        StrategyExporter.ExportResult result = exporter.exportAndTest(
            smaCrossover, "MetricsTest001", bars, 50_000.0);

        assertTrue(result.compiled(), "Must compile");
        assertNotNull(result.backtestResult(), "Must have backtest result");

        BacktestResult bt = result.backtestResult();
        assertEquals(50_000.0, bt.initialCapital(), 0.001);
        assertTrue(bt.totalTrades() > 0,
            "Should have at least one trade on sine wave data");

        // Period start/end should be set
        assertNotNull(bt.periodStart(), "Period start must be set");
        assertNotNull(bt.periodEnd(), "Period end must be set");

        // Clean up
        Path.of(result.filePath()).toFile().delete();
    }

    @Test
    void testExportAndTestRejectsEmptyBars() {
        assertThrows(IllegalArgumentException.class,
            () -> exporter.exportAndTest(
                smaCrossover, "EmptyBars", List.of(), 100_000.0));
    }

    @Test
    void testExportAndTestRejectsNonPositiveCapital() {
        List<Bar> bars = TestBarData.generateTrendingBars(50, 1.1000);
        assertThrows(IllegalArgumentException.class,
            () -> exporter.exportAndTest(smaCrossover, "BadCap", bars, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> exporter.exportAndTest(smaCrossover, "BadCap", bars, -100.0));
    }

    // ---------------------------------------------------------------
    //  Compilation tests (standalone, via JavaCompiler)
    // ---------------------------------------------------------------

    @Test
    void testGeneratedFileCompiles(@TempDir Path tempDir) throws Exception {
        // Export first
        StrategyExporter.ExportResult result = exporter.export(smaCrossover, "CompileTest001");

        // Now manually compile via JavaCompiler (like StrategyCodeGenTest does)
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System JavaCompiler must be available");

        String sourceCode = result.sourceCode();
        Path pkgDir = tempDir.resolve("com/martinfou/trading/strategies/generated");
        Files.createDirectories(pkgDir);
        Path sourceFile = pkgDir.resolve("CompileTest001.java");
        Files.writeString(sourceFile, sourceCode);

        String classpath = StrategyExporter.buildClasspath();
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        List<String> options = List.of(
            "-d", classesDir.toString(),
            "--release", "21",
            "-cp", classpath,
            sourceFile.toString()
        );

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, null, err, options.toArray(String[]::new));
        assertEquals(0, exitCode,
            "Generated code must compile without errors.\nCompiler output:\n" + err);

        // Verify .class file exists
        Path classFile = classesDir.resolve(
            "com/martinfou/trading/strategies/generated/CompileTest001.class");
        assertTrue(Files.exists(classFile), "Compiled .class file must exist");

        // Verify it can be loaded and implements Strategy
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                getClass().getClassLoader())) {

            Class<?> loaded = loader.loadClass(
                "com.martinfou.trading.strategies.generated.CompileTest001");
            assertTrue(Strategy.class.isAssignableFrom(loaded),
                "Generated class must implement " + Strategy.class.getName());

            Strategy strategy = (Strategy) loaded.getConstructor(String.class)
                .newInstance("EUR_USD");
            assertEquals("CompileTest001", strategy.name());
        }

        // Clean up the exported file
        Path.of(result.filePath()).toFile().delete();
    }

    @Test
    void testExportAndTestCompilesFromTemp() {
        // This validates that exportAndTest() internally compiles the generated
        // code using JavaCompiler (not just Maven compile)
        List<Bar> bars = TestBarData.generateTrendingBars(100, 1.1000);

        StrategyExporter.ExportResult result = exporter.exportAndTest(
            smaCrossover, "InternalCompileTest", bars, 100_000.0);

        assertTrue(result.compiled(),
            "exportAndTest must compile successfully using JavaCompiler");
        assertNotNull(result.backtestResult(),
            "Backtest result must be present after successful compile");
        assertTrue(result.backtestResult().totalTrades() >= 0,
            "Backtest must complete with non-negative trades");

        // Clean up
        Path.of(result.filePath()).toFile().delete();
    }

    // ---------------------------------------------------------------
    //  Project root detection
    // ---------------------------------------------------------------

    @Test
    void testDetectProjectRoot() {
        Path root = StrategyExporter.detectProjectRoot();
        assertNotNull(root, "Project root must not be null");
        assertTrue(Files.exists(root), "Project root must exist");
        assertTrue(Files.exists(root.resolve("pom.xml")),
            "Project root must contain pom.xml");
    }

    @Test
    void testBuildClasspath() {
        String cp = StrategyExporter.buildClasspath();
        assertNotNull(cp, "Classpath must not be null");
        assertFalse(cp.isBlank(), "Classpath must not be blank");
        assertTrue(cp.contains("trading-core"),
            "Classpath must include trading-core module classes");
        assertTrue(cp.contains("trading-genetics"),
            "Classpath must include trading-genetics module classes");
        assertTrue(cp.contains("trading-backtest"),
            "Classpath must include trading-backtest module classes");
    }

    // ---------------------------------------------------------------
    //  Edge cases
    // ---------------------------------------------------------------

    @Test
    void testExportWithAllIndicatorTypes() {
        List<Gene> entries = List.of(
            new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE)
        );
        List<Gene> exits = List.of(
            new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)
        );
        Chromosome chromo = new Chromosome(entries, exits, 50, 100);

        StrategyExporter.ExportResult result = exporter.export(chromo, "AllIndicatorsExport");

        String code = result.sourceCode();
        assertTrue(code.contains("sma("));
        assertTrue(code.contains("adx("));
        assertTrue(code.contains("rsi("));
        assertTrue(Files.exists(Path.of(result.filePath())));

        Path.of(result.filePath()).toFile().delete();
    }

    @Test
    void testExportWithZeroStopLoss() {
        Chromosome noSL = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            0, 50
        );
        StrategyExporter.ExportResult result = exporter.export(noSL, "NoStopLossExport");

        assertTrue(result.sourceCode().contains("STOP_LOSS_POINTS = 0"),
            "Generated code must reflect zero stop-loss");
        assertTrue(result.sourceCode().contains("TAKE_PROFIT_POINTS = 50"),
            "Generated code must reflect take-profit of 50");

        Path.of(result.filePath()).toFile().delete();
    }
}
