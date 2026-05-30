package com.martinfou.trading.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Live scraper for ForexFactory economic calendar.
 * Fetches and parses the HTML calendar page to extract high-impact events.
 *
 * <p>Usage:
 * <pre>{@code
 *   var scraper = new ForexFactoryScraper();
 *   List<ForexFactoryScraper.Event> events = scraper.fetchWeek(LocalDate.now());
 *   for (var e : events) System.out.println(e);
 * }</pre>
 *
 * <p>Currency mapping: USD, EUR, GBP, JPY, CAD, AUD, NZD, CHF, CNY
 */
public class ForexFactoryScraper {

    private static final Logger log = LoggerFactory.getLogger(ForexFactoryScraper.class);
    private static final String FOREX_FACTORY_URL = "https://www.forexfactory.com/calendar";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Zone mapping for known currencies
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId WELLINGTON = ZoneId.of("Pacific/Auckland");

    private final HttpClient client;
    private final DateTimeFormatter dateFmt;

    /**
     * Economic event as parsed from ForexFactory.
     */
    public record Event(
        Instant time,
        String currency,
        String event,
        String impact,       // HIGH, MEDIUM, LOW
        String previous,
        String forecast,
        String actual
    ) {
        @Override
        public String toString() {
            String icon = switch (impact) {
                case "HIGH" -> "🔥";
                case "MEDIUM" -> "📊";
                default -> "📋";
            };
            var zdt = time.atZone(ZoneId.of("America/New_York"));
            var fmt = DateTimeFormatter.ofPattern("EE dd HH:mm");
            return String.format("%s %s %-4s %-40s Prev: %-8s Fest: %-8s Act: %s",
                icon, zdt.format(fmt), currency, event, previous, forecast, actual);
        }
    }

