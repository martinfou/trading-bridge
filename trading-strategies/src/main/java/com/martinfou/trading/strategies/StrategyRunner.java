package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.OandaPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class StrategyRunner {
    private static final Logger log = LoggerFactory.getLogger(StrategyRunner.class);
    
    private final OandaPriceClient oanda;
    private final List<NewsTradingStrategy> strategies;
    private boolean running = false;

    public StrategyRunner(String apiKey, String accountId) {
        this.oanda = new OandaPriceClient(apiKey, accountId, true);
        this.strategies = WeekStrategies.getStrategies();
    }

    public void runAll() throws Exception {
        running = true;
        log.info("Starting automatic strategy runner...");
        
        // Get account info
        var acct = oanda.getAccountSummary();
        log.info("Account: {} | Balance: ${}", acct.id(), acct.balance());
        
        // Evaluate each strategy
        for (var strategy : strategies) {
            if (!running) break;
            
            try {
                String oandaSym = strategyNameToSymbol(strategy.name());
                var bars = oanda.getCandles(oandaSym, "H1", 120);
                
                // Determine if news expected to be positive based on strategy direction
                boolean newsPositive = strategy.name().contains("Long");
                
                var signal = strategy.evaluate(bars, newsPositive);
                
                if (signal != null) {
                    String status = signal.isConfident() ? "🔴 EXECUTABLE" : "⚪ Watching";
                    log.info("{} | {} | Score: {}% | {} | Price: {:.5f}", 
                        status, signal.pair(), signal.confidence(), 
                        signal.reason().substring(0, Math.min(50, signal.reason().length())),
                        signal.entryPrice());
                }
                
                Thread.sleep(1000); // Rate limiting
            } catch (Exception e) {
                log.error("Error evaluating {}: {}", strategy.name(), e.getMessage());
            }
        }
        
        log.info("Strategy evaluation complete.");
        running = false;
    }

    public void stop() { running = false; }

    private String strategyNameToSymbol(String name) {
        if (name.contains("AUD")) return "AUD_USD";
        if (name.contains("USDCAD") || name.contains("CAD")) return "USD_CAD";
        if (name.contains("GBPUSD")) return "GBP_USD";
        if (name.contains("GBPJPY")) return "GBP_JPY";
        if (name.contains("GBP")) return "GBP_USD";
        return "EUR_USD";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: StrategyRunner <apiKey> <accountId>");
            System.out.println("\nOr: StrategyRunner --schedule (show this week's plan)");
            if (args.length > 0 && args[0].equals("--schedule")) {
                WeekStrategies.printSchedule();
            }
            return;
        }

        if (args[0].equals("--schedule")) {
            WeekStrategies.printSchedule();
            return;
        }

        var runner = new StrategyRunner(args[0], args[1]);
        runner.runAll();
    }
}
