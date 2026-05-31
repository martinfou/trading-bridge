package com.martinfou.trading.tui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * JLine3 terminal workshop for the control plane (Story 13.6).
 *
 * <p>Start the control plane first:
 * {@code mvn exec:java -pl trading-runtime -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain}
 */
public final class TradingTuiMain {

    private TradingTuiMain() {}

    public static void main(String[] args) throws Exception {
        ControlPlaneClient client = ControlPlaneClient.fromEnvironment();
        TuiCommandHandler handler = new TuiCommandHandler(client);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            terminal.writer().println("Trading Bridge TUI → " + client.baseUrl());
            try {
                var health = client.health();
                terminal.writer().println("Connected: " + health.get("status").asText()
                    + " v" + health.get("version").asText());
            } catch (Exception e) {
                terminal.writer().println("Warning: control plane unreachable — " + e.getMessage());
                terminal.writer().println("Start ControlPlaneMain then retry commands.");
            }
            terminal.writer().println("Type /help for slash commands.");
            terminal.flush();

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

            while (true) {
                String line;
                try {
                    line = reader.readLine("tui> ");
                } catch (UserInterruptException e) {
                    break;
                } catch (EndOfFileException e) {
                    break;
                }
                for (String output : handler.handle(line)) {
                    if ("__QUIT__".equals(output)) {
                        terminal.writer().println("Bye.");
                        terminal.flush();
                        return;
                    }
                    terminal.writer().println(output);
                }
                terminal.flush();
            }
        }
    }
}
