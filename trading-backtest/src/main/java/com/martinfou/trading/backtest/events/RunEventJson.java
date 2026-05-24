package com.martinfou.trading.backtest.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON Lines serialization for {@link RunEvent} (schema v1).
 */
public final class RunEventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RunEventJson() {}

    public static String toJsonLine(RunEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize RunEvent", e);
        }
    }

    public static RunEvent fromJsonLine(String line) {
        try {
            return MAPPER.readValue(line, RunEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid RunEvent JSON line", e);
        }
    }
}
