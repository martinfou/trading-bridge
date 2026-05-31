package com.martinfou.trading.parser.codegen;

import java.util.Objects;

/** Options for {@link SqStrategyCodeGenerator} (story 2-9). */
public record SqCodegenRequest(
    String className,
    String packageName,
    String xmlResourcePath,
    String defaultSymbol
) {
    public SqCodegenRequest {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(xmlResourcePath, "xmlResourcePath");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (defaultSymbol == null || defaultSymbol.isBlank()) {
            defaultSymbol = "EUR_USD";
        }
    }

    public static SqCodegenRequest of(String className, String packageName, String xmlResourcePath) {
        return new SqCodegenRequest(className, packageName, xmlResourcePath, "EUR_USD");
    }
}
