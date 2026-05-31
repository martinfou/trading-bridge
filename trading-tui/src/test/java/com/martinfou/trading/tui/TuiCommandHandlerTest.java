package com.martinfou.trading.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiCommandHandlerTest {

    @Test
    void tokenize_splitsQuotedArgs() {
        List<String> tokens = TuiCommandHandler.tokenize("promote LondonOpenRangeBreakout PAPER run-1");
        assertEquals(List.of("promote", "LondonOpenRangeBreakout", "PAPER", "run-1"), tokens);
    }

    @Test
    void help_listsCoreCommands() {
        List<String> lines = TuiCommandHandler.help();
        assertTrue(lines.stream().anyMatch(l -> l.contains("/backtest")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/promote")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/sq")));
    }

    @Test
    void handle_unknownCommand() {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> out = handler.handle("/nope");
        assertTrue(out.getFirst().contains("Unknown command"));
    }

    @Test
    void handle_nonSlashInput() {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> out = handler.handle("hello");
        assertTrue(out.getFirst().contains("/help"));
    }
}
