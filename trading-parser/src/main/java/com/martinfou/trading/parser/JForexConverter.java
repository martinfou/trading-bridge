package com.martinfou.trading.parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Converts JForex/Oanda StrategyQuant strategies to Trading Bridge format.
 *
 * <p>Reads a JForex strategy (com.oanda.strategies.*, implementing
 * com.oanda.backtest.Strategy with onBar(int, BacktestEngine)) and generates
 * a Trading Bridge strategy (com.martinfou.trading.core.Strategy with
 * onBar(Bar bar) + getPendingOrders()).</p>
 *
 * <p>Handled indicator conversions:</p>
 * <ul>
 *   <li>SMAIndicator → inline SMA helper</li>
 *   <li>BBRangeIndicator → inline Bollinger Band width</li>
 *   <li>LinearRegressionIndicator → inline LinReg helper</li>
 *   <li>BiggestRangeIndicator → inline Highest-Low helper</li>
 *   <li>VortexIndicator → inline Vortex helper</li>
 *   <li>LWMAIndicator → inline LWMA helper</li>
 *   <li>ATRIndicator → inline ATR helper</li>
 * </ul>
 */
public class JForexConverter {

    private static final String TARGET_PACKAGE = "com.martinfou.trading.strategies.sqimported";
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^package\\s+com\\.oanda\\.strategies\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^import\\s+com\\.oanda\\.(backtest|core)\\.\\*?[^;]*;\\s*", Pattern.MULTILINE);
    private static final Pattern CLASS_DECL_PATTERN =
            Pattern.compile("(public\\s+class\\s+)(\\w+)(.*implements\\s+Strategy)");
    private static final Pattern ONBAR_SIG_PATTERN =
            Pattern.compile("@Override\\s*\\n\\s*public\\s+void\\s+onBar\\(int\\s+index,\\s*BacktestEngine\\s+engine\\)");
    private static final Pattern PLACE_BUYSTOP_PATTERN =
            Pattern.compile("engine\\.placeBuyStop\\((\\d+(?:\\.\\d+)?[^)]*)\\)");
    private static final Pattern IS_IN_POSITION_PATTERN =
            Pattern.compile("engine\\.isInPosition\\(\\)");
    private static final Pattern IS_PENDING_BUYSTOP_PATTERN =
            Pattern.compile("engine\\.isPendingBuyStop\\(\\)");
    private static final Pattern BARS_GET_PATTERN =
            Pattern.compile("bars\\.get\\(index\\s*[-+]\\s*(\\d+)\\)");
    private static final Pattern BARS_GET_INLINE_PATTERN =
            Pattern.compile("bars\\.get\\(([^)]+)\\)");
    private static final Pattern BARS_FIELD_PATTERN =
            Pattern.compile("private\\s+List<Bar>\\s+bars\\s*;");
    private static final Pattern BARS_FIELD_FULL_PATTERN =
            Pattern.compile("private\\s+List<Bar>\\s+bars;\\s*\\n\\s*private\\s+final\\s+List<Bar>\\s+executedTrades\\s*=\\s*new\\s+ArrayList<>\\(\\)\\s*;?");
    private static final Pattern GET_BARS_PATTERN =
            Pattern.compile("@Override\\s*\\n\\s*public\\s+List<Bar>\\s+getBars\\(\\)\\s*\\{\\s*return\\s+bars;\\s*\\}");
    private static final Pattern SET_BARS_PATTERN =
            Pattern.compile("@Override\\s*\\n\\s*public\\s+void\\s+setBars\\(List<Bar>\\s+bars\\)\\s*\\{\\s*this\\.bars\\s*=\\s*bars;\\s*\\}");
    private static final Pattern GET_RESULT_PATTERN =
            Pattern.compile("@Override\\s*\\n\\s*public\\s+BacktestResult\\s+getResult\\(\\)\\s*\\{\\s*return\\s+result;\\s*\\}");
    private static final Pattern RESULT_FIELD_PATTERN =
            Pattern.compile("private\\s+BacktestResult\\s+result\\s*;");
    private static final Pattern NAN_CHECK_PATTERN =
            Pattern.compile("Double\\.isNaN\\((\\w+)\\)");
    private static final Pattern NAME_FIELD_PATTERN =
            Pattern.compile("private\\s+final\\s+String\\s+name\\s*=\\s*\"[^\"]*\"\\s*;", Pattern.MULTILINE);
    private static final Pattern GET_NAME_METHOD_PATTERN =
            Pattern.compile("@Override\\s*\\n\\s*public\\s+String\\s+getName\\(\\)\\s*\\{\\s*return\\s+name;\\s*\\}", Pattern.MULTILINE);

