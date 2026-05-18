package com.martinfou.trading.strategies;

import java.time.LocalDateTime;
import java.util.*;

public class WeekStrategies {
    
    public static List<NewsTradingStrategy> getStrategies() {
        return List.of(
            // 1. AUDUSD - RBA Minutes - LONG
            new NewsTradingStrategy(
                "🥇 RBA Hawkish Long",
                "AUD/USD", "AUD_USD",
                NewsTradingStrategy.TradeSide.LONG,
                "RBA Meeting Minutes (hawkish expected)",
                LocalDateTime.of(2026, 5, 19, 5, 30),
                15, 2.0, 0.01
            ),
            // 2. USDCAD - Canada CPI - SHORT
            new NewsTradingStrategy(
                "🥇 IPC Canada Short",
                "USD/CAD", "USD_CAD",
                NewsTradingStrategy.TradeSide.SHORT,
                "Canada CPI (oil up = CAD strong)",
                LocalDateTime.of(2026, 5, 19, 12, 30),
                12, 2.0, 0.01
            ),
            // 3. GBPUSD - UK CPI - SHORT
            new NewsTradingStrategy(
                "🥈 IPC UK Short",
                "GBP/USD", "GBP_USD",
                NewsTradingStrategy.TradeSide.SHORT,
                "UK CPI (expected lower = GBP weak)",
                LocalDateTime.of(2026, 5, 20, 10, 0),
                18, 2.0, 0.01
            ),
            // 4. GBPJPY - UK CPI + Japan GDP - SHORT
            new NewsTradingStrategy(
                "🥉 GBPJPY Short",
                "GBP/JPY", "GBP_JPY",
                NewsTradingStrategy.TradeSide.SHORT,
                "UK CPI + Japan GDP combined",
                LocalDateTime.of(2026, 5, 20, 10, 0),
                25, 1.5, 0.01
            ),
            // 5. AUDUSD - Employment - LONG
            new NewsTradingStrategy(
                "AUD Employment Long",
                "AUD/USD", "AUD_USD",
                NewsTradingStrategy.TradeSide.LONG,
                "Australia Employment",
                LocalDateTime.of(2026, 5, 21, 5, 30),
                15, 2.0, 0.01
            )
        );
    }

    public static void printSchedule() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║    📅 CALENDRIER STRATEGIES SEMAINE         ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        for (var s : getStrategies()) {
            s.run();
        }
        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
