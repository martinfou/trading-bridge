package com.martinfou.trading.examples;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FetchMgcBars {

    private static final Logger log = LoggerFactory.getLogger(FetchMgcBars.class);

    public static void main(String[] args) throws Exception {
        EJavaSignal signal = new EJavaSignal();
        CountDownLatch latch = new CountDownLatch(1);
        List<Bar> bars = new ArrayList<>();

        DefaultEWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void historicalData(int reqId, Bar bar) {
                bars.add(bar);
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                log.info("Historical data download complete. Total bars received: {}", bars.size());
                latch.countDown();
            }

            @Override
            public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (id != -1) {
                    log.warn("IB error id={} code={}: {}", id, errorCode, errorMsg);
                }
            }
        };

        EClientSocket client = new EClientSocket(wrapper, signal);
        client.eConnect("127.0.0.1", 4002, 95);

        EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    break;
                }
            }
        }).start();

        Thread.sleep(1000);

        Contract contract = new Contract();
        contract.symbol("MGC");
        contract.secType("FUT");
        contract.exchange("COMEX");
        contract.currency("USD");
        contract.lastTradeDateOrContractMonth("202612");

        log.info("Requesting 1 Y of 30 mins bars for MGC 202612 from IB Gateway...");
        client.reqHistoricalData(1001, contract, "", "1 Y", "30 mins", "TRADES", 1, 1, false, null);

        boolean finished = latch.await(20, TimeUnit.SECONDS);
        log.info("Download finished: {}, bars={}", finished, bars.size());

        if (!bars.isEmpty()) {
            Path outDir = Paths.get("data/historical/csv");
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve("MGC_M30_2026.csv");
            try (PrintWriter pw = new PrintWriter(new FileWriter(outFile.toFile()))) {
                pw.println("Date,Open,High,Low,Close,Volume");
                for (Bar b : bars) {
                    pw.printf("%s,%.2f,%.2f,%.2f,%.2f,%d%n",
                        b.time(), b.open(), b.high(), b.low(), b.close(), b.volume().longValue());
                }
            }
            log.info("Saved {} bars to {}", bars.size(), outFile);
        }

        client.eDisconnect();
        System.exit(0);
    }
}
