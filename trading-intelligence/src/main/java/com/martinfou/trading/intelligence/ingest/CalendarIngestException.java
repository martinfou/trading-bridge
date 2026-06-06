package com.martinfou.trading.intelligence.ingest;

/** Calendar ingest failed — pipeline must not hand off to LLM. */
public class CalendarIngestException extends Exception {

    public CalendarIngestException(String message) {
        super(message);
    }

    public CalendarIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
