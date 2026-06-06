package com.martinfou.trading.intelligence.compile;

import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.plan.WeeklyPlanIO;
import com.martinfou.trading.intelligence.template.RiskBudgetEnvelope;
import com.martinfou.trading.intelligence.template.TemplateRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyCompileWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void runOnce_pendingFixtureMovesToCompiled() throws Exception {
        WeeklyBuilderPaths.ensureLayout(tempDir);
        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(new WeeklyPlan.Pick("T8", null, null, Map.of("reason", "test"), List.of(), "no trade")),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );
        Path pendingPlan = WeeklyPlanIO.planPath(WeeklyBuilderPaths.pending(tempDir), plan.weekId());
        WeeklyPlanIO.write(plan, pendingPlan);

        Path registrar = tempDir.resolve(
            "trading-strategies/src/main/java/com/martinfou/trading/strategies/llmweekly/LlmWeeklyStrategyCatalogRegistrar.java");
        Files.createDirectories(registrar.getParent());
        Files.writeString(registrar, """
            package com.martinfou.trading.strategies.llmweekly;
            public final class LlmWeeklyStrategyCatalogRegistrar {
                public static void registerAll() {
            // CODEGEN-BEGIN
            // CODEGEN-END
                }
            }
            """);

        CompileGate mockGate = new CompileGate(tempDir) {
            @Override
            public Result compile() {
                return new Result(true, "mock compile ok", null);
            }
        };

        WeeklyCompileWatcher watcher = new WeeklyCompileWatcher(
            tempDir,
            WeeklyStrategyCodeGenerator.loadDefault(),
            mockGate,
            Clock.fixed(Instant.parse("2026-06-06T18:00:00Z"), ZoneOffset.UTC)
        );

        WeeklyCompileWatcher.Result result = watcher.runOnce();
        assertEquals(WeeklyCompileWatcher.Result.Status.SUCCESS, result.status());
        Path compiledDir = WeeklyBuilderPaths.compiled(tempDir).resolve("2026-W24");
        assertTrue(Files.exists(compiledDir.resolve("manifest.json")));
        assertTrue(Files.exists(compiledDir.resolve(pendingPlan.getFileName())));
        assertTrue(Files.exists(tempDir.resolve("deploy/weekly-plans/2026-06-06.md")));
        try (var compiling = Files.list(WeeklyBuilderPaths.compiling(tempDir))) {
            assertTrue(compiling.findAny().isEmpty());
        }
    }

    @Test
    void runOnce_resumesStuckWeeklyPlanInCompiling() throws Exception {
        WeeklyBuilderPaths.ensureLayout(tempDir);
        WeeklyPlan plan = new WeeklyPlan(
            "2026-W24",
            List.of(new WeeklyPlan.Pick("T8", null, null, Map.of("reason", "test"), List.of(), "no trade")),
            ReviewerStatus.APPROVED,
            "brief.json",
            RiskBudgetEnvelope.defaults(TemplateRegistry.loadDefault().whitelistPairs())
        );
        Path compilingPlan = WeeklyPlanIO.planPath(WeeklyBuilderPaths.compiling(tempDir), plan.weekId());
        WeeklyPlanIO.write(plan, compilingPlan);

        Path registrar = tempDir.resolve(
            "trading-strategies/src/main/java/com/martinfou/trading/strategies/llmweekly/LlmWeeklyStrategyCatalogRegistrar.java");
        Files.createDirectories(registrar.getParent());
        Files.writeString(registrar, """
            package com.martinfou.trading.strategies.llmweekly;
            public final class LlmWeeklyStrategyCatalogRegistrar {
                public static void registerAll() {
            // CODEGEN-BEGIN
            // CODEGEN-END
                }
            }
            """);

        CompileGate mockGate = new CompileGate(tempDir) {
            @Override
            public Result compile() {
                return new Result(true, "mock compile ok", null);
            }
        };

        WeeklyCompileWatcher watcher = new WeeklyCompileWatcher(
            tempDir,
            WeeklyStrategyCodeGenerator.loadDefault(),
            mockGate,
            Clock.fixed(Instant.parse("2026-06-06T18:00:00Z"), ZoneOffset.UTC)
        );

        WeeklyCompileWatcher.Result result = watcher.runOnce();
        assertEquals(WeeklyCompileWatcher.Result.Status.SUCCESS, result.status());
        assertTrue(Files.exists(WeeklyBuilderPaths.compiled(tempDir).resolve("2026-W24/manifest.json")));
    }

    @Test
    void runOnce_idleWhenOnlyNonPlanFilesInCompiling() throws Exception {
        WeeklyBuilderPaths.ensureLayout(tempDir);
        Files.createFile(WeeklyBuilderPaths.compiling(tempDir).resolve("lock-plan.json"));

        WeeklyCompileWatcher watcher = new WeeklyCompileWatcher(
            tempDir,
            WeeklyStrategyCodeGenerator.loadDefault(),
            new CompileGate(tempDir),
            Clock.systemUTC()
        );

        assertEquals(WeeklyCompileWatcher.Result.Status.IDLE, watcher.runOnce().status());
    }
}
