package com.martinfou.trading.data.ibkr;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock TCP server simulating IB Gateway / TWS socket connectivity for unit and integration testing.
 */
public final class MockTcpGatewayServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    public MockTcpGatewayServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.executor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionCount.incrementAndGet();
                executor.submit(() -> handleClient(clientSocket));
            } catch (IOException ignored) {}
        }
    }

    private void handleClient(Socket socket) {
        try (socket) {
            // Echo / keepalive until client closes or server stops
            byte[] buf = new byte[1024];
            while (running.get() && !socket.isClosed()) {
                int read = socket.getInputStream().read(buf);
                if (read == -1) break;
                socket.getOutputStream().write(buf, 0, read);
                socket.getOutputStream().flush();
            }
        } catch (IOException ignored) {}
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        executor.shutdownNow();
    }
}
