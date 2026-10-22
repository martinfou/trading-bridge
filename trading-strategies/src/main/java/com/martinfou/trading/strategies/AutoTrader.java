package com.martinfou.trading.strategies;

import com.martinfou.trading.core.TimeConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                Instant now = TimeConventions.now();
                for (var s : strategies) {
                    if (executed.getOrDefault(s.name(), false)) continue;

                    long minutesUntilNews = Duration.between(now, s.getNewsTime()).toMinutes();

                    if (minutesUntilNews > 0 && minutesUntilNews <= 30) {
                        Optional<TradeSignal> signalOpt = evaluateStrategy(s);
                        if (signalOpt.isPresent() && signalOpt.get().isConfident()) {
                            if (executeTrade(s, signalOpt.get())) {
                                executed.put(s.name(), true);
                            }
                        }
                    }

                    if (minutesUntilNews < -60) {
                        executed.put(s.name(), true);
                    }
                }
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                running = false;
            } catch (Exception e) {
                log.error("AutoTrader error: {}", e.getMessage());
            }
        }
    }

    private Optional<TradeSignal> evaluateStrategy(NewsTradingStrategy strategy) {
        return Optional.empty();
    }

    private boolean executeTrade(NewsTradingStrategy strategy, TradeSignal signal) {
        try {
            String units = signal.side() == com.martinfou.trading.core.Order.Side.BUY
                ? String.valueOf((int) (signal.quantity() * 100000))
                : String.valueOf(-(int) (signal.quantity() * 100000));

            var result = executor.placeMarketOrder(signal.pair(), units, "AUTO");

            // Never log or mark an order that did not open a trade as executed: a cancelled FOK
            // order would otherwise read as a fill at price null, and the strategy would be
            // marked done without ever having traded.
            if (!result.openedTrade()) {
                log.warn("⚠ TRADE NOT COUNTED: {} {} — status={}, cancelReason={}. The strategy is not marked as executed.",
                    signal.pair(), signal.side(), result.status(),
                    result.cancelReason() != null ? result.cancelReason() : "none");
                return false;
            }

            log.info("✅ TRADE EXECUTED: {} {} {} lots @ {}",
                signal.pair(), signal.side(), signal.quantity(), result.fillPrice());

            String slStr = LiveStrategyRunner.formatPrice(signal.stopLoss(), signal.pair());
            String tpStr = LiveStrategyRunner.formatPrice(signal.takeProfit(), signal.pair());

            executor.addStopLoss(result.tradeId(), slStr, "AUTO");
            executor.addTakeProfit(result.tradeId(), tpStr, "AUTO");
            log.info("   SL: {} TP: {}", slStr, tpStr);
            return true;
        } catch (Exception e) {
            log.error("❌ Trade execution FAILED: {}", e.getMessage());
            return false;
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
