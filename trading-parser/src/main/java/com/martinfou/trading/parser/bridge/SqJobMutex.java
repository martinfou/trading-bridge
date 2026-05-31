package com.martinfou.trading.parser.bridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exclusive file lock so only one sqcli job runs at a time (story 21-5). */
public final class SqJobMutex implements AutoCloseable {

    private final RandomAccessFile file;
    private final FileLock lock;

    private SqJobMutex(RandomAccessFile file, FileLock lock) {
        this.file = file;
        this.lock = lock;
    }

    public static SqJobMutex acquire(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.getParent());
        RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
        FileLock acquired;
        try {
            acquired = raf.getChannel().tryLock();
        } catch (OverlappingFileLockException e) {
            raf.close();
            throw new SqJobBusyException("SQ job already running (lock: " + lockFile + ")");
        }
        if (acquired == null) {
            raf.close();
            throw new SqJobBusyException("SQ job already running (lock: " + lockFile + ")");
        }
        return new SqJobMutex(raf, acquired);
    }

    @Override
    public void close() throws IOException {
        lock.release();
        file.close();
    }
}