    // Indicator call patterns
    private static final Pattern SMA_CALL =
            Pattern.compile("SMAIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*(\\w+)\\.(\\w+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern BBRANGE_CALL =
            Pattern.compile("BBRangeIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*([\\d.]+),\\s*(\\w+)\\.(\\w+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern LINREG_CALL =
            Pattern.compile("LinearRegressionIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*(\\w+)\\.(\\w+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern BIGGEST_RANGE_CALL =
            Pattern.compile("BiggestRangeIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern VORTEX_CALL =
            Pattern.compile("VortexIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern LWMA_CALL =
            Pattern.compile("LWMAIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*(\\w+)\\.(\\w+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern ATR_CALL =
            Pattern.compile("ATRIndicator\\.calculate\\(bars,\\s*(\\d+),\\s*index\\s*(-|\\+)\\s*(\\d+)\\)");
    private static final Pattern APPLIED_PRICE_GET =
            Pattern.compile("AppliedPrice\\.(\\w+)\\.getPrice\\(bars\\.get\\(index\\s*(-|\\+)\\s*(\\d+)\\)\\)");
    private static final Pattern BAR_HIGH_DIRECT =
            Pattern.compile("bars\\.get\\(index\\s*(-|\\+)\\s*(\\d+)\\)\\.(high|low|open|close)\\(\\)");
    private static final Pattern MEDIAN_PRICE_DIRECT =
            Pattern.compile("bars\\.get\\(index\\s*(-|\\+)\\s*(\\d+)\\)\\.getMedianPrice\\(\\)");

    // Map from JForex strategy class name to target output class name
    private static final Map<String, String> NAME_MAP = new LinkedHashMap<>();
    static {
        NAME_MAP.put("Strategy2_31_177", "Strategy_2_31_177_Converted");
        NAME_MAP.put("Strategy2_32_120", "Strategy_2_32_120_Converted");
        NAME_MAP.put("Strategy2_38_112", "Strategy_2_38_112_Converted");
        NAME_MAP.put("Strategy2_36_190", "Strategy_2_36_190_Converted");
        NAME_MAP.put("Strategy2_31_175", "Strategy_2_31_175_Converted");
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Converts a single JForex strategy source file and saves the result.
     *
     * @param sourcePath path to the JForex .java file
     * @param outputDir  directory for the converted output file
     * @param className  desired class name for the output (or null for auto-detect)
     * @return path to the generated file
     * @throws IOException if reading/writing fails
     */
    public static String convert(String sourcePath, String outputDir, String className) throws IOException {
        Path src = Paths.get(sourcePath);
        String source = Files.readString(src);

        // Extract original class name
        String originalName = detectClassName(source);
        if (originalName == null) {
            throw new IllegalArgumentException("Cannot detect class name in: " + sourcePath);
        }

        // Determine output class name
        String targetName = (className != null && !className.isEmpty())
                ? className
                : NAME_MAP.getOrDefault(originalName, originalName + "_Converted");

        // Perform conversion
        String result = convertSource(source, originalName, targetName);

        // Write output
        Path outputPath = Paths.get(outputDir, targetName + ".java");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, result);

        return outputPath.toAbsolutePath().toString();
    }

    /**
     * Converts all JForex strategies from a directory.
     *
     * @param sourceDir directory containing JForex strategy .java files
     * @param outputDir target directory for converted files
     * @return list of generated file paths
     */
    public static List<String> convertAll(String sourceDir, String outputDir) throws IOException {
        List<String> results = new ArrayList<>();
        Path dir = Paths.get(sourceDir);

        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + sourceDir);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.java")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                // Only convert strategies (not adapters/models)
                if (fileName.startsWith("Strategy")) {
                    // Determine output name
                    String baseName = fileName.replace(".java", "");
                    String outName = NAME_MAP.getOrDefault(baseName, baseName + "_Converted");
                    try {
                        String outPath = convert(entry.toString(), outputDir, outName);
                        results.add(outPath);
                        System.out.println("  ✓ " + fileName + " → " + outName + ".java");
                    } catch (Exception e) {
                        System.err.println("  ✗ " + fileName + " FAILED: " + e.getMessage());
                    }
                }
            }
        }

        return results;
    }

    /**
     * Attempts to compile a file with javac (basic syntax check).
     *
     * @param filePath   path to the .java file
     * @param classPaths colon-separated list of classpath entries
     * @return true if compilation succeeded
     */
    public static boolean compileTest(String filePath, String classPaths) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "javac", "-d", System.getProperty("java.io.tmpdir"),
                    "-cp", classPaths, filePath);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                String output = new String(p.getInputStream().readAllBytes());
                System.err.println("Compilation failed:\n" + output);
            }
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("Compile test error: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------
    //  Core conversion logic
    // ---------------------------------------------------------------

    static String convertSource(String source, String originalName, String targetName) {
        // 1. Extract javadoc
        String javadoc = extractJavadoc(source);
        String body = source;

        // 2. Replace package
        body = PACKAGE_PATTERN.matcher(body).replaceFirst("package " + TARGET_PACKAGE + ";");

        // 3. Remove Oanda imports
        body = IMPORT_PATTERN.matcher(body).replaceAll("");

        // 4. Add new imports (after package line)
        body = addImports(body);

        // 5. Replace class name
        body = body.replace(originalName, targetName);

        // 6. Replace fields
        body = replaceFields(body);

        // 7. Convert onBar signature + body
        body = convertOnBar(body);

        // 8. Remove unnecessary methods (get/set bars, getResult)
        body = removeUnusedMethods(body);

        // 9. Add boilerplate methods
        JavaClassInfo info = new JavaClassInfo();
        info.targetName = targetName;
        info.javadoc = javadoc;
        body = addBoilerplate(body, info);

        // 10. Clean up
        // Remove duplicate @Override annotations
        body = body.replaceAll("(?m)^\\s*@Override\\s*$\\n(?=\\s*@Override)", "");
        // Remove duplicate blank lines
        body = body.replaceAll("\n\\s*\n\\s*\n", "\n\n");
        body = body.replaceAll("\n\\s*\n\\s*\n", "\n\n");
        // Remove duplicate import statements
        // Remove redundant individual java.util imports when wildcard import exists
        body = body.replaceAll("(?m)^import\\s+java\\.util\\.(ArrayList|List|Map|HashMap|Set|HashSet|Arrays|Collections);\\s*\\n", "");
        // Remove trailing && true and similar
        body = body.replaceAll("\\s*&&\\s*true\\b", "");
        body = body.replaceAll("\\s*\\|\\|\\s*false\\b", "");

        return body;
    }

    static String extractJavadoc(String source) {
        // Extract the class javadoc comment
        Matcher m = Pattern.compile("/\\*\\*\\s*\\n(\\s*\\*.*?\\n)*\\s*\\*/\\s*\\n\\s*public\\s+class\\s+\\w+", Pattern.DOTALL)
                .matcher(source);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    static String detectClassName(String source) {
        Matcher m = Pattern.compile("public\\s+class\\s+(\\w+)").matcher(source);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    static String addImports(String source) {
        // Find the package line and add imports after it
        String imports = "\n" +
                "import com.martinfou.trading.core.*;\n" +
                "import java.util.*;\n";

        // Insert after the package line
        return source.replaceFirst(";" + "\\s*\\n",
                ";\n" + imports + "\n");
    }

    static String replaceFields(String source) {
        // Remove bars field
        source = BARS_FIELD_FULL_PATTERN.matcher(source).replaceFirst("");
        source = BARS_FIELD_PATTERN.matcher(source).replaceFirst("");
        // Remove result field
        source = RESULT_FIELD_PATTERN.matcher(source).replaceFirst("");
        return source;
    }

    static String removeUnusedMethods(String source) {
        source = GET_BARS_PATTERN.matcher(source).replaceFirst("");
        source = SET_BARS_PATTERN.matcher(source).replaceFirst("");
        source = GET_RESULT_PATTERN.matcher(source).replaceFirst("");
        source = GET_NAME_METHOD_PATTERN.matcher(source).replaceFirst("");
        source = NAME_FIELD_PATTERN.matcher(source).replaceFirst("");
        source = source.replaceAll("(?m)^\\s*@Override\\s*$\\n(?=\\s*@Override)", "");
        return source;
    }

    static String convertOnBar(String source) {
        // Find the onBar method and replace it
        String[] lines = source.split("\n", -1);

        // Phase 1: locate the onBar method start
        int onBarStartLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().contains("void onBar(int index, BacktestEngine engine)")) {
                onBarStartLine = i;
                break;
            }
        }
        if (onBarStartLine < 0) return source; // No onBar method found

        // Phase 2: find the END of the onBar method by counting braces
        int onBarEndLine = -1;
        int braceDepth = -1; // start at -1; first '{' brings it to 0
        boolean bodyStarted = false;
        StringBuilder onBarBody = new StringBuilder();

        for (int i = onBarStartLine; i < lines.length; i++) {
            String raw = lines[i];
            for (int ci = 0; ci < raw.length(); ci++) {
                char c = raw.charAt(ci);
                if (c == '{') {
                    if (!bodyStarted) {
                        bodyStarted = true;
                        braceDepth = 0;
                    } else {
                        braceDepth++;
                    }
                } else if (c == '}' && bodyStarted) {
                    if (braceDepth == 0) {
                        onBarEndLine = i;
                        break;
                    }
                    braceDepth--;
                }
            }
            if (onBarEndLine >= 0) break;
            // Collect body content (everything after the opening brace's line)
            if (bodyStarted && i > onBarStartLine && onBarEndLine < 0) {
                onBarBody.append(raw).append("\n");
            }
        }

        if (onBarEndLine < 0) return source; // Could not find end of onBar

        // Phase 3: split lines into before / after (exclude the old onBar method entirely)
        List<String> beforeOnBar = new ArrayList<>();
        List<String> afterOnBar = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i < onBarStartLine) {
                beforeOnBar.add(lines[i]);
            } else if (i > onBarEndLine) {
                afterOnBar.add(lines[i]);
            }
            // Lines in [onBarStartLine, onBarEndLine] are the old onBar method — skip entirely
        }

        // Phase 4: transform the old body into signal logic
        String newBody = transformOnBarBody(onBarBody.toString());

        // Phase 5: build the new onBar method
        String newOnBar = "    @Override\n" +
                "    public void onBar(Bar bar) {\n" +
                "        history.add(bar);\n" +
                "        if (history.size() < MIN_BARS) return;\n" +
                "\n" +
                "        // Manage active order / position\n" +
                "        if (activeOrder != null) {\n" +
                "            barsSinceEntry++;\n" +
                "            if (expirationBars > 0 && barsSinceEntry >= expirationBars) {\n" +
                "                pendingOrders.remove(activeOrder);\n" +
                "                activeOrder = null;\n" +
                "            }\n" +
                "            return;\n" +
                "        }\n" +
                "\n" +
                "        if (entryTriggered) return;\n" +
                "\n" +
                newBody +
                "    }\n";

        // Phase 6: reassemble
        StringBuilder result = new StringBuilder();
        for (String bl : beforeOnBar) {
            result.append(bl).append("\n");
        }
        result.append(newOnBar).append("\n");
        for (String al : afterOnBar) {
            result.append(al).append("\n");
        }

        String out = result.toString();
        // Remove duplicate @Override annotations
        out = out.replaceAll("(?m)^\\s*@Override\\s*$\\n(?=\\s*@Override)", "");
        // Clean up triple newlines
        out = out.replaceAll("\n{3,}", "\n\n");
        return out;
    }

