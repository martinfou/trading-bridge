package com.martinfou.trading.parser.codegen;

import com.martinfou.trading.parser.sq.SqStrategyDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates thin {@link com.martinfou.trading.core.Strategy} wrapper classes from SQ XML (story 2-9).
 * Generated classes delegate to {@link SqInterpretedStrategy} at runtime.
 */
public final class SqStrategyCodeGenerator {

    private static final String INDENT = "    ";

    private SqStrategyCodeGenerator() {}

    public static String generate(SqStrategyDocument document, SqCodegenRequest request) {
        String strategyTitle = document.strategyName() == null || document.strategyName().isBlank()
            ? request.className()
            : document.strategyName();
        String engine = document.engine() == null ? "" : document.engine();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("package ").append(request.packageName()).append(";\n\n");
        sb.append("import com.martinfou.trading.core.Bar;\n");
        sb.append("import com.martinfou.trading.core.Order;\n");
        sb.append("import com.martinfou.trading.core.Strategy;\n");
        sb.append("import com.martinfou.trading.parser.codegen.SqInterpretedStrategy;\n\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n");
        sb.append(" * SQ interpreter strategy generated from StrategyQuant XML.\n");
        sb.append(" * <p>Source strategy: ").append(escapeComment(strategyTitle));
        sb.append(" (engine: ").append(escapeComment(engine)).append(")</p>\n");
        sb.append(" * <p>XML resource: {@code ").append(request.xmlResourcePath()).append("}</p>\n");
        sb.append(" */\n");
        sb.append("public class ").append(request.className()).append(" implements Strategy {\n\n");
        sb.append(INDENT).append("private final SqInterpretedStrategy delegate;\n\n");
        sb.append(INDENT).append("public ").append(request.className()).append("(String symbol) {\n");
        sb.append(INDENT).append(INDENT).append("this.delegate = SqInterpretedStrategy.fromClasspath(\"");
        sb.append(request.xmlResourcePath()).append("\", \"").append(request.className());
        sb.append("\", symbol);\n");
        sb.append(INDENT).append("}\n\n");
        sb.append(INDENT).append("public static ").append(request.className()).append(" forDefaultSymbol() {\n");
        sb.append(INDENT).append(INDENT).append("return new ").append(request.className());
        sb.append("(\"").append(request.defaultSymbol()).append("\");\n");
        sb.append(INDENT).append("}\n\n");
        appendDelegateMethod(sb, "String", "name", "name()");
        appendDelegateVoid(sb, "onBar", "bar", "onBar(bar)");
        appendDelegateVoid(sb, "onTick", "bid, ask, volume", "onTick(bid, ask, volume)");
        appendDelegateMethod(sb, "List<Order>", "getPendingOrders", "getPendingOrders()");
        appendDelegateVoid(sb, "reset", "", "reset()");
        sb.append("}\n");
        return sb.toString();
    }

    public static Path write(SqStrategyDocument document, SqCodegenRequest request, Path outputDirectory)
        throws IOException {
        String source = generate(document, request);
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(request.className() + ".java");
        Files.writeString(output, source);
        return output;
    }

    public static String sanitizeClassName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SqGeneratedStrategy";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isJavaIdentifierStart(cleaned.charAt(0))) {
            cleaned = "Sq_" + cleaned;
        }
        return cleaned;
    }

    private static void appendDelegateMethod(StringBuilder sb, String returnType, String method, String delegateCall) {
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public ").append(returnType).append(" ").append(method).append("() {\n");
        sb.append(INDENT).append(INDENT).append("return delegate.").append(delegateCall).append(";\n");
        sb.append(INDENT).append("}\n\n");
    }

    private static void appendDelegateVoid(StringBuilder sb, String method, String params, String delegateCall) {
        sb.append(INDENT).append("@Override\n");
        sb.append(INDENT).append("public void ").append(method).append("(");
        if ("onBar".equals(method)) {
            sb.append("Bar bar");
        } else if ("onTick".equals(method)) {
            sb.append("double bid, double ask, long volume");
        }
        sb.append(") {\n");
        sb.append(INDENT).append(INDENT).append("delegate.").append(delegateCall).append(";\n");
        sb.append(INDENT).append("}\n\n");
    }

    private static String escapeComment(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("*/", "* /");
    }
}
