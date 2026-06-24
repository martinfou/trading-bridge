package com.martinfou.trading.intelligence.compile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs {@code mvn compile -pl trading-strategies -am} as compile gate (Epic 22.4). */
public class CompileGate {

    private static final Logger log = LoggerFactory.getLogger(CompileGate.class);

    private final Path repoRoot;
    private final Duration timeout;

    public CompileGate(Path repoRoot) {
        this(repoRoot, Duration.ofMinutes(10));
    }

    CompileGate(Path repoRoot, Duration timeout) {
        this.repoRoot = repoRoot;
        this.timeout = timeout;
    }

    public Result compile() throws IOException, InterruptedException {
        String mvn = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        List<String> command = List.of(mvn, "compile", "-pl", "trading-strategies", "-am", "-q");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(true);

        log.info("Running compile gate: {}", String.join(" ", command));
        Process process = pb.start();

        StringBuilder outputBuilder = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (outputBuilder) {
                        outputBuilder.append(line).append("\n");
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            try {
                outputThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output;
            synchronized (outputBuilder) {
                output = outputBuilder.toString();
            }
            return new Result(false, output, "Compile gate timed out after " + timeout);
        }

        try {
            outputThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output;
        synchronized (outputBuilder) {
            output = outputBuilder.toString();
        }

        int exit = process.exitValue();
        if (exit == 0) {
            return new Result(true, output, null);
        }
        return new Result(false, output, excerpt(output, 2000));
    }

    private static String excerpt(String text, int max) {
        if (text == null || text.isBlank()) {
            return "Maven compile failed (no output)";
        }
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    public record Result(boolean success, String output, String errorSnippet) {}
}
