package com.martinfou.trading.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link JForexConverter}.
 */
class JForexConverterTest {

    private static final String JFOREX_SIMPLE = """
            package com.oanda.strategies;

            import com.oanda.backtest.BacktestEngine;
            import com.oanda.backtest.Strategy;
            import com.oanda.backtest.models.BacktestResult;
            import com.oanda.core.Bar;
            import com.oanda.core.indicators.*;

            import java.util.ArrayList;
            import java.util.List;

            /**
             * Simple test strategy.
             * Signal: Open croise SMA
             */
            public class SimpleTestStrategy implements Strategy {

                private List<Bar> bars;
                private final String name = "SimpleTest";
                private BacktestResult result;

                @Override
                public String getName() { return name; }

                @Override
                public List<Bar> getBars() { return bars; }

                @Override
                public void setBars(List<Bar> bars) { this.bars = bars; }

                @Override
                public void onBar(int index, BacktestEngine engine) {
                    if (index < 25) return;

                    double open3 = AppliedPrice.OPEN.getPrice(bars.get(index - 3));
                    double sma20 = SMAIndicator.calculate(bars, 20, AppliedPrice.CLOSE, index - 2);

                    if (Double.isNaN(sma20)) return;

                    boolean longEntry = open3 < sma20;
                    if (longEntry && !engine.isInPosition() && !engine.isPendingBuyStop()) {
                        double entryPrice = sma20;
                        engine.placeBuyStop(entryPrice, 95, 290, 70, 100, 168);
                    }
                }

                @Override
                public BacktestResult getResult() { return result; }
            }
            """;

