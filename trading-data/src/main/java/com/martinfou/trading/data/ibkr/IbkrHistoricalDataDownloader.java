package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EWrapper;
import com.ib.client.Types.WhatToShow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class IbkrHistoricalDataDownloader {

    public static void main(String[] args) throws InterruptedException {
        String symbol = args.length > 0 ? args[0] : "MES";
        String exchange = args.length > 1 ? args[1] : "CME";
        String secType = args.length > 2 ? args[2] : "FUT";
        
        System.out.println("Downloading historical data for " + symbol);
        
        var props = IbkrConfigurationTool.loadConfig();
        String host = props.getProperty("host", "127.0.0.1");
        int port = Integer.parseInt(props.getProperty("port", "7497"));
        // Use a different clientId so we don't conflict with main trading or config test
        int clientId = Integer.parseInt(props.getProperty("clientId", "1")) + 10;
        
        CountDownLatch latch = new CountDownLatch(1);
        List<com.ib.client.Bar> bars = new ArrayList<>();
        
        EWrapper wrapper = new com.ib.client.DefaultEWrapper() {
            @Override
            public void error(Exception e) {
                System.err.println("Error: " + e.getMessage());
            }

            @Override
            public void error(String str) {
                System.err.println("Error: " + str);
            }

            @Override
            public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (id != -1) {
                    System.err.println("Error [" + errorCode + "]: " + errorMsg);
                    if (errorCode >= 162 && errorCode <= 165) {
                        // Historical data pacing/error
                        latch.countDown();
                    }
                }
            }
            
            @Override
            public void nextValidId(int orderId) {
                System.out.println("Connected to TWS.");
            }

            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                bars.add(bar);
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                System.out.println("Finished downloading. Total bars: " + bars.size());
                saveToCsv(symbol, bars);
                latch.countDown();
            }
        };

        EJavaSignal signal = new EJavaSignal();
        EClientSocket client = new EClientSocket(wrapper, signal);

        client.eConnect(host, port, clientId);
        if (!client.isConnected()) {
            System.err.println("❌ Could not connect to IBKR.");
            return;
        }

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

        // Wait a sec for connection to settle
        Thread.sleep(1000);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType(secType);
        contract.exchange(exchange);
        contract.currency("USD");
        if ("FUT".equals(secType)) {
            // Need a specific expiration or it will fail.
            // Using a hardcoded recent one for the demo. In production, this would be passed as an argument.
            contract.lastTradeDateOrContractMonth("20260918"); 
        }

        String queryTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
        String duration = "1 M"; // 1 Month
        String barSize = "1 min"; // 1 minute bars
        
        System.out.println("Requesting " + duration + " of " + barSize + " bars...");
        client.reqHistoricalData(1001, contract, queryTime, duration, barSize, WhatToShow.TRADES.toString(), 1, 1, false, null);

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        if (!completed) {
            System.err.println("Timed out waiting for historical data.");
        }
        
        client.eDisconnect();
        System.out.println("Done.");
        System.exit(0);
    }
    
    private static void saveToCsv(String symbol, List<com.ib.client.Bar> bars) {
        if (bars.isEmpty()) return;
        Path dir = Paths.get(System.getProperty("trading.data.dir", "data"), "ibkr");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(symbol + "_1min.csv");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(file)) {
                writer.write("time,open,high,low,close,volume\n");
                for (com.ib.client.Bar b : bars) {
                    writer.write(String.format("%s,%.4f,%.4f,%.4f,%.4f,%s\n",
                        b.time(), b.open(), b.high(), b.low(), b.close(), b.volume().toString()));
                }
            }
            System.out.println("✅ Saved CSV to " + file.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save CSV: " + e.getMessage());
        }
    }
}
