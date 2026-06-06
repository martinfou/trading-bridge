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
}
