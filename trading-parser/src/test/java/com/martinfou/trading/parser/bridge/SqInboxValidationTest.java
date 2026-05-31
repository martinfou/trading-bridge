package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqInboxValidationTest {

    @Test
    void requireConfinedToPending_acceptsFileInPending(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Path xml = pending.resolve("strategy.xml");
        Files.writeString(xml, "<StrategyFile Version=\"1\"/>");

        assertDoesNotThrow(() -> SqInboxValidation.requireConfinedToPending(xml, pending));
    }

    @Test
    void requireConfinedToPending_rejectsSiblingDirectory(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Path sibling = SqInboxPaths.root(repo).resolve("pending_other");
        Files.createDirectories(sibling);
        Path xml = sibling.resolve("strategy.xml");
        Files.writeString(xml, "<StrategyFile Version=\"1\"/>");

        InboxValidationException ex = assertThrows(
            InboxValidationException.class,
            () -> SqInboxValidation.requireConfinedToPending(xml, pending)
        );
        assertEquals(InboxValidationException.Reason.OUTSIDE_PENDING, ex.reason());
    }

    @Test
    void requireWithinSizeLimit_rejectsEmpty() {
        InboxValidationException ex = assertThrows(
            InboxValidationException.class,
            () -> SqInboxValidation.requireWithinSizeLimit(0, 1024)
        );
        assertEquals(InboxValidationException.Reason.EMPTY_FILE, ex.reason());
    }
}
