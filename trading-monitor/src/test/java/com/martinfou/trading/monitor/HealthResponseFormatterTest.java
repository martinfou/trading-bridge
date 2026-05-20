package com.martinfou.trading.monitor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthResponseFormatterTest {

    private final HealthResponseFormatter fmt = new HealthResponseFormatter();

    @Test
    void format_containsAllFields() {
        String json = fmt.format(
                "backtest", "72h 30m", "abc1234", "1.0.0",
                "[\"SMA_CROSS\"]", "2026-05-20T14:30:00Z",
                0, "ok",
                12.5, 45.0, 23.0, "DEP-001"
        );

        assertAll(
                () -> assertTrue(json.contains("\"machine\": \"backtest\"")),
                () -> assertTrue(json.contains("\"uptime\": \"72h 30m\"")),
                () -> assertTrue(json.contains("\"git_commit\": \"abc1234\"")),
                () -> assertTrue(json.contains("\"version\": \"1.0.0\"")),
                () -> assertTrue(json.contains("\"deployment_id\": \"DEP-001\"")),
                () -> assertTrue(json.contains("\"active_strategies\": [\"SMA_CROSS\"]")),
                () -> assertTrue(json.contains("\"errors_24h\": 0")),
                () -> assertTrue(json.contains("\"oanda_api_status\": \"ok\"")),
                () -> assertTrue(json.contains("\"cpu\": 12.5")),
                () -> assertTrue(json.contains("\"memory\": 45.0")),
                () -> assertTrue(json.contains("\"disk\": 23.0"))
        );
    }

    @Test
    void format_unavailableResources() {
        String json = fmt.format(
                "test", "0h", "unknown", "1.0.0",
                "[]", "", 0, "unknown",
                -1, -1, -1, ""
        );

        assertAll(
                () -> assertTrue(json.contains("\"cpu\": \"unavailable\"")),
                () -> assertTrue(json.contains("\"memory\": \"unavailable\"")),
                () -> assertTrue(json.contains("\"disk\": \"unavailable\""))
        );
    }

    @Test
    void format_validJson() {
        String json = fmt.format(
                "paper-vps", "5h 0m", "def5678", "2.0.0",
                "[]", "", 2, "error",
                30.0, 60.0, 40.0, ""
        );

        // Basic JSON validity: balanced braces
        int opens = countChar(json, '{');
        int closes = countChar(json, '}');
        assertEquals(opens, closes, "JSON braces must be balanced");
    }

    private static int countChar(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }
}
