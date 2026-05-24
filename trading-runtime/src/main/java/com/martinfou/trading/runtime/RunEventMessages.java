package com.martinfou.trading.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON messages for WebSocket RunEvent frames ({@code sequence} + {@code event}).
 */
final class RunEventMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private RunEventMessages() {}

    static String toJson(StoredRunEvent stored) {
        try {
            return MAPPER.writeValueAsString(
                java.util.Map.of("sequence", stored.sequence(), "event", stored.event()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize StoredRunEvent", e);
        }
    }
}