    public ForexFactoryScraper() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.dateFmt = DateTimeFormatter.ofPattern("MMM/dd", Locale.US);
    }

    /**
     * Fetch events for the week containing the given date.
     */
    public List<Event> fetchWeek(LocalDate dateInWeek) throws Exception {
        // ForexFactory calendar uses week-based navigation via ?week= parameter
        // The simplest approach: navigate to the calendar page and parse
        String url = FOREX_FACTORY_URL;
        log.info("🌐 Fetching ForexFactory calendar...");

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(TIMEOUT)
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("ForexFactory returned status {}", response.statusCode());
            return fallbackEvents(dateInWeek);
        }

        String html = response.body();
        List<Event> events = parseHtml(html, dateInWeek);

        if (events.isEmpty()) {
            log.warn("No events parsed from ForexFactory, using fallback");
            return fallbackEvents(dateInWeek);
        }

        log.info("✅ Parsed {} events from ForexFactory", events.size());
        return events;
    }

    /**
     * Parse ForexFactory HTML to extract events.
     * Looks for calendar__row elements with impact flags.
     */
    List<Event> parseHtml(String html, LocalDate weekRef) {
        List<Event> events = new ArrayList<>();
        String[] lines = html.split("\n");

        // Find date headers and event rows
        String currentDate = "";
        int currentYear = weekRef.getYear();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();

            // Detect date headers: "Mon May 19" or similar
            var dateMatcher = Pattern.compile("(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d+)")
                .matcher(line);
            if (dateMatcher.find()) {
                String month = dateMatcher.group(2);
                String day = dateMatcher.group(3);
                currentDate = month + " " + day;
                continue;
            }

            // Skip if no date context
            if (currentDate.isEmpty()) continue;

            // Look for impact indicators (red/orange/yellow dots in ForexFactory)
            // The classic FF page has: calendar__row with calendar__impact-icon
            boolean hasHighImpact = line.contains("impact-red") || line.contains("icon--red-fire");
            boolean hasMedImpact = line.contains("impact-orn") || line.contains("icon--orange");
            boolean hasLowImpact = line.contains("impact-yel") || line.contains("icon--gray");

            if (!hasHighImpact && !hasMedImpact && !hasLowImpact) continue;

            // Extract event details from surrounding lines
            String currency = extractField(lines, i, "currency", "calendar__currency");
            String eventName = extractField(lines, i, "event", "calendar__event");
            String timeStr = extractField(lines, i, "time", "calendar__time");
            String previous = extractField(lines, i, "previous", "calendar__previous");
            String forecast = extractField(lines, i, "forecast", "calendar__forecast");
            String actual = extractField(lines, i, "actual", "calendar__actual");

            if (currency == null || eventName == null) continue;

            String impact = hasHighImpact ? "HIGH" : hasMedImpact ? "MEDIUM" : "LOW";
            if (!"HIGH".equals(impact)) continue;  // Only keep HIGH impact

            // Parse time
            Instant eventTime = parseEventTime(currentDate, currentYear, timeStr, currency);

            events.add(new Event(eventTime, currency, eventName, impact,
                previous != null ? previous : "",
                forecast != null ? forecast : "",
                actual != null ? actual : ""));
        }

        // If HTML parsing failed, try JSON endpoint
        if (events.isEmpty()) {
            events = tryJsonEndpoint(weekRef);
        }

        return events;
    }

    /**
     * ForexFactory has a JSON endpoint for the calendar data.
     */
    private List<Event> tryJsonEndpoint(LocalDate date) {
        try {
            String url = "https://www.forexfactory.com/calendar?month="
                + date.getMonthValue() + "&year=" + date.getYear();

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET()
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            var json = MAPPER.readTree(response.body());
            List<Event> events = new ArrayList<>();

            // ForexFactory JSON structure varies; try common patterns
            if (json.has("weekdays")) {
                for (var day : json.get("weekdays")) {
                    String dateStr = day.has("date") ? day.get("date").asText() : "";
                    if (day.has("events")) {
                        for (var evt : day.get("events")) {
                            String impact = evt.has("impact") ? evt.get("impact").asText().toUpperCase() : "LOW";
                            if (!"HIGH".equals(impact)) continue;

                            String currency = evt.has("country") ? evt.get("country").asText() : "";
                            String name = evt.has("title") ? evt.get("title").asText() : "";
                            String timeStr = evt.has("time") ? evt.get("time").asText() : "";
                            String prev = evt.has("previous") ? evt.get("previous").asText() : "";
                            String fest = evt.has("forecast") ? evt.get("forecast").asText() : "";
                            String actual = evt.has("actual") ? evt.get("actual").asText() : "";

                            Instant eventTime = parseEventTime(dateStr, date.getYear(), timeStr, currency);

                            events.add(new Event(eventTime, currency, name, impact, prev, fest, actual));
                        }
                    }
                }
            }

            return events;
        } catch (Exception e) {
            log.warn("JSON endpoint failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract a field from an HTML row by looking for CSS class markers.
     */
    private String extractField(String[] lines, int currentIdx, String fieldName, String cssClass) {
        // Search context around the current line (±5 lines)
        int start = Math.max(0, currentIdx - 3);
        int end = Math.min(lines.length, currentIdx + 5);

        for (int j = start; j < end; j++) {
            String l = lines[j];
            if (l.contains(cssClass)) {
                // Extract text content between > and <
                var m = Pattern.compile(">" + fieldName + "</span>\\s*<span[^>]*>([^<]+)")
                    .matcher(l);
                if (m.find()) {
                    return m.group(1).strip();
                }
                // Fallback: extract any text node in a span
                m = Pattern.compile("<span[^>]*class=\"[^\"]*" + Pattern.quote(cssClass) + "[^\"]*\"[^>]*>([^<]+)")
                    .matcher(l);
                if (m.find()) {
                    return m.group(1).strip();
                }
            }
        }
        return null;
    }

    /**
     * Parse event time string into an Instant.
     */
    private Instant parseEventTime(String dateStr, int year, String timeStr, String currency) {
        try {
            // Parse the date: "May 19" or "Mon May 19"
            String cleanDate = dateStr.replaceAll("(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+", "").strip();
            var dateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US);
            var localDate = LocalDate.parse(cleanDate, dateFormatter).withYear(year);

            // Determine time
            int hour = 8, minute = 0; // Default to 08:00
            if (timeStr != null && !timeStr.isBlank()) {
                timeStr = timeStr.strip();
                if (timeStr.contains(":")) {
                    var parts = timeStr.split(":");
                    hour = Integer.parseInt(parts[0].strip());
                    minute = Integer.parseInt(parts[1].strip().replaceAll("[^0-9]", ""));
                    boolean isPM = timeStr.toUpperCase().contains("PM");
                    if (isPM && hour < 12) hour += 12;
                    if (!isPM && hour == 12) hour = 0;
                } else if (timeStr.equalsIgnoreCase("All Day")) {
                    return localDate.atStartOfDay(ZoneId.of("UTC")).toInstant();
                }
            }

            // Determine timezone from currency
            ZoneId zone = zoneForCurrency(currency);

            return ZonedDateTime.of(localDate, java.time.LocalTime.of(hour, minute), zone)
                .toInstant();

        } catch (Exception e) {
            log.debug("Could not parse event time: date={} time={} cur={}: {}",
                dateStr, timeStr, currency, e.getMessage());
            // Fallback: noon UTC on that date
            var ld = parseDate(dateStr, year);
            return ld.atStartOfDay(ZoneId.of("UTC")).toInstant();
        }
    }

    private LocalDate parseDate(String dateStr, int year) {
        try {
            String clean = dateStr.replaceAll("(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+", "").strip();
            return LocalDate.parse(clean, DateTimeFormatter.ofPattern("MMM d", Locale.US)).withYear(year);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private ZoneId zoneForCurrency(String currency) {
        if (currency == null) return NEW_YORK;
        return switch (currency.toUpperCase()) {
            case "USD" -> NEW_YORK;
            case "EUR" -> BERLIN;
            case "GBP" -> LONDON;
            case "JPY" -> TOKYO;
            case "CAD" -> TORONTO;
            case "AUD" -> SYDNEY;
            case "NZD" -> WELLINGTON;
            case "CHF" -> BERLIN;
            case "CNY" -> SHANGHAI;
            default -> NEW_YORK;
        };
    }

    // ── Fallback: return a reasonable set of expected events when scrape fails ──

    private List<Event> fallbackEvents(LocalDate date) {
        log.info("Using fallback economic calendar for week of {}", date);
        // Generate last-resort fallback based on known weekly patterns
        return EconomicCalendar.THIS_WEEK.stream()
            .map(e -> new Event(e.time(), e.currency(), e.event(), e.impact(),
                e.previous(), e.forecast(), e.actual()))
            .toList();
    }

    // ── Convenience: filter helpers ──

    public static List<Event> highImpact(List<Event> events) {
        return events.stream().filter(e -> "HIGH".equals(e.impact())).toList();
    }

    public static List<Event> forCurrency(List<Event> events, String currency) {
        return events.stream().filter(e -> e.currency().equals(currency)).toList();
    }

    public static List<Event> upcoming(List<Event> events, int hoursAhead) {
        Instant deadline = Instant.now().plus(Duration.ofHours(hoursAhead));
        return events.stream().filter(e -> e.time().isBefore(deadline) && e.time().isAfter(Instant.now())).toList();
    }
}
