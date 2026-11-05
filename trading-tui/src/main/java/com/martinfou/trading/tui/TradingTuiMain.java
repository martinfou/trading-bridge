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

        if (args != null && args.length > 0) {
            java.util.List<String> argList = java.util.Arrays.asList(args);
            boolean jsonFlag = argList.stream().anyMatch("--json"::equalsIgnoreCase);
            java.util.function.Consumer<String> liveOut = jsonFlag ? System.err::println : System.out::println;
            java.util.List<String> output = handler.handleCliArgs(argList, liveOut);
            boolean isError = false;
            for (String line : output) {
                if ("__QUIT__".equals(line)) {
                    continue;
                }
                System.out.println(line);
                if (line.startsWith("API error ")
                    || line.startsWith("Invalid input: ")
                    || line.startsWith("Invalid ")
                    || line.startsWith("Request failed: ")
                    || line.startsWith("Unknown command: ")
                    || line.startsWith("Backtest data: ")
                    || line.startsWith("Strategy or Databank candidate not found")) {
                    isError = true;
                }
            }
            if (isError) {
                System.exit(1);
            }
            return;
        }

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            terminal.writer().println("Trading Bridge TUI → " + client.baseUrl());
            try {
                var health = client.health();
                terminal.writer().println("Connected: " + health.get("status").asText()
                    + " v" + health.get("version").asText()
                    + (health.path("dataCatalog").asBoolean(false)
                        ? "  (data catalog OK)"
                        : "  (data catalog MISSING — restart control plane)"));
            } catch (Exception e) {
                terminal.writer().println("Warning: control plane unreachable — " + e.getMessage());
                terminal.writer().println("Start ControlPlaneMain then retry commands.");
            }
            terminal.writer().println("Type /help for slash commands. Use /backtest for interactive wizard.");
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
                for (String output : handler.handle(line, reader, msg -> {
                    terminal.writer().println(msg);
                    terminal.flush();
                })) {
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
