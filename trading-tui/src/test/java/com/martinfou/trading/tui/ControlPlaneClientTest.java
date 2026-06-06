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

    @Test
    void getJson_plainText404_reportsBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/data/symbols", exchange -> {
            byte[] body = "Endpoint GET /api/data/symbols not found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ControlPlaneClient client = new ControlPlaneClient("http://127.0.0.1:" + port);
            try {
                client.listDataSymbols();
                org.junit.jupiter.api.Assertions.fail("expected exception");
            } catch (ControlPlaneClient.ControlPlaneException e) {
                assertEquals(404, e.statusCode());
                org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("not found"));
            }
        } finally {
            server.stop(0);
        }
    }
}
