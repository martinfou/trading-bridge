package com.martinfou.trading.parser.codegen;

import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqStrategyCodeGeneratorTest {

    @Test
    void generate_fixture_producesCompilableWrapper() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        SqCodegenRequest request = SqCodegenRequest.of(
            "Strategy_1_6_221B",
            "com.martinfou.trading.strategies.sqgenerated",
            "/sq/strategy-1.6.221B.xml"
        );

        String source = SqStrategyCodeGenerator.generate(document, request);

        assertTrue(source.contains("public class Strategy_1_6_221B implements Strategy"));
        assertTrue(source.contains("SqInterpretedStrategy.fromClasspath(\"/sq/strategy-1.6.221B.xml\""));
        assertTrue(source.contains("forDefaultSymbol()"));
    }

    @Test
    void write_createsJavaFile(@TempDir Path tempDir) throws Exception {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        SqCodegenRequest request = SqCodegenRequest.of(
            "Strategy_1_6_221B",
            "com.martinfou.trading.parser.codegen.generated",
            "/sq/strategy-1.6.221B.xml"
        );

        Path written = SqStrategyCodeGenerator.write(document, request, tempDir);

        assertTrue(Files.exists(written));
        assertEquals("Strategy_1_6_221B.java", written.getFileName().toString());
        assertTrue(Files.readString(written).contains("implements Strategy"));
    }

    @Test
    void sanitizeClassName_stripsInvalidCharacters() {
        assertEquals("Sq_1_6_221B", SqStrategyCodeGenerator.sanitizeClassName("1.6.221B"));
        assertEquals("MyStrategy", SqStrategyCodeGenerator.sanitizeClassName("MyStrategy"));
    }

    @Test
    void pipSize_jpyPairsUseTwoDecimalPips() {
        assertEquals(0.01, SqPipScale.pipSize("GBP_JPY"), 1e-9);
        assertEquals(0.0001, SqPipScale.pipSize("EUR_USD"), 1e-9);
    }
}
