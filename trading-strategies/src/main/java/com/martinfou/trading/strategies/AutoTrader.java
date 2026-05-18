package com.martinfou.trading.strategies;

import com.martinfou.trading.data.OandaPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.*;

public class AutoTrader {
    private static final Logger log = LoggerFactory.getLogger(AutoTrader.class);
    private final OandaExecutor executor;
    private final List<NewsTradingStrategy> strategies;
    private final Map<String, Boolean> executed = new HashMap<>();
    private boolean running = true;

    public AutoTrader(String apiKey, String accountId) {
        this.executor = new OandaExecutor(apiKey, accountId, true);
        this.strategies = WeekStrategies.getStrategies();
    }

    public void run() {
        log.info("🤖 AutoTrader starting... Monitoring {} strategies", strategies.size());
        
        while (running) {
            try {
                var now = LocalDateTime.now();
                for (var s : strategies) {
                    if (executed.getOrDefault(s.name(), false)) continue;
                    
                    var minutesUntilNews = java.time.Duration.between(now, s.getNewsTime()).toMinutes();
                    
                    if (minutesUntilNews > 0 && minutesUntilNews <= 30) {
                        // Check price and execute
                        var signal = evaluateStrategy(s);
                        if (signal != null && signal.isConfident()) {
                            executeTrade(s, signal);
                            executed.put(s.name(), true);
                        }
                    }
                    
                    if (minutesUntilNews < -60) { // More than 1 hour past news
                        executed.put(s.name(), true); // News already passed
                    }
                }
                Thread.sleep(60000); // Check every minute
            } catch (InterruptedException e) {
                running = false;
            } catch (Exception e) {
                log.error("AutoTrader error: {}", e.getMessage());
            }
        }
    }

    private TradeSignal evaluateStrategy(NewsTradingStrategy strategy) {
        // Simplified: in real version would fetch bars and analyze
        return null; // Placeholder for the actual evaluation
    }

    private void executeTrade(NewsTradingStrategy strategy, TradeSignal signal) {
        try {
            // Convert signal to OANDA units (positive = buy, negative = sell)
            String units = signal.side() == com.martinfou.trading.core.Order.Side.BUY 
                ? String.valueOf((int)(signal.quantity() * 100000))
                : String.valueOf(-(int)(signal.quantity() * 100000));
            
            var result = executor.placeMarketOrder(signal.pair(), units);
            log.info("✅ TRADE EXECUTED: {} {} {} lots @ {}", 
                signal.pair(), signal.side(), signal.quantity(), result.fillPrice());
            
            // Add SL and TP
            String slStr = String.format("%.5f", signal.stopLoss());
            String tpStr = String.format("%.5f", signal.takeProfit());
            
            if (result.tradeId() != null && !result.tradeId().equals("N/A")) {
                executor.addStopLoss(result.tradeId(), slStr);
                executor.addTakeProfit(result.tradeId(), tpStr);
                log.info("   SL: {} TP: {}", slStr, tpStr);
            }
        } catch (Exception e) {
            log.error("❌ Trade execution FAILED: {}", e.getMessage());
        }
    }

    public void stop() { running = false; }
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: AutoTrader <apiKey> <accountId>");
            return;
        }
        var trader = new AutoTrader(args[0], args[1]);
        trader.run();
    }
}
