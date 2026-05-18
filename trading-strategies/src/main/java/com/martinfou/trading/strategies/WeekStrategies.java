package com.martinfou.trading.strategies;

import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.data.EconomicCalendar;

import java.time.Instant;
import java.util.List;

public class WeekStrategies {

    private static Instant newsTime(String eventSubstring) {
        return EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> e.event().contains(eventSubstring))
            .map(EconomicCalendar.Event::time)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No calendar event: " + eventSubstring));
    }

    public static List<NewsTradingStrategy> getStrategies() {
        return List.of(
            new NewsTradingStrategy(
                "🥇 RBA Hawkish Long",
                "AUD/USD", "AUD_USD",
                NewsTradingStrategy.TradeSide.LONG,
                "RBA Meeting Minutes (hawkish expected)",
                newsTime("RBA Meeting Minutes"),
                15, 2.0, 0.01
            ),
            new NewsTradingStrategy(
                "🥇 IPC Canada Short",
                "USD/CAD", "USD_CAD",
                NewsTradingStrategy.TradeSide.SHORT,
                "Canada CPI (oil up = CAD strong)",
                newsTime("Canada CPI"),
                12, 2.0, 0.01
            ),
            new NewsTradingStrategy(
                "🥈 IPC UK Short",
                "GBP/USD", "GBP_USD",
                NewsTradingStrategy.TradeSide.SHORT,
                "UK CPI (expected lower = GBP weak)",
                newsTime("UK CPI"),
                18, 2.0, 0.01
            ),
            new NewsTradingStrategy(
                "🥉 GBPJPY Short",
                "GBP/JPY", "GBP_JPY",
                NewsTradingStrategy.TradeSide.SHORT,
                "UK CPI (GBP weak vs JPY)",
                newsTime("UK CPI"),
                25, 1.5, 0.01
            ),
            new NewsTradingStrategy(
                "AUD Employment Long",
                "AUD/USD", "AUD_USD",
                NewsTradingStrategy.TradeSide.LONG,
                "Australia Employment",
                newsTime("Australia Employment"),
                15, 2.0, 0.01
            )
        );
    }

    public static void printSchedule() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║    📅 CALENDRIER STRATEGIES SEMAINE         ║");
        System.out.println("║    (heures affichées: " + TimeConventions.DISPLAY_ZONE + ")     ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        for (var s : getStrategies()) {
            s.run();
        }
        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
