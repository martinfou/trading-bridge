package com.martinfou.trading.intelligence.compile;

import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.template.TemplateRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Deterministic Java codegen for weekly template picks T1–T8 (Epic 22.4–22.5). */
public final class WeeklyStrategyCodeGenerator {

    public static final String GENERATED_PKG = "com.martinfou.trading.strategies.llmweekly.generated";
    public static final String REGISTRAR_CLASS = "com.martinfou.trading.strategies.llmweekly.LlmWeeklyStrategyCatalogRegistrar";

    private final TemplateRegistry registry;

    public WeeklyStrategyCodeGenerator(TemplateRegistry registry) {
        this.registry = registry;
    }

    public static WeeklyStrategyCodeGenerator loadDefault() throws IOException {
        return new WeeklyStrategyCodeGenerator(TemplateRegistry.loadDefault());
    }

    public GenerationResult generate(WeeklyPlan plan, Path generatedDir, Path registrarFile) throws IOException {
        Files.createDirectories(generatedDir);
        clearGeneratedDir(generatedDir);

        List<GeneratedStrategy> strategies = new ArrayList<>();
        for (WeeklyPlan.Pick pick : plan.picks()) {
            TemplateRegistry.TemplateEntry entry = registry.require(pick.templateId());
            GeneratedStrategy generated = generatePick(plan.weekId(), pick, entry);
            Path file = generatedDir.resolve(generated.className() + ".java");
            Files.writeString(file, generated.source(), StandardCharsets.UTF_8);
            strategies.add(generated);
        }

        rewriteRegistrar(registrarFile, strategies);
        return new GenerationResult(strategies);
    }

    private GeneratedStrategy generatePick(
        String weekId,
        WeeklyPlan.Pick pick,
        TemplateRegistry.TemplateEntry entry
    ) {
        String className = toClassName(weekId, pick.templateId(), pick.pair());
        String strategyId = toStrategyId(weekId, pick.templateId(), pick.pair());
        String source = switch (entry.codegenHandler()) {
            case NO_TRADE -> generateNoTrade(className, strategyId, pick);
            case DELEGATE_LORB -> generateDelegateLorb(className, strategyId, pick);
            case DELEGATE_GAP_FADE -> generateDelegateGapFade(className, strategyId, pick);
            case THIN_PROP -> generateThinProp(className, strategyId, pick, entry);
        };
        return new GeneratedStrategy(strategyId, className, pick.templateId(), pick.pair(), source);
    }

    private String generateNoTrade(String className, String strategyId, WeeklyPlan.Pick pick) {
        String reason = pick.params() == null ? "" : String.valueOf(pick.params().getOrDefault("reason", ""));
        return """
            package %s;

            import com.martinfou.trading.strategies.llmweekly.NoTradeWeekStrategy;

            /** LLM weekly T8 — %s */
            public final class %s extends NoTradeWeekStrategy {
                public %s() {
                    super("%s", "%s");
                }
            }
            """.formatted(
            GENERATED_PKG,
            escapeJava(reason),
            className,
            className,
            strategyId,
            escapeJava(pick.rationale() == null ? "" : pick.rationale())
        );
    }

    private String generateDelegateLorb(String className, String strategyId, WeeklyPlan.Pick pick) {
        return """
            package %s;

            import com.martinfou.trading.strategies.prop.LondonOpenRangeBreakoutStrategy;

            /** LLM weekly T4 delegate — sources: %s */
            public final class %s extends LondonOpenRangeBreakoutStrategy {
                public %s() {
                    super("%s");
                }

                @Override
                public String name() {
                    return "%s";
                }
            }
            """.formatted(
            GENERATED_PKG,
            joinSources(pick),
            className,
            className,
            pick.pair(),
            strategyId
        );
    }

    private String generateDelegateGapFade(String className, String strategyId, WeeklyPlan.Pick pick) {
        return """
            package %s;

            import com.martinfou.trading.strategies.prop.WeeklyOpenGapFadeStrategy;

            /** LLM weekly T5 delegate — sources: %s */
            public final class %s extends WeeklyOpenGapFadeStrategy {
                public %s() {
                    super("%s");
                }

                @Override
                public String name() {
                    return "%s";
                }
            }
            """.formatted(
            GENERATED_PKG,
            joinSources(pick),
            className,
            className,
            pick.pair(),
            strategyId
        );
    }

