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

    @Test
    void listOandaAccounts_callsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/broker-accounts/oanda-accounts", exchange -> {
            byte[] body = """
                {"accounts":[{"id":"101-123","tags":[]}]}
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ControlPlaneClient client = new ControlPlaneClient("http://127.0.0.1:" + port);
            var node = client.listOandaAccounts("mock-token", "https://api-fxpractice.oanda.com");
            assertEquals("101-123", node.get("accounts").get(0).get("id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void updateBrokerAccount_callsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/broker-accounts", exchange -> {
            byte[] body = """
                {"success":true}
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ControlPlaneClient client = new ControlPlaneClient("http://127.0.0.1:" + port);
            var node = client.updateBrokerAccount("default", "OANDA", "mock-token", "101-123", "https://api-fxpractice.oanda.com");
            org.junit.jupiter.api.Assertions.assertTrue(node.get("success").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testBrokerAccount_callsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/broker-accounts/test", exchange -> {
            byte[] body = """
                {"success":true,"balance":12345.67,"currency":"USD"}
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ControlPlaneClient client = new ControlPlaneClient("http://127.0.0.1:" + port);
            var node = client.testBrokerAccount("default", "OANDA", "mock-token", "101-123", "https://api-fxpractice.oanda.com");
            org.junit.jupiter.api.Assertions.assertTrue(node.get("success").asBoolean());
            assertEquals(12345.67, node.get("balance").asDouble());
        } finally {
            server.stop(0);
        }
    }
}
