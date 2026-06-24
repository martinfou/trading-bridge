package com.martinfou.trading.intelligence.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateRegistryTest {

    @Test
    void loadDefault_containsT1ThroughT8() throws Exception {
        TemplateRegistry registry = TemplateRegistry.loadDefault();
        assertEquals(8, registry.templateIds().size());
        assertTrue(registry.templateIds().containsAll(java.util.List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8")));
        assertEquals(6, registry.whitelistPairs().size());
        assertEquals(TemplateRegistry.CodegenHandler.DELEGATE_LORB, registry.require("T4").codegenHandler());
        assertEquals(TemplateRegistry.CodegenHandler.NO_TRADE, registry.require("T8").codegenHandler());
    }

    @Test
    void constructor_handlesNullsAndNullElementsCleanly() {
        TemplateRegistry registry = new TemplateRegistry(
            java.util.Arrays.asList("EUR_USD", null, "GBP_USD"),
            null
        );
        assertEquals(2, registry.whitelistPairs().size());
        assertTrue(registry.whitelistPairs().contains("EUR_USD"));
        assertTrue(registry.whitelistPairs().contains("GBP_USD"));
        for (String p : registry.whitelistPairs()) {
            assertNotNull(p);
        }
        assertEquals(0, registry.templateIds().size());

        java.util.Map<String, TemplateRegistry.TemplateEntry> map = new java.util.HashMap<>();
        map.put("T1", null);
        map.put(null, new TemplateRegistry.TemplateEntry("T2", "Name", "Desc", TemplateRegistry.CodegenHandler.NO_TRADE, java.util.List.of(), java.util.List.of(), java.util.List.of()));
        
        TemplateRegistry registry2 = new TemplateRegistry(null, map);
        assertEquals(0, registry2.templateIds().size());
    }

    @Test
    void parseCodegenHandler_isCaseInsensitiveAndSafe() throws Exception {
        String json = """
            {
              "whitelistPairs": ["EUR_USD"],
              "templates": {
                "T_TEST_1": {
                  "name": "Test1",
                  "description": "Test",
                  "codegenHandler": "thin_prop",
                  "requiredParams": [],
                  "optionalParams": [],
                  "allowedDirections": []
                },
                "T_TEST_2": {
                  "name": "Test2",
                  "description": "Test",
                  "codegenHandler": "invalid_value",
                  "requiredParams": [],
                  "optionalParams": [],
                  "allowedDirections": []
                }
              }
            }
            """;
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            TemplateRegistry registry = TemplateRegistry.load(in);
            assertEquals(TemplateRegistry.CodegenHandler.THIN_PROP, registry.require("T_TEST_1").codegenHandler());
            assertEquals(TemplateRegistry.CodegenHandler.NO_TRADE, registry.require("T_TEST_2").codegenHandler());
        }
    }
}
