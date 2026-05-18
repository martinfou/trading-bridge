package com.martinfou.trading.data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class EconomicCalendar {
    public record Event(
        LocalDateTime time,
        String currency,
        String event,
        String impact,   // HIGH, MEDIUM, LOW
        String previous,
        String forecast,
        String actual
    ) {}

    public static final List<Event> THIS_WEEK = List.of(
        // Monday May 18
        new Event(LocalDateTime.parse("2026-05-18T06:00"), "CNY", "China Industrial Production", "HIGH", "+5.7%", "+5.2%", ""),
        new Event(LocalDateTime.parse("2026-05-18T06:00"), "CNY", "China Retail Sales", "HIGH", "+1.7%", "+2.5%", ""),
        new Event(LocalDateTime.parse("2026-05-19T03:50"), "JPY", "Japan GDP Q1 (Prelim)", "HIGH", "+0.3%", "+0.5%", ""),
        
        // Tuesday May 19
        new Event(LocalDateTime.parse("2026-05-19T05:30"), "AUD", "RBA Meeting Minutes", "HIGH", "4.35%", "-", ""),
        new Event(LocalDateTime.parse("2026-05-19T10:00"), "GBP", "UK Average Weekly Earnings", "HIGH", "+3.8%", "+3.5%", ""),
        new Event(LocalDateTime.parse("2026-05-19T12:30"), "CAD", "Canada CPI (Inflation)", "HIGH", "+2.3%", "+2.1%", ""),
        
        // Wednesday May 20
        new Event(LocalDateTime.parse("2026-05-20T10:00"), "GBP", "UK CPI", "HIGH", "+3.9%", "+3.4%", ""),
        new Event(LocalDateTime.parse("2026-05-20T18:00"), "USD", "FOMC Meeting Minutes", "HIGH", "-", "-", ""),
        
        // Thursday May 21
        new Event(LocalDateTime.parse("2026-05-21T05:30"), "AUD", "Australia Employment", "HIGH", "+32K", "+20K", ""),
        new Event(LocalDateTime.parse("2026-05-21T13:00"), "EUR", "Germany PMI Flash", "MEDIUM", "48.5", "49.0", ""),
        new Event(LocalDateTime.parse("2026-05-21T13:30"), "GBP", "UK PMI Flash", "MEDIUM", "50.2", "50.5", ""),
        new Event(LocalDateTime.parse("2026-05-21T13:45"), "USD", "US PMI Flash", "MEDIUM", "50.0", "50.3", ""),
        
        // Friday May 22
        new Event(LocalDateTime.parse("2026-05-22T10:00"), "GBP", "UK Retail Sales", "HIGH", "-0.5%", "+0.3%", "")
    );

    public static List<Event> getHighImpactEvents() {
        return THIS_WEEK.stream().filter(e -> e.impact().equals("HIGH")).toList();
    }

    public static List<Event> getEventsForCurrency(String currency) {
        return THIS_WEEK.stream().filter(e -> e.currency().equals(currency)).toList();
    }

    public static void printWeek() {
        System.out.println("\n=== ECONOMIC CALENDAR: May 18-24, 2026 ===");
        for (Event e : THIS_WEEK) {
            String icon = switch(e.impact()) {
                case "HIGH" -> "🔥";
                case "MEDIUM" -> "📊";
                default -> "📋";
            };
            System.out.printf("%s %s %s %s | Prev: %s | Fest: %s%n",
                icon, e.time().format(DateTimeFormatter.ofPattern("EE dd HH:mm")),
                e.currency(), e.event(), e.previous(), e.forecast());
        }
    }
}