    private String generateThinProp(
        String className,
        String strategyId,
        WeeklyPlan.Pick pick,
        TemplateRegistry.TemplateEntry entry
    ) {
        String direction = pick.direction() == null ? "LONG" : pick.direction().toUpperCase(Locale.ROOT);
        String metadata = strategyId + "|" + pick.templateId() + "|" + joinSources(pick);
        Map<String, Object> params = pick.params() == null ? Map.of() : pick.params();
        String paramBlock = params.entrySet().stream()
            .map(e -> "        params.put(\"%s\", %s);".formatted(
                escapeJava(e.getKey()),
                toJavaLiteral(e.getValue())))
            .collect(Collectors.joining("\n"));

        return """
            package %s;

            import com.martinfou.trading.core.Bar;
            import com.martinfou.trading.core.indicators.Indicators;
            import com.martinfou.trading.strategies.prop.AbstractPropStrategy;
            import com.martinfou.trading.strategies.prop.PropSessions;

            import java.time.Instant;
            import java.util.LinkedHashMap;
            import java.util.Map;

            /** LLM weekly %s (%s) — %s */
            public final class %s extends AbstractPropStrategy {
                private final Map<String, Object> params = new LinkedHashMap<>();
                private final String metadata = "%s";
                private final String tradeDirection = "%s";

                public %s() {
                    super("%s", "%s");
            %s
                }

                @Override
                public String name() {
                    return metadata;
                }

                @Override
                protected void evaluate(Bar bar) {
                    if (history.size() < 50) {
                        return;
                    }
                    if (!PropSessions.inHourRange(bar, 7, 16)) {
                        return;
                    }
                    double ema20 = Indicators.emaLatest(history, 20);
                    double ema50 = Indicators.emaLatest(history, 50);
                    double atr = atr(14);
                    double pip = Indicators.pipSize(symbol);
                    if ("LONG".equals(tradeDirection) && bar.close() > ema20 && ema20 > ema50) {
                        double sl = bar.close() - Math.max(pip * 15, atr);
                        enterLong(bar, sl, rrTp(bar.close(), sl, Indicators.TradeSide.LONG));
                    } else if ("SHORT".equals(tradeDirection) && bar.close() < ema20 && ema20 < ema50) {
                        double sl = bar.close() + Math.max(pip * 15, atr);
                        enterShort(bar, sl, rrTp(bar.close(), sl, Indicators.TradeSide.SHORT));
                    }
                }
            }
            """.formatted(
            GENERATED_PKG,
            pick.templateId(),
            entry.name(),
            escapeJava(pick.rationale() == null ? "" : pick.rationale()),
            className,
            escapeJava(metadata),
            direction,
            className,
            strategyId,
            pick.pair(),
            paramBlock.isBlank() ? "" : paramBlock
        );
    }

    public void rewriteRegistrar(Path registrarFile, List<GeneratedStrategy> strategies) throws IOException {
        String markerBegin = "// CODEGEN-BEGIN";
        String markerEnd = "// CODEGEN-END";
        String body = strategies.stream()
            .map(s -> "        LlmWeeklyStrategyCatalog.register(\"%s\", sym -> new %s());"
                .formatted(s.strategyId(), GENERATED_PKG + "." + s.className()))
            .collect(Collectors.joining("\n"));

        String template;
        if (Files.exists(registrarFile)) {
            template = Files.readString(registrarFile);
        } else {
            template = """
                package com.martinfou.trading.strategies.llmweekly;

                import com.martinfou.trading.strategies.llmweekly.generated.*;

                public final class LlmWeeklyStrategyCatalogRegistrar {
                    private LlmWeeklyStrategyCatalogRegistrar() {}

                    public static void registerAll() {
                // CODEGEN-BEGIN
                // CODEGEN-END
                    }
                }
                """;
        }

        int begin = template.indexOf(markerBegin);
        int end = template.indexOf(markerEnd);
        if (begin < 0 || end < 0 || end <= begin) {
            throw new IOException("Registrar missing CODEGEN markers: " + registrarFile);
        }
        String updated = template.substring(0, begin + markerBegin.length())
            + "\n"
            + body
            + (body.isBlank() ? "" : "\n")
            + "        "
            + template.substring(end);
        Files.createDirectories(registrarFile.getParent());
        Files.writeString(registrarFile, updated, StandardCharsets.UTF_8);
    }

    public static String toStrategyId(String weekId, String templateId, String pair) {
        String pairPart = pair == null || pair.isBlank() ? "NONE" : pair;
        return "LLM_WEEKLY_" + weekId + "_" + templateId + "_" + pairPart;
    }

    public static String toClassName(String weekId, String templateId, String pair) {
        String pairPart = pair == null ? "None" : pair.replace("_", "");
        return "Weekly" + weekId.replace("-", "") + templateId + pairPart;
    }

    private static void clearGeneratedDir(Path generatedDir) throws IOException {
        if (!Files.isDirectory(generatedDir)) {
            return;
        }
        try (var stream = Files.list(generatedDir)) {
            for (Path file : stream.toList()) {
                if (file.getFileName().toString().endsWith(".java")) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static String joinSources(WeeklyPlan.Pick pick) {
        if (pick.sources() == null || pick.sources().isEmpty()) {
            return "";
        }
        return String.join(",", pick.sources());
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJavaLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escapeJava(String.valueOf(value)) + "\"";
    }

    public record GeneratedStrategy(
        String strategyId,
        String className,
        String templateId,
        String pair,
        String source
    ) {}

    public record GenerationResult(List<GeneratedStrategy> strategies) {}
}
