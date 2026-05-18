package com.martinfou.trading.data;

import com.martinfou.trading.core.TimeConventions;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * High-impact economic events. Each {@link #event} stores UTC {@link Instant};
 * publication wall times are converted from their source release zones.
 *
 * <p>Publication zones (wall time → UTC via {@link TimeConventions#eventAt}):
 * <table border="1">
 *   <tr><th>Event (May 2026)</th><th>Wall time</th><th>ZoneId</th></tr>
 *   <tr><td>China Industrial Production / Retail Sales</td><td>06:00</td><td>Asia/Shanghai</td></tr>
 *   <tr><td>Japan GDP Q1</td><td>03:50</td><td>Asia/Tokyo</td></tr>
 *   <tr><td>RBA Meeting Minutes</td><td>05:30</td><td>Australia/Sydney</td></tr>
 *   <tr><td>UK Average Weekly Earnings</td><td>10:00</td><td>Europe/London</td></tr>
 *   <tr><td>Canada CPI</td><td>12:30</td><td>America/Toronto</td></tr>
 *   <tr><td>UK CPI</td><td>10:00</td><td>Europe/London</td></tr>
 *   <tr><td>FOMC Meeting Minutes</td><td>18:00</td><td>America/New_York</td></tr>
 *   <tr><td>Australia Employment</td><td>05:30</td><td>Australia/Sydney</td></tr>
 *   <tr><td>Germany PMI Flash</td><td>13:00</td><td>Europe/Berlin (verify source)</td></tr>
 *   <tr><td>UK PMI Flash</td><td>13:30</td><td>Europe/London</td></tr>
 *   <tr><td>US PMI Flash</td><td>13:45</td><td>America/New_York</td></tr>
 *   <tr><td>UK Retail Sales</td><td>10:00</td><td>Europe/London</td></tr>
 * </table>
 */
public class EconomicCalendar {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("EE dd HH:mm").withZone(TimeConventions.DISPLAY_ZONE);

    public record Event(
        Instant time,
        String currency,
        String event,
        String impact,
        String previous,
        String forecast,
        String actual
    ) {}

    private static Event event(int y, int m, int d, int h, int min, ZoneId zone,
                               String currency, String name, String impact,
                               String previous, String forecast, String actual) {
        return new Event(
            TimeConventions.eventAt(y, m, d, h, min, zone),
            currency, name, impact, previous, forecast, actual
        );
    }

    public static final List<Event> THIS_WEEK = List.of(
        event(2026, 5, 18, 6, 0, SHANGHAI, "CNY", "China Industrial Production", "HIGH", "+5.7%", "+5.2%", ""),
        event(2026, 5, 18, 6, 0, SHANGHAI, "CNY", "China Retail Sales", "HIGH", "+1.7%", "+2.5%", ""),
        event(2026, 5, 19, 3, 50, TOKYO, "JPY", "Japan GDP Q1 (Prelim)", "HIGH", "+0.3%", "+0.5%", ""),
        event(2026, 5, 19, 5, 30, SYDNEY, "AUD", "RBA Meeting Minutes", "HIGH", "4.35%", "-", ""),
        event(2026, 5, 19, 10, 0, LONDON, "GBP", "UK Average Weekly Earnings", "HIGH", "+3.8%", "+3.5%", ""),
        event(2026, 5, 19, 12, 30, TORONTO, "CAD", "Canada CPI (Inflation)", "HIGH", "+2.3%", "+2.1%", ""),
        event(2026, 5, 20, 10, 0, LONDON, "GBP", "UK CPI", "HIGH", "+3.9%", "+3.4%", ""),
        event(2026, 5, 20, 18, 0, NEW_YORK, "USD", "FOMC Meeting Minutes", "HIGH", "-", "-", ""),
        event(2026, 5, 21, 5, 30, SYDNEY, "AUD", "Australia Employment", "HIGH", "+32K", "+20K", ""),
        event(2026, 5, 21, 13, 0, BERLIN, "EUR", "Germany PMI Flash", "MEDIUM", "48.5", "49.0", ""),
        event(2026, 5, 21, 13, 30, LONDON, "GBP", "UK PMI Flash", "MEDIUM", "50.2", "50.5", ""),
        event(2026, 5, 21, 13, 45, NEW_YORK, "USD", "US PMI Flash", "MEDIUM", "50.0", "50.3", ""),
        event(2026, 5, 22, 10, 0, LONDON, "GBP", "UK Retail Sales", "HIGH", "-0.5%", "+0.3%", "")
    );

    public static List<Event> getHighImpactEvents() {
        return THIS_WEEK.stream().filter(e -> e.impact().equals("HIGH")).toList();
    }

    public static List<Event> getEventsForCurrency(String currency) {
        return THIS_WEEK.stream().filter(e -> e.currency().equals(currency)).toList();
    }

    public static void printWeek() {
        System.out.println("\n=== ECONOMIC CALENDAR: May 18-24, 2026 (stored UTC, shown "
            + TimeConventions.DISPLAY_ZONE + ") ===");
        for (Event e : THIS_WEEK) {
            String icon = switch (e.impact()) {
                case "HIGH" -> "🔥";
                case "MEDIUM" -> "📊";
                default -> "📋";
            };
            System.out.printf("%s %s %s %s | Prev: %s | Fest: %s%n",
                icon, DISPLAY_FMT.format(e.time()), e.currency(), e.event(), e.previous(), e.forecast());
        }
    }
}