    static String transformOnBarBody(String body) {
        if (body == null || body.isBlank()) return "        // No signals defined\n";

        // Remove the initial min-bar check
        body = body.replaceAll("(?m)^\\s*if\\s*\\(\\s*index\\s*<\\s*\\d+\\s*\\)\\s*return\\s*;\\s*", "");
        // Remove trailing semicolons after that
        body = body.replaceAll("(?m)needs?.*", "").strip();

        // Replace engine.placeBuyStop(...) with order creation
        body = convertPlaceBuyStop(body);

        // Replace engine.isInPosition() → (activeOrder != null)
        // But handle negation: !engine.isInPosition() → activeOrder == null
        body = body.replaceAll("!engine\\.isInPosition\\(\\)", "activeOrder == null");
        body = IS_IN_POSITION_PATTERN.matcher(body).replaceAll("(activeOrder != null)");

        // Replace engine.isPendingBuyStop() → (false) — we manage state via entryTriggered
        // Handle negation too: !engine.isPendingBuyStop() → true
        body = body.replaceAll("!engine\\.isPendingBuyStop\\(\\)", "true");
        body = IS_PENDING_BUYSTOP_PATTERN.matcher(body).replaceAll("(false)");
        // Clean up any remaining "&& !false" or "&& !(false)" or "&& !(activeOrder != null)"
        body = body.replaceAll("&&\\s*!\\(false\\)", "");
        body = body.replaceAll("\\|\\|\\s*!\\(false\\)", "");
        body = body.replaceAll("!\\(false\\)\\s*&&", "");
        body = body.replaceAll("!\\(false\\)", "true");
        body = body.replaceAll("\\(activeOrder != null\\)", "activeOrder != null").trim();

        // Convert AppliedPrice.XXX.getPrice(bars.get(index - N)) to bar access
        body = convertAppliedPriceGets(body);

        // Convert bars.get(index - N).method() to getBar(N).method()
        body = convertBarAccess(body);

        // Convert indicator calls
        body = convertIndicatorCalls(body);

        // Replace VortexIndicator.VortexResult vX = ... → split into vX_plus, vX_minus
        body = body.replaceAll(
            "VortexIndicator\\.VortexResult\\s+(\\w+)\\s*=\\s*calcVortexPlus\\((\\w+),\\s*(\\d+),\\s*(\\d+)\\)\\s*;",
            "double $1_plus = calcVortexPlus($2, $3, $4); double $1_minus = calcVortexMinus($2, $3, $4);");
        body = body.replaceAll("(\\w+)\\.vortexPlus\\(\\)", "$1_plus");
        body = body.replaceAll("(\\w+)\\.vortexMinus\\(\\)", "$1_minus");

        // Replace remaining bare 'bars' references with 'history' (safe in onBar body)
        body = body.replaceAll("(?<![\\.\\w])bars\\.get\\(", "history.get(");
        body = body.replaceAll("(?<![\\.\\w])bars\\.", "history.");
        // Replace remaining bare 'index' variable with history.size()-1
        body = body.replaceAll("\\bindex\\b(?!\\.)", "(history.size() - 1)");
        // Replace getMedianPrice() - the pattern is: history.get(VAR).getMedianPrice() → (history.get(VAR).high()+history.get(VAR).low())/2.0
        body = body.replaceAll("history\\.get\\((\\w+)\\)\\.getMedianPrice\\(\\)",
                "(history.get($1).high() + history.get($1).low()) / 2.0");
        // Replace getTypicalPrice() similarly
        body = body.replaceAll("history\\.get\\((\\w+)\\)\\.getTypicalPrice\\(\\)",
                "(history.get($1).high() + history.get($1).low() + history.get($1).close()) / 3.0");

        // Clean up empty lines
        body = body.replaceAll("\\n{3,}", "\n\n");

        // Indent properly (add 8 spaces to each line)
        StringBuilder indented = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.trim().isEmpty() || indented.length() > 0) {
                indented.append("        ").append(line).append("\n");
            }
        }

        return indented.toString().stripTrailing() + "\n";
    }

    static String convertAppliedPriceGets(String body) {
        // AppliedPrice.OPEN.getPrice(bars.get(index - 3)) → getBar(3).open()
        StringBuffer sb = new StringBuffer();
        Matcher m = Pattern.compile(
                "AppliedPrice\\.(OPEN|HIGH|LOW|CLOSE|MEDIAN|TYPICAL)\\.getPrice\\(bars\\.get\\(index\\s*-\\s*(\\d+)\\)\\)")
                .matcher(body);
        while (m.find()) {
            String priceType = m.group(1).toLowerCase();
            int shift = Integer.parseInt(m.group(2));
            String replacement;
            switch (priceType) {
                case "open": replacement = "getBar(" + shift + ").open()"; break;
                case "high": replacement = "getBar(" + shift + ").high()"; break;
                case "low": replacement = "getBar(" + shift + ").low()"; break;
                case "close": replacement = "getBar(" + shift + ").close()"; break;
                case "median": replacement = "getBar(" + shift + ").high() + getBar(" + shift + ").low()) / 2.0"; break;
                case "typical": replacement = "(getBar(" + shift + ").high() + getBar(" + shift + ").low() + getBar(" + shift + ").close()) / 3.0"; break;
                default: replacement = m.group(0); break;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String convertBarAccess(String body) {
        // bars.get(index - N).high() → getBar(N).high()
        StringBuffer sb = new StringBuffer();
        Matcher m = Pattern.compile(
                "bars\\.get\\(index\\s*-\\s*(\\d+)\\)\\.(high|low|open|close|getMedianPrice|getTypicalPrice)\\(\\)")
                .matcher(body);
        while (m.find()) {
            int shift = Integer.parseInt(m.group(1));
            String method = m.group(2);
            String replacement;
            if (method.equals("getMedianPrice")) {
                replacement = "(getBar(" + shift + ").high() + getBar(" + shift + ").low()) / 2.0";
            } else if (method.equals("getTypicalPrice")) {
                replacement = "(getBar(" + shift + ").high() + getBar(" + shift + ").low() + getBar(" + shift + ").close()) / 3.0";
            } else {
                replacement = "getBar(" + shift + ")." + method + "()";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String convertIndicatorCalls(String body) {
        // ATRIndicator call → inline helper
        body = convertBigPattern(body, ATR_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String op = m.group(2);
            int shift = Integer.parseInt(m.group(3));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcATR(history, " + period + ", " + actualShift + ")";
        });

        // SMAIndicator call → inline helper  
        body = convertBigPattern(body, SMA_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String priceType = m.group(3).toLowerCase();
            String op = m.group(4);
            int shift = Integer.parseInt(m.group(5));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcSMA(history, " + period + ", \"" + priceType + "\", " + actualShift + ")";
        });

        // BBRangeIndicator call → inline helper
        body = convertBigPattern(body, BBRANGE_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            double mult = Double.parseDouble(m.group(2));
            String priceType = m.group(4).toLowerCase();
            String op = m.group(5);
            int shift = Integer.parseInt(m.group(6));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcBBRange(history, " + period + ", " + mult + ", \"" + priceType + "\", " + actualShift + ")";
        });

        // LinearRegressionIndicator call → inline helper
        body = convertBigPattern(body, LINREG_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String priceType = m.group(3).toLowerCase();
            String op = m.group(4);
            int shift = Integer.parseInt(m.group(5));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcLinReg(history, " + period + ", \"" + priceType + "\", " + actualShift + ")";
        });

        // BiggestRangeIndicator call → inline helper
        body = convertBigPattern(body, BIGGEST_RANGE_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String op = m.group(2);
            int shift = Integer.parseInt(m.group(3));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcBiggestRange(history, " + period + ", " + actualShift + ")";
        });

        // VortexIndicator call → inline helper
        body = convertBigPattern(body, VORTEX_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String op = m.group(2);
            int shift = Integer.parseInt(m.group(3));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcVortexPlus(history, " + period + ", " + actualShift + ")";
        });

        // LWMAIndicator call → inline helper
        body = convertBigPattern(body, LWMA_CALL, (m) -> {
            int period = Integer.parseInt(m.group(1));
            String priceType = m.group(3).toLowerCase();
            String op = m.group(4);
            int shift = Integer.parseInt(m.group(5));
            int actualShift = op.equals("-") ? shift : -shift;
            return "calcLWMA(history, " + period + ", \"" + priceType + "\", " + actualShift + ")";
        });

        return body;
    }

    private static String convertBigPattern(String body, Pattern pattern, MatchConverter converter) {
        StringBuffer sb = new StringBuffer();
        Matcher m = pattern.matcher(body);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(converter.convert(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @FunctionalInterface
    private interface MatchConverter {
        String convert(Matcher m);
    }

    static String convertPlaceBuyStop(String body) {
        // Extract all engine.placeBuyStop(...) calls and replace with order creation
        StringBuffer sb = new StringBuffer();
        Matcher m = Pattern.compile(
                "engine\\.placeBuyStop\\((\\d+(?:\\.\\d+)?[^)]*)\\)")
                .matcher(body);

        // We need a more flexible matcher since params can be expressions
        // Replace with a placeholder pattern, then process
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int searchFrom = 0;

        while (true) {
            int idx = body.indexOf("engine.placeBuyStop(", searchFrom);
            if (idx < 0) {
                result.append(body.substring(lastEnd));
                break;
            }

            // Find the matching closing paren
            int parenDepth = 0;
            int endIdx = -1;
            for (int i = idx + "engine.placeBuyStop(".length(); i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '(') parenDepth++;
                else if (c == ')') {
                    if (parenDepth == 0) {
                        endIdx = i;
                        break;
                    }
                    parenDepth--;
                }
            }

            if (endIdx < 0) {
                result.append(body.substring(lastEnd));
                break;
            }

            // Extract the argument list
            String args = body.substring(idx + "engine.placeBuyStop(".length(), endIdx).trim();
            // Remove trailing paren
            if (args.endsWith(")")) args = args.substring(0, args.length() - 1);
            String[] parts = splitArgs(args);

            String orderCode;
            if (parts.length >= 6) {
                String entryPrice = parts[0].trim();
                String slPips = parts[1].trim();
                String tpPips = parts[2].trim();
                String trailingStop = parts[3].trim();
                String trailingAct = parts[4].trim();
                String expiration = parts[5].trim();

                orderCode = String.format(
                        "{\n" +
                        "            double ep = %s;\n" +
                        "            double sl = ep - (%s * PIP);\n" +
                        "            double tp = ep + (%s * PIP);\n" +
                        "            Order order = new Order(SYMBOL, Order.Side.BUY, Order.Type.STOP, QUANTITY, ep)\n" +
                        "                .withStopLoss(sl)\n" +
                        "                .withTakeProfit(tp);\n" +
                        "            pendingOrders.add(order);\n" +
                        "            activeOrder = order;\n" +
                        "            entryTriggered = true;\n" +
                        "            barsSinceEntry = 0;\n" +
                        "            expirationBars = %s;\n" +
                        "        }",
                        entryPrice, slPips, tpPips, expiration);
            } else {
                orderCode = "        // TODO: convert placeBuyStop(" + args + ")\n";
            }

            result.append(body, lastEnd, idx);
            result.append(orderCode);
            lastEnd = endIdx + 1;
            searchFrom = endIdx + 1;
        }

        return result.toString();
    }

    private static String[] splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts.toArray(new String[0]);
    }

    // ---------------------------------------------------------------
    //  Boilerplate generation
    // ---------------------------------------------------------------

    static String addBoilerplate(String source, JavaClassInfo info) {
        // Find the class opening brace and add fields + methods
        int classBraceIdx = findClassBrace(source, info.targetName);
        if (classBraceIdx < 0) return source;

        String before = source.substring(0, classBraceIdx + 1);
        String after = source.substring(classBraceIdx + 1);

        String boilerplate = "\n" +
                "\n    // ---- JForex Conversion Parameters ----\n" +
                "    private static final double PIP = 0.01; // JPY pair default\n" +
                "    private static final String SYMBOL = \"GBP_JPY\";\n" +
                "    private static final double QUANTITY = 1000;\n" +
                "\n" +
                "    private final List<Bar> history = new ArrayList<>();\n" +
                "    private final List<Order> pendingOrders = new ArrayList<>();\n" +
                "    private Order activeOrder = null;\n" +
                "    private int barsSinceEntry = 0;\n" +
                "    private boolean entryTriggered = false;\n" +
                "    private int expirationBars = 0;\n" +
                "    private int MIN_BARS = 50;\n" +
                "\n" +
                "    @Override\n" +
                "    public String name() {\n" +
                "        return \"" + info.targetName + "\";\n" +
                "    }\n" +
                "\n" +
                "    @Override\n" +
                "    public void onTick(double bid, double ask, long volume) {\n" +
                "        // Not used for bar-based strategy\n" +
                "    }\n" +
                "\n" +
                "    @Override\n" +
                "    public List<Order> getPendingOrders() {\n" +
                "        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);\n" +
                "        return pendingOrders;\n" +
                "    }\n" +
                "\n" +
                "    @Override\n" +
                "    public void reset() {\n" +
                "        history.clear();\n" +
                "        pendingOrders.clear();\n" +
                "        activeOrder = null;\n" +
                "        barsSinceEntry = 0;\n" +
                "        entryTriggered = false;\n" +
                "        expirationBars = 0;\n" +
                "    }\n" +
                "\n" +
                "    // ---- Indicator helpers ----\n" +
                "\n" +
                "    private Bar getBar(int shift) {\n" +
                "        return history.get(history.size() - 1 - shift);\n" +
                "    }\n" +
                "\n" +
                "    private double calcSMA(List<Bar> bars, int period, String priceType, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 0) return 0;\n" +
                "        double sum = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            sum += getPrice(bars.get(i), priceType);\n" +
                "        }\n" +
                "        return sum / period;\n" +
                "    }\n" +
                "\n" +
                "    private double calcBBRange(List<Bar> bars, int period, double mult, String priceType, int shift) {\n" +
                "        double sma = calcSMA(bars, period, priceType, shift);\n" +
                "        if (sma == 0) return 0;\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 0) return 0;\n" +
                "        double variance = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double diff = getPrice(bars.get(i), priceType) - sma;\n" +
                "            variance += diff * diff;\n" +
                "        }\n" +
                "        double stdDev = Math.sqrt(variance / period);\n" +
                "        return 2.0 * mult * stdDev;\n" +
                "    }\n" +
                "\n" +
                "    private double calcLinReg(List<Bar> bars, int period, String priceType, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 0) return 0;\n" +
                "        double sumX = 0, sumY = 0;\n" +
                "        int n = period;\n" +
                "        for (int i = 0; i < n; i++) {\n" +
                "            sumX += i;\n" +
                "            sumY += getPrice(bars.get(start + i), priceType);\n" +
                "        }\n" +
                "        double meanX = sumX / n;\n" +
                "        double meanY = sumY / n;\n" +
                "        double covariance = 0, varianceX = 0;\n" +
                "        for (int i = 0; i < n; i++) {\n" +
                "            double xi = i;\n" +
                "            double yi = getPrice(bars.get(start + i), priceType);\n" +
                "            covariance += (xi - meanX) * (yi - meanY);\n" +
                "            varianceX += (xi - meanX) * (xi - meanX);\n" +
                "        }\n" +
                "        if (varianceX == 0) return meanY;\n" +
                "        double slope = covariance / varianceX;\n" +
                "        double intercept = meanY - slope * meanX;\n" +
                "        return intercept + slope * (n - 1);\n" +
                "    }\n" +
                "\n" +
                "    private double calcBiggestRange(List<Bar> bars, int period, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 0) return 0;\n" +
                "        double maxRange = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double range = bars.get(i).high() - bars.get(i).low();\n" +
                "            if (range > maxRange) maxRange = range;\n" +
                "        }\n" +
                "        return maxRange;\n" +
                "    }\n" +
                "\n" +
                "    private double calcATR(List<Bar> bars, int period, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 1) return 0;\n" +
                "        double sumTr = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double tr1 = bars.get(i).high() - bars.get(i).low();\n" +
                "            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());\n" +
                "            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());\n" +
                "            sumTr += Math.max(tr1, Math.max(tr2, tr3));\n" +
                "        }\n" +
                "        return sumTr / period;\n" +
                "    }\n" +
                "\n" +
                "    private double calcVortexPlus(List<Bar> bars, int period, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 1) return 0;\n" +
                "        double sumVmPlus = 0, sumTr = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double tr1 = bars.get(i).high() - bars.get(i).low();\n" +
                "            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());\n" +
                "            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());\n" +
                "            double tr = Math.max(tr1, Math.max(tr2, tr3));\n" +
                "            double vmPlus = Math.abs(bars.get(i).high() - bars.get(i - 1).low());\n" +
                "            sumVmPlus += vmPlus;\n" +
                "            sumTr += tr;\n" +
                "        }\n" +
                "        return sumTr > 0 ? sumVmPlus / sumTr : 0;\n" +
                "    }\n" +
                "\n" +
                "    private double calcVortexMinus(List<Bar> bars, int period, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 1) return 0;\n" +
                "        double sumVmMinus = 0, sumTr = 0;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double tr1 = bars.get(i).high() - bars.get(i).low();\n" +
                "            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());\n" +
                "            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());\n" +
                "            double tr = Math.max(tr1, Math.max(tr2, tr3));\n" +
                "            double vmMinus = Math.abs(bars.get(i).low() - bars.get(i - 1).high());\n" +
                "            sumVmMinus += vmMinus;\n" +
                "            sumTr += tr;\n" +
                "        }\n" +
                "        return sumTr > 0 ? sumVmMinus / sumTr : 0;\n" +
                "    }\n" +
                "\n" +
                "    private double calcLWMA(List<Bar> bars, int period, String priceType, int shift) {\n" +
                "        int end = bars.size() - 1 - shift;\n" +
                "        int start = end - period + 1;\n" +
                "        if (start < 0) return 0;\n" +
                "        double sumWeightedPrice = 0, sumWeight = 0;\n" +
                "        int weight = 1;\n" +
                "        for (int i = start; i <= end; i++) {\n" +
                "            double price = getPrice(bars.get(i), priceType);\n" +
                "            sumWeightedPrice += price * weight;\n" +
                "            sumWeight += weight;\n" +
                "            weight++;\n" +
                "        }\n" +
                "        return sumWeightedPrice / sumWeight;\n" +
                "    }\n" +
                "\n" +
                "    private double getPrice(Bar bar, String type) {\n" +
                "        return switch (type.toLowerCase()) {\n" +
                "            case \"open\" -> bar.open();\n" +
                "            case \"high\" -> bar.high();\n" +
                "            case \"low\" -> bar.low();\n" +
                "            case \"close\" -> bar.close();\n" +
                "            case \"median\" -> (bar.high() + bar.low()) / 2.0;\n" +
                "            case \"typical\" -> (bar.high() + bar.low() + bar.close()) / 3.0;\n" +
                "            default -> bar.close();\n" +
                "        };\n" +
                "    }\n";

        return before + boilerplate + "\n" + after;
    }

    static int findClassBrace(String source, String className) {
        // Find the opening brace of the class declaration
        Pattern p = Pattern.compile("public\\s+class\\s+" + Pattern.quote(className) + "[^\\{]*\\{");
        Matcher m = p.matcher(source);
        if (m.find()) {
            return m.end() - 1; // position of '{'
        }
        return -1;
    }

    /**
     * Internal data carrier for class metadata during conversion.
     */
    static class JavaClassInfo {
        String targetName;
        String javadoc;
    }
}
