package com.martinfou.trading.tui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses /backtest data arguments to match {@code RunBacktest} CLI conventions. */
final class BacktestRequestParser {

    private static final String DEFAULT_SYMBOL = "EUR_USD";
    private static final int DEFAULT_SAMPLE_BARS = 500;

    private BacktestRequestParser() {}

    record ParsedBacktest(
        String symbol,
        Map<String, Object> barsSource,
        String dataLabel,
        Double capital,
        Double lotSize
    ) {}

    static ParsedBacktest parse(List<String> parts) {
        if (parts.size() < 2) {
            throw new IllegalArgumentException("strategyId is required");
        }
        List<String> dataParts = new ArrayList<>(parts.subList(2, parts.size()));
        Double capital = null;
        Double lotSize = null;
        for (int i = 0; i < dataParts.size(); ) {
            String token = dataParts.get(i);
            if ("--capital".equals(token) && i + 1 < dataParts.size()) {
                capital = TuiPrompts.parsePositiveDouble(dataParts.get(i + 1), "capital");
                dataParts.remove(i + 1);
                dataParts.remove(i);
                continue;
            }
            if ("--lots".equals(token) && i + 1 < dataParts.size()) {
                lotSize = TuiPrompts.parsePositiveDouble(dataParts.get(i + 1), "lot size");
                dataParts.remove(i + 1);
                dataParts.remove(i);
                continue;
            }
            i++;
        }

        List<String> rebuilt = new ArrayList<>();
        rebuilt.add("backtest");
        rebuilt.add(parts.get(1));
        rebuilt.addAll(dataParts);

        ParsedBacktest core = parseDataOnly(rebuilt);
        return new ParsedBacktest(core.symbol(), core.barsSource(), core.dataLabel(), capital, lotSize);
    }

    private static ParsedBacktest parseDataOnly(List<String> parts) {
        if (parts.size() == 2) {
            return coreSample(DEFAULT_SYMBOL, DEFAULT_SAMPLE_BARS);
        }

        String token = parts.get(2);
        if ("--sample".equals(token)) {
            int bars = parts.size() >= 4 ? parseInt(parts.get(3), "bars") : DEFAULT_SAMPLE_BARS;
            return coreSample(DEFAULT_SYMBOL, bars);
        }
        if ("--ci".equals(token)) {
            return coreCi(DEFAULT_SYMBOL);
        }
        if (isFilePath(token)) {
            return coreFile(token, DEFAULT_SYMBOL);
        }
        if (parts.size() >= 4) {
            String symbol = token;
            String yearOrBars = parts.get(3);
            if (looksLikeYearSpec(yearOrBars)) {
                return coreYear(symbol, yearOrBars);
            }
            if (yearOrBars.matches("\\d+")) {
                return coreSample(symbol, parseInt(yearOrBars, "bars"));
            }
            throw new IllegalArgumentException(
                "Expected year (e.g. 2012 or 2006-2012), bar count, --sample, --ci, or file path");
        }
        if (looksLikeSymbol(token)) {
            return coreSample(token, DEFAULT_SAMPLE_BARS);
        }
        throw new IllegalArgumentException(
            "Unrecognized data argument: " + token + " (try SYMBOL YEAR, --sample, --ci, or a file path)");
    }

    private static ParsedBacktest coreSample(String symbol, int bars) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "sample");
        source.put("count", bars);
        return new ParsedBacktest(symbol, source, "sample " + bars + " bars", null, null);
    }

    private static ParsedBacktest coreCi(String symbol) {
        Map<String, Object> source = Map.of("type", "ci");
        return new ParsedBacktest(symbol, source, "ci subset", null, null);
    }

    private static ParsedBacktest coreYear(String symbol, String yearSpec) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "year");
        source.put("year", yearSpec);
        return new ParsedBacktest(symbol, source, symbol + " " + yearSpec, null, null);
    }

    private static ParsedBacktest coreFile(String path, String symbol) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "file");
        source.put("path", path);
        return new ParsedBacktest(symbol, source, path, null, null);
    }

    private static boolean looksLikeYearSpec(String arg) {
        if (arg.matches("\\d{4}-\\d{4}")) {
            return true;
        }
        if (arg.matches("\\d{4}")) {
            int year = Integer.parseInt(arg);
            return year >= 1990 && year <= 2035;
        }
        return false;
    }

    private static boolean looksLikeSymbol(String arg) {
        return arg.contains("_") || arg.matches("[A-Za-z]{6}");
    }

    private static boolean isFilePath(String arg) {
        if (arg.endsWith(".bars") || arg.endsWith(".csv")) {
            return true;
        }
        return Files.exists(Path.of(arg));
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }
}
