package com.martinfou.trading.parser.bridge;

import java.io.IOException;
import java.nio.file.Path;

/** Pre-parse inbox checks (story 21-3). */
public final class SqInboxValidation {

    private SqInboxValidation() {}

    public static void requireConfinedToPending(Path xml, Path pendingDir) throws IOException {
        Path absoluteXml = xml.toAbsolutePath().normalize();
        Path absolutePending = pendingDir.toAbsolutePath().normalize();
        Path relative = absolutePending.relativize(absoluteXml);
        if (relative.startsWith("..") || relative.isAbsolute()) {
            throw new InboxValidationException(
                InboxValidationException.Reason.OUTSIDE_PENDING,
                "XML path outside pending inbox: " + xml
            );
        }
    }

    public static void requireWithinSizeLimit(long byteCount, long maxBytes) throws IOException {
        if (byteCount <= 0) {
            throw new InboxValidationException(
                InboxValidationException.Reason.EMPTY_FILE,
                "XML file is empty"
            );
        }
        if (byteCount > maxBytes) {
            throw new InboxValidationException(
                InboxValidationException.Reason.EXCEEDS_MAX_SIZE,
                "XML exceeds max size " + maxBytes + " bytes"
            );
        }
    }
}
