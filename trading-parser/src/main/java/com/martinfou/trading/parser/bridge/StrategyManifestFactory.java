package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlFormatProbe;
import com.martinfou.trading.parser.sq.SqXmlFormatReport;
import com.martinfou.trading.parser.sq.SqXmlParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Builds {@link StrategyManifest} instances from SQ XML files (story 21-1). */
public final class StrategyManifestFactory {

    private StrategyManifestFactory() {}

    public static StrategyManifest fromXml(Path xmlPath) throws IOException {
        byte[] bytes = Files.readAllBytes(xmlPath);
        Instant exportedAt = Files.getLastModifiedTime(xmlPath).toInstant();
        return fromBytes(bytes, xmlPath, exportedAt);
    }

    static StrategyManifest fromBytes(byte[] bytes, Path xmlPath) throws IOException {
        return fromBytes(bytes, xmlPath, Files.getLastModifiedTime(xmlPath).toInstant());
    }

    static StrategyManifest fromBytes(byte[] bytes, Path xmlPath, Instant exportedAt) throws IOException {
        SqStrategyDocument document = SqXmlParser.parse(new ByteArrayInputStream(bytes));
        SqXmlFormatReport report = SqXmlFormatProbe.analyze(document);
        String id = manifestId(document, xmlPath);
        return new StrategyManifest(
            id,
            defaultSymbol(document),
            "UNKNOWN",
            report.strategyFileVersion(),
            sha256(bytes),
            exportedAt
        );
    }

    public static StrategyManifest fromXml(Path xmlPath, Instant exportedAt) throws IOException {
        StrategyManifest base = fromXml(xmlPath);
        return new StrategyManifest(
            base.id(),
            base.symbol(),
            base.timeframe(),
            base.sqBuild(),
            base.contentSha256(),
            exportedAt
        );
    }

    static String manifestId(SqStrategyDocument document, Path xmlPath) {
        if (document.strategyName() != null && !document.strategyName().isBlank()) {
            return sanitizeId(document.strategyName());
        }
        String fileName = xmlPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return sanitizeId(dot > 0 ? fileName.substring(0, dot) : fileName);
    }

    static String defaultSymbol(SqStrategyDocument document) {
        return "EUR_USD";
    }

    static String sanitizeId(String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "sq_strategy" : cleaned;
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
