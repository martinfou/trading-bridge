package com.martinfou.trading.tui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ControlPlaneClientTest {

    @Test
    void processSqInbox_accepts409Conflict() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/sq-bridge/process-inbox", exchange -> {
            byte[] body = """
                {"accepted":false,"message":"Inbox processing already running"}
                """.strip().getBytes();
            exchange.sendResponseHeaders(409, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ControlPlaneClient client = new ControlPlaneClient("http://127.0.0.1:" + port);
            var node = client.processSqInbox();
            assertFalse(node.get("accepted").asBoolean());
            assertEquals("Inbox processing already running", node.get("message").asText());
        } finally {
            server.stop(0);
        }
    }
}