    @Test
    void testPackageReplacement() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertTrue(result.contains("package com.martinfou.trading.strategies.sqimported;"),
                "Package should be replaced");
        assertFalse(result.contains("package com.oanda.strategies;"),
                "Old package should be removed");
    }

    @Test
    void testImportReplacement() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertFalse(result.contains("import com.oanda.backtest"),
                "Oanda backtest imports should be removed");
        assertFalse(result.contains("import com.oanda.core.indicators"),
                "Oanda indicator imports should be removed");
        assertTrue(result.contains("import com.martinfou.trading.core.*;"),
                "Trading core imports should be added");
        assertTrue(result.contains("import java.util.*;"),
                "Java util imports should be added");
    }

    @Test
    void testClassNameReplacement() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertTrue(result.contains("public class SimpleTestStrategy_Converted"),
                "Class name should be updated");
        assertFalse(result.contains("public class SimpleTestStrategy implements Strategy"),
                "Old interface should be removed");
    }

    @Test
    void testOnBarMethodReplaced() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertFalse(result.contains("void onBar(int index, BacktestEngine engine)"),
                "Old onBar signature should be removed");
        assertTrue(result.contains("void onBar(Bar bar)"),
                "New onBar signature should be present");
        assertTrue(result.contains("history.add(bar)"),
                "onBar should add bar to history");
    }

    @Test
    void testUnusedMethodsRemoved() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertFalse(result.contains("getBars()"), "getBars should be removed");
        assertFalse(result.contains("setBars("), "setBars should be removed");
        assertFalse(result.contains("getResult()"), "getResult should be removed");
    }

    @Test
    void testBoilerplateMethodsAdded() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertTrue(result.contains("public String name()"), "name() method should be added");
        assertTrue(result.contains("public List<Order> getPendingOrders()"), "getPendingOrders should be added");
        assertTrue(result.contains("public void reset()"), "reset() should be added");
        assertTrue(result.contains("onTick(double bid"), "onTick should be added");
    }

    @Test
    void testEngineCallsReplaced() {
        String result = JForexConverter.convertSource(JFOREX_SIMPLE, "SimpleTestStrategy", "SimpleTestStrategy_Converted");
        assertFalse(result.contains("engine.isInPosition()"), "isInPosition should be replaced");
        assertFalse(result.contains("engine.isPendingBuyStop()"), "isPendingBuyStop should be replaced");
        assertFalse(result.contains("engine.placeBuyStop("), "placeBuyStop should be replaced");
        assertTrue(result.contains("pendingOrders.add(order)"), "Order should be added to pending orders");
    }

    @Test
    void testConvertRealStrategy_31_177() throws IOException {
        String jforexPath = "/home/martinfou/projects/oanda-strategies/mvn-project/src/main/java/com/oanda/strategies/Strategy2_31_177.java";
        assumeTrue(Files.isRegularFile(Path.of(jforexPath)),
            "Skipping: external JForex fixture not present at " + jforexPath);
        Path tmpDir = Files.createTempDirectory("jforex-test-");
        String outputPath = JForexConverter.convert(jforexPath, tmpDir.toString(), "Strategy_2_31_177_Converted");

        assertTrue(Files.exists(Path.of(outputPath)), "Converted file should exist");

        String content = Files.readString(Path.of(outputPath));
        assertTrue(content.contains("package com.martinfou.trading.strategies.sqimported;"));
        assertTrue(content.contains("public class Strategy_2_31_177_Converted"));
        assertTrue(content.contains("void onBar(Bar bar)"));
        assertTrue(content.contains("getPendingOrders()"));
        assertTrue(content.contains("reset()"));

        // Cleanup
        Files.deleteIfExists(Path.of(outputPath));
    }

    @Test
    void testConvertAllStrategies(@TempDir Path tmpDir) throws IOException {
        String sourceDir = "/home/martinfou/projects/oanda-strategies/mvn-project/src/main/java/com/oanda/strategies";
        assumeTrue(Files.isDirectory(Path.of(sourceDir)),
            "Skipping: external JForex source dir not present at " + sourceDir);
        String outputDir = tmpDir.resolve("converted").toString();

        List<String> results = JForexConverter.convertAll(sourceDir, outputDir);

        assertFalse(results.isEmpty(), "Should convert at least one strategy");
        for (String path : results) {
            assertTrue(Files.exists(Path.of(path)), "Converted file should exist: " + path);
        }
    }

    @Test
    void testDetectClassName() {
        assertEquals("SimpleTestStrategy", JForexConverter.detectClassName(JFOREX_SIMPLE));
        assertEquals("Strategy2_31_177",
                JForexConverter.detectClassName("public class Strategy2_31_177 implements Strategy {"));
    }

    @Test
    void testExtractJavadoc() {
        String javadoc = JForexConverter.extractJavadoc(JFOREX_SIMPLE);
        assertTrue(javadoc.contains("Simple test strategy"), "Should extract javadoc");
    }

    @Test
    void testIndicatorPatternDetection() {
        // Test that indicator call patterns can be detected
        String testBody = """
                double sma20 = SMAIndicator.calculate(bars, 20, AppliedPrice.CLOSE, index - 2);
                double linReg = LinearRegressionIndicator.calculate(bars, 40, AppliedPrice.CLOSE, index - 3);
                double bigRange = BiggestRangeIndicator.calculate(bars, 25, index - 3);
               """;

        assertTrue(testBody.contains("SMAIndicator.calculate"));
        assertTrue(testBody.contains("BiggestRangeIndicator.calculate"));
        assertTrue(testBody.contains("LinearRegressionIndicator.calculate"));
    }

    @Test
    void testConvertPlaceBuyStop() {
        String testBody = """
                    if (longEntry && !engine.isInPosition() && !engine.isPendingBuyStop()) {
                        double sma = SMAIndicator.calculate(bars, 10, AppliedPrice.CLOSE, index - 3);
                        double bbrange = BBRangeIndicator.calculate(bars, 20, 2.0, AppliedPrice.CLOSE, index - 2);
                        double lowerBand = sma - (bbrange / 2.0);
                        double entryPrice = lowerBand + (1.0 * bbrange);
                        engine.placeBuyStop(entryPrice, 95, 290, 70, 100, 168);
                    }
                """;

        String result = JForexConverter.convertPlaceBuyStop(testBody);
        assertFalse(result.contains("engine.placeBuyStop("), "placeBuyStop should be converted");
        assertTrue(result.contains("pendingOrders.add(order)"), "Should create Order");
        assertTrue(result.contains("activeOrder = order"), "Should set activeOrder");
        assertTrue(result.contains("entryTriggered = true"), "Should set entryTriggered");
    }
}
