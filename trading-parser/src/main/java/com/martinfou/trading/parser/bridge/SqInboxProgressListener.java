package com.martinfou.trading.parser.bridge;

/**
 * Callback for each XML file processed by {@link SqInboxProcessor} (story 21-7).
 */
@FunctionalInterface
public interface SqInboxProgressListener {

    void onFileProcessed(String fileName, String disposition, String manifestId);
}
