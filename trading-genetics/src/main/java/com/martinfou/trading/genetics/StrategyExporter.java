package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Exports a genetic-algorithm {@link Chromosome} into a standalone, compilable
 * Java trading strategy class, with optional compilation and backtesting.
 *
 * <p>The generated source code is identical to what {@link StrategyCodeGen}
 * produces. The file is written into the {@code trading-strategies} module
 * under {@code com.martinfou.trading.strategies.generated}, so it becomes
 * part of the project and can be compiled with {@code mvn compile}.</p>
 *
 * <p>Two main workflows are supported:
 * <ol>
 *   <li><b>Export only</b> — write the Java source file without compiling.</li>
 *   <li><b>Export, compile, and backtest</b> — write the file, compile it in
 *       an isolated temp directory using the system {@link JavaCompiler},
 *       instantiate the resulting class via reflection, and execute a
 *       {@link BacktestEngine} run against supplied historical bars.</li>
 * </ol>
 *
 * <p>Usage from the command line (via the project's Maven classpath):</p>
 * <pre>{@code
 *   # Export only
 *   java -cp ... com.martinfou.trading.genetics.StrategyExporter --type trend --name MyStrategy
 *
 *   # Export + compile + backtest
 *   java -cp ... com.martinfou.trading.genetics.StrategyExporter --type breakout --name BreakoutV1 --backtest
 * }</pre>
 */
public final class StrategyExporter {

    /** Target package for generated strategy classes. */
    static final String GENERATED_PACKAGE = "com.martinfou.trading.strategies.generated";

    /** Relative path from the project root to the generated strategies directory. */
    private static final String GENERATED_DIR_RELATIVE =
        "trading-strategies/src/main/java/" + GENERATED_PACKAGE.replace('.', '/');

    private final StrategyCodeGen codeGen = new StrategyCodeGen();

    // ---------------------------------------------------------------
    //  ExportResult record
    // ---------------------------------------------------------------

    /**
     * The result of a strategy export, optionally including compilation and
     * backtest outcome.
     *
     * @param className     the strategy class name
     * @param sourceCode    the full generated Java source
     * @param filePath      absolute path where the source file was written
     * @param compiled      whether compilation succeeded ({@code false} when
     *                      only {@link #export(Chromosome, String)} was called)
     * @param backtestResult the backtest result, or {@code null} if not run
     */
    public record ExportResult(
            String className,
            String sourceCode,
            String filePath,
            boolean compiled,
            BacktestResult backtestResult
    ) {

        /**
         * Prints a human-readable summary of the export result to stdout.
         */
        public void printSummary() {
            System.out.println("\n═══════════════════════════════════════════");
            System.out.println("  STRATEGY EXPORT: " + className);
            System.out.println("═══════════════════════════════════════════");
            System.out.println("  Source file:  " + filePath);
            System.out.println("  Lines:        " + sourceCode.lines().count());
            System.out.println("  Compiled:     " + (compiled ? "YES" : "NO"));

            if (backtestResult != null) {
                backtestResult.printSummary();
            } else if (compiled) {
                System.out.println("  Backtest:     Not run");
            }

            System.out.println("═══════════════════════════════════════════\n");
        }
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Exports a chromosome as a Java source file into the
     * {@code trading-strategies} module.
     *
     * <p>The file path is resolved relative to the project root, which is
     * determined from the {@code user.dir} system property. If the current
     * working directory is the {@code trading-genetics} sub-module, the
     * parent directory is used as the project root.</p>
     *
     * @param chromosome the strategy DNA (must not be null)
     * @param className  the desired class name (must not be blank)
     * @return the export result with {@code compiled=false} and
     *         {@code backtestResult=null}
     * @throws NullPointerException     if chromosome or className is null
     * @throws IllegalArgumentException if className is blank
     * @throws RuntimeException         if the file cannot be written
     */
    public ExportResult export(Chromosome chromosome, String className) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        Objects.requireNonNull(className, "className must not be null");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }

        String sourceCode = codeGen.generate(chromosome, className);
        Path outputFile = resolveGeneratedFile(className);

        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, sourceCode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write generated strategy to " + outputFile, e);
        }

        return new ExportResult(className, sourceCode, outputFile.toAbsolutePath().toString(), false, null);
    }

    /**
     * Exports, compiles, and backtests a strategy in one call.
     *
     * <p>The source file is written to the project's {@code trading-strategies}
     * module. Compilation is performed with the system
     * {@link JavaCompiler} (JDK required) into an isolated temp directory.
     * If compilation succeeds, the class is loaded via a
     * {@link URLClassLoader} and executed through {@link BacktestEngine}.</p>
     *
     * @param chromosome     the strategy DNA
     * @param className      the desired class name
     * @param bars           historical bar data for the backtest
     * @param initialCapital starting account balance
     * @return the export result with compilation and backtest outcomes
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if className is blank or bars empty
     * @throws RuntimeException         if compilation setup fails
     */
    public ExportResult exportAndTest(
            Chromosome chromosome,
            String className,
            List<Bar> bars,
            double initialCapital) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(bars, "bars must not be null");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be empty");
        }
        if (initialCapital <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }

        // 1. Export the source file into the project
        String sourceCode = codeGen.generate(chromosome, className);
        Path outputFile = resolveGeneratedFile(className);
        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, sourceCode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write generated strategy to " + outputFile, e);
        }

        // 2. Compile in an isolated temp directory
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No system Java compiler available. Run on a JDK, not a JRE.");
        }

        Path tempDir;
        Path classesDir;
        try {
            tempDir = Files.createTempDirectory("strategy-export-");
            classesDir = tempDir.resolve("classes");
            Files.createDirectories(classesDir);

            Path pkgDir = tempDir.resolve(GENERATED_PACKAGE.replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path tempSourceFile = pkgDir.resolve(className + ".java");
            Files.writeString(tempSourceFile, sourceCode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up compilation environment", e);
        }

        String classpath = buildClasspath();

        List<String> compileOptions = List.of(
            "-d", classesDir.toAbsolutePath().toString(),
            "--release", "21",
            "-cp", classpath,
            tempDir.resolve(GENERATED_PACKAGE.replace('.', '/'))
                .resolve(className + ".java").toAbsolutePath().toString()
        );

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, null, errStream,
            compileOptions.toArray(String[]::new));
        boolean compiled = exitCode == 0;
        String compilerErrors = errStream.toString();

        if (!compiled) {
            System.err.println("Compilation failed for " + className);
            System.err.println(compilerErrors);
            return new ExportResult(className, sourceCode,
                outputFile.toAbsolutePath().toString(), false, null);
        }

        // 3. Load the compiled class and run the backtest
        BacktestResult backtestResult;
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                StrategyExporter.class.getClassLoader())) {

            Class<?> loadedClass = loader.loadClass(GENERATED_PACKAGE + "." + className);
            Strategy strategy = (Strategy) loadedClass.getConstructor(String.class)
                .newInstance("EXPORTED");

            BacktestEngine engine = new BacktestEngine(strategy, bars, initialCapital);
            backtestResult = engine.run();

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to backtest generated strategy '" + className + "'", e);
        }

        return new ExportResult(className, sourceCode,
            outputFile.toAbsolutePath().toString(), true, backtestResult);
    }

    // ---------------------------------------------------------------
    //  CLI entry point
    // ---------------------------------------------------------------

    /**
     * Command-line entry point for use from scripts.
     *
     * <p>Accepted flags:</p>
     * <ul>
     *   <li>{@code --type trend|meanrev|breakout|momentum} (default: trend)</li>
     *   <li>{@code --name StrategyName} (default: GeneratedStrategy)</li>
     *   <li>{@code --backtest} — also compile and backtest with synthetic data</li>
     *   <li>{@code --bars N} — number of synthetic bars to generate (default: 250)</li>
     *   <li>{@code --capital N} — initial capital (default: 100000)</li>
     * </ul>
     */
    public static void main(String[] args) {
        // Parse CLI args
        String typeStr = "trend";
        String name = "GeneratedStrategy";
        boolean runBacktest = false;
        int barCount = 250;
        double capital = 100_000.0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--type" -> typeStr = (++i < args.length) ? args[i] : typeStr;
                case "--name" -> name = (++i < args.length) ? args[i] : name;
                case "--backtest" -> runBacktest = true;
                case "--bars" -> barCount = (++i < args.length) ? Integer.parseInt(args[i]) : barCount;
                case "--capital" -> capital = (++i < args.length) ? Double.parseDouble(args[i]) : capital;
            }
        }

        // Map type string to StrategyType
        StrategyBuilder.StrategyType type = switch (typeStr.toLowerCase()) {
            case "trend", "trend_following" -> StrategyBuilder.StrategyType.TREND_FOLLOWING;
            case "meanrev", "mean_reversion" -> StrategyBuilder.StrategyType.MEAN_REVERSION;
            case "breakout" -> StrategyBuilder.StrategyType.BREAKOUT;
            case "momentum" -> StrategyBuilder.StrategyType.MOMENTUM;
            default -> {
                System.err.println("Unknown strategy type: " + typeStr
                    + ". Valid: trend, meanrev, breakout, momentum. Using 'trend'.");
                yield StrategyBuilder.StrategyType.TREND_FOLLOWING;
            }
        };

        // Build chromosome using StrategyBuilder defaults
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(type);
        Chromosome chromosome = StrategyBuilder.buildChromosome(config);

        System.out.println();
        System.out.println(StrategyBuilder.describeConfig(config));
        System.out.println("Chromosome: " + chromosome);

        // Create exporter and run
        StrategyExporter exporter = new StrategyExporter();

        if (runBacktest) {
            // Generate synthetic bars for backtest
            List<Bar> bars = generateSyntheticBars(barCount, 1.1000);
            ExportResult result = exporter.exportAndTest(chromosome, name, bars, capital);
            result.printSummary();
        } else {
            ExportResult result = exporter.export(chromosome, name);
            result.printSummary();
        }
    }

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

    /**
     * Resolves the path where the generated strategy source file should be
     * written, relative to the project root.
     */
    static Path resolveGeneratedFile(String className) {
        Path projectRoot = detectProjectRoot();
        return projectRoot.resolve(GENERATED_DIR_RELATIVE).resolve(className + ".java");
    }

    /**
     * Detects the multi-module project root directory.
     *
     * <p>Heuristic: if the current working directory ends with
     * {@code trading-genetics}, use its parent. Otherwise use
     * {@code user.dir} directly. Override with system property
     * {@code strategy.exporter.project.root}.</p>
     */
    static Path detectProjectRoot() {
        String override = System.getProperty("strategy.exporter.project.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        // When running Maven tests, cwd is the project root.
        // If a pom.xml is present, we are at the root.
        if (Files.exists(cwd.resolve("pom.xml"))
            && Files.exists(cwd.resolve("trading-core"))
            && Files.exists(cwd.resolve("trading-genetics"))) {
            return cwd;
        }

        // When running from within trading-genetics sub-module
        if (cwd.endsWith("trading-genetics")) {
            Path parent = cwd.getParent();
            if (parent != null) return parent;
        }

        return cwd;
    }

    /**
     * Builds the classpath string needed for compiling generated strategies
     * against project classes.
     */
    static String buildClasspath() {
        Path projectRoot = detectProjectRoot();

        // Collect all target/classes directories from project modules
        String[] modules = {"trading-core", "trading-genetics",
            "trading-backtest", "trading-strategies"};

        StringBuilder cp = new StringBuilder();

        // First, include the JVM's own classpath (contains dependency JARs)
        String sysCp = System.getProperty("java.class.path", "");
        if (!sysCp.isBlank()) {
            cp.append(sysCp);
        }

        // Then add target/classes from each project module
        for (String module : modules) {
            Path classesPath = projectRoot.resolve(module).resolve("target").resolve("classes");
            if (Files.exists(classesPath)) {
                if (!cp.isEmpty()) cp.append(File.pathSeparatorChar);
                cp.append(classesPath.toAbsolutePath().toString());
            }
        }

        // Fallback: try target/classes of trading-genetics itself
        if (cp.isEmpty()) {
            cp.append("target/classes");
        }

        return cp.toString();
    }

    /**
     * Generates synthetic bar data with a mild upward trend for CLI backtests.
     */
    static List<Bar> generateSyntheticBars(int count, double startPrice) {
        var rng = ThreadLocalRandom.current();
        List<Bar> bars = new ArrayList<>(count);
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            double change = (rng.nextDouble() - 0.45) * 0.015;
            price = price * (1 + change);
            double open = price;
            double close = price * (1 + (rng.nextDouble() - 0.5) * 0.008);
            double high = Math.max(open, close) * (1 + rng.nextDouble() * 0.004);
            double low = Math.min(open, close) * (1 - rng.nextDouble() * 0.004);
            long volume = rng.nextLong(100, 10000);
            bars.add(new Bar("EURUSD", Instant.ofEpochSecond(86400L * i),
                open, high, low, close, volume));
        }
        return bars;
    }
}
