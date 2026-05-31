package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqJobMutexTest {

    @Test
    void acquire_blocksSecondHolder(@TempDir Path repo) throws Exception {
        Path lock = SqJobPaths.lockFile(repo);
        try (SqJobMutex first = SqJobMutex.acquire(lock)) {
            assertThrows(SqJobBusyException.class, () -> SqJobMutex.acquire(lock));
        }
    }

    @Test
    void acquire_releasedLockCanBeRetaken(@TempDir Path repo) throws Exception {
        Path lock = SqJobPaths.lockFile(repo);
        try (SqJobMutex ignored = SqJobMutex.acquire(lock)) {
            // held
        }
        assertDoesNotThrow(() -> {
            try (SqJobMutex second = SqJobMutex.acquire(lock)) {
                assertNotNull(second);
            }
        });
    }
}
