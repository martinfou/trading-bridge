package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqJobScriptRegistryTest {

    @Test
    void load_readsScripts(@TempDir Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.copy(
            getClass().getResourceAsStream("/sq-cli/registry-test.json"),
            registry
        );

        SqJobScriptRegistry reg = SqJobScriptRegistry.load(registry);

        assertEquals(1, reg.all().size());
        SqJobScript script = reg.require("test-job");
        assertEquals("Test job", script.description());
        assertEquals(2, script.args().size());
    }

    @Test
    void load_rejectsDuplicateIds(@TempDir Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, """
            {
              "scripts": [
                {"id": "a", "description": "one", "args": ["-symbol", "action=list"]},
                {"id": "a", "description": "two", "args": ["-data", "action=update"]}
              ]
            }
            """);

        assertThrows(IOException.class, () -> SqJobScriptRegistry.load(registry));
    }

    @Test
    void require_unknownId_throws(@TempDir Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.copy(
            getClass().getResourceAsStream("/sq-cli/registry-test.json"),
            registry
        );
        SqJobScriptRegistry reg = SqJobScriptRegistry.load(registry);
        assertThrows(IOException.class, () -> reg.require("missing"));
    }
}
