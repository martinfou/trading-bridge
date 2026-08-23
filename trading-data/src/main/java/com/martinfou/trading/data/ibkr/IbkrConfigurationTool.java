package com.martinfou.trading.data.ibkr;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EWrapper;
import com.ib.client.EWrapperMsgGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Scanner;

public class IbkrConfigurationTool {

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".trading-bridge");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("ibkr.properties");

    public static void main(String[] args) throws IOException {
        System.out.println("=================================================");
        System.out.println("    IBKR Gateway / TWS Configuration Tool");
        System.out.println("=================================================");

        Properties props = loadConfig();

        Scanner scanner = new Scanner(System.in);
        String defaultHost = props.getProperty("host", "127.0.0.1");
        System.out.printf("Host [%s]: ", defaultHost);
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = defaultHost;

        String defaultPort = props.getProperty("port", "7497");
        System.out.printf("Port [%s]: ", defaultPort);
        String portStr = scanner.nextLine().trim();
        if (portStr.isEmpty()) portStr = defaultPort;

        String defaultClientId = props.getProperty("clientId", "1");
        System.out.printf("Client ID [%s]: ", defaultClientId);
        String clientIdStr = scanner.nextLine().trim();
        if (clientIdStr.isEmpty()) clientIdStr = defaultClientId;

        props.setProperty("host", host);
        props.setProperty("port", portStr);
        props.setProperty("clientId", clientIdStr);

        saveConfig(props);

        System.out.println("\nConfiguration saved to " + CONFIG_FILE);
        System.out.println("Testing connection...");

        testConnection(host, Integer.parseInt(portStr), Integer.parseInt(clientIdStr));
    }

    private static void testConnection(String host, int port, int clientId) {
        EWrapper wrapper = new com.ib.client.DefaultEWrapper() {
            @Override
            public void error(Exception e) {
                System.err.println("API Error: " + e.getMessage());
            }

            @Override
            public void error(String str) {
                System.err.println("API Error: " + str);
            }

            @Override
            public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 502) {
                    System.err.println("Couldn't connect to TWS. Confirm that \"Enable ActiveX and Socket Clients\" is enabled and connection port is correct.");
                } else if (id == -1) {
                    // Informational messages
                    System.out.println("Info: " + errorMsg);
                } else {
                    System.err.println("Error [" + errorCode + "]: " + errorMsg);
                }
            }

            @Override
            public void nextValidId(int orderId) {
                System.out.println("✅ Successfully connected to IBKR. Next valid order ID: " + orderId);
            }
        };

        EJavaSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(wrapper, signal);

        client.eConnect(host, port, clientId);

        if (client.isConnected()) {
            final EReader reader = new EReader(client, signal);
            reader.start();
            new Thread(() -> {
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
            
            try {
                Thread.sleep(2000); // Wait for connection feedback
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            client.eDisconnect();
            System.out.println("Disconnected.");
            System.exit(0);
        } else {
            System.err.println("❌ Connection failed.");
            System.exit(1);
        }
    }

    public static Properties loadConfig() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (java.io.InputStream in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Could not load config file: " + e.getMessage());
            }
        }
        return props;
    }

    public static void saveConfig(Properties props) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        try (java.io.OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "IBKR Gateway Connection Settings");
        }
    }
}
