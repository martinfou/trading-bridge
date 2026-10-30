package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.Types.WhatToShow;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.CmeGlobexMarketCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable IBKR historical bar fetcher via {@code reqHistoricalData} (Story 46.9).
 *
 * <p>Connects on its own dedicated {@code clientId} (distinct from the order-execution runner to
 * avoid IBKR error 326), converts IBKR exchange-timezone bar timestamps to UTC {@link Instant} at
 * the socket boundary, drops any still-forming bar whose {@code timestamp + timeframe > now}, and
 * returns closed {@link Bar} records sorted in monotonically increasing timestamp order.</p>
 */
public final class IbkrBarSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrBarSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter IBKR_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final DateTimeFormatter IBKR_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Seam for unit-testing bar filtering and timestamp conversion without a live socket. */
    @FunctionalInterface
    public interface RawBarFetcher {
        List<RawIbkrBar> fetch(String symbol, String timeframe, String durationStr);
    }

    public record RawIbkrBar(
        String time,
        double open,
        double high,
        double low,
        double close,
        long volume
    ) {}

    private final IbkrConnectionConfig config;
    private final Clock clock;
    private final RawBarFetcher rawBarFetcher;
    private final AtomicInteger reqIdCounter = new AtomicInteger(5000);

    private volatile EClientSocket client;
    private volatile EJavaSignal signal;
    private volatile EReader reader;
    private volatile CountDownLatch activeLatch;
    private final List<RawIbkrBar> activeBuffer = new CopyOnWriteArrayList<>();

    public IbkrBarSource(IbkrConnectionConfig config) {
        this(config, Clock.systemUTC(), null);
    }

    public IbkrBarSource(IbkrConnectionConfig config, Clock clock, RawBarFetcher rawBarFetcher) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.rawBarFetcher = rawBarFetcher != null ? rawBarFetcher : this::fetchFromSocket;
    }

    /**
     * Fetches the latest {@code count} closed bars for {@code symbol} and {@code timeframe}
     * (e.g. {@code ("MGC", "30 mins", 60)}). Excludes any still-forming bar where
     * {@code timestamp + timeframe > now}.
     */
    public List<Bar> latestBars(String symbol, String timeframe, int count) {
        Objects.requireNonNull(symbol, "symbol is required");
        Objects.requireNonNull(timeframe, "timeframe is required");
        if (count <= 0) {
            return List.of();
        }

        Duration barDuration = parseTimeframeDuration(timeframe);
        String ibkrBarSize = toIbkrBarSize(timeframe);
        String durationStr = computeIbkrDurationString(barDuration, count);

        List<RawIbkrBar> rawBars = rawBarFetcher.fetch(symbol, ibkrBarSize, durationStr);
        if (rawBars == null || rawBars.isEmpty()) {
            return List.of();
        }

        Instant now = clock.instant();
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        List<Bar> closedBars = new ArrayList<>(rawBars.size());

        for (RawIbkrBar raw : rawBars) {
            Instant barStart = parseIbkrTimestamp(raw.time());
            if (barStart == null) {
                continue;
            }
            // Drop the still-forming bar: a bar is complete only when barStart + barDuration <= now.
            if (barStart.plus(barDuration).isAfter(now)) {
                log.debug("Dropping still-forming bar for {} at {} (barEnd={} > now={})",
                    normalizedSymbol, barStart, barStart.plus(barDuration), now);
                continue;
            }
            closedBars.add(new Bar(
                normalizedSymbol,
                barStart,
                raw.open(),
                raw.high(),
                raw.low(),
                raw.close(),
                raw.volume()
            ));
        }

        closedBars.sort(Comparator.comparing(Bar::timestamp));

        if (closedBars.size() > count) {
            return List.copyOf(closedBars.subList(closedBars.size() - count, closedBars.size()));
        }
        return List.copyOf(closedBars);
    }

    private synchronized List<RawIbkrBar> fetchFromSocket(String symbol, String ibkrBarSize, String durationStr) {
        ensureConnected();
        EClientSocket socket = this.client;
        if (socket == null || !socket.isConnected()) {
            log.warn("IbkrBarSource not connected to {}:{}", config.host(), config.port());
            return List.of();
        }

        activeBuffer.clear();
        CountDownLatch latch = new CountDownLatch(1);
        this.activeLatch = latch;

        LocalDate todayEt = LocalDate.now(clock.withZone(CmeGlobexMarketCalendar.EASTERN_ZONE));
        Contract contract = TcpIbkrGatewayClient.buildContract(
            IbkrContractResolver.resolve(symbol, todayEt)
        );
        int reqId = reqIdCounter.incrementAndGet();

        socket.reqHistoricalData(
            reqId,
            contract,
            "",
            durationStr,
            ibkrBarSize,
            WhatToShow.TRADES.toString(),
            0, // useRTH = 0 (include ETH Globex session bars)
            2, // formatDate = 2 (UTC epoch seconds — immune to IB Gateway local timezone)
            false,
            null
        );

        try {
            boolean done = latch.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                log.warn("Timed out waiting for IBKR historicalDataEnd (symbol={}, barSize={}, reqId={})",
                    symbol, ibkrBarSize, reqId);
                try {
                    socket.cancelHistoricalData(reqId);
                } catch (RuntimeException ignored) {}
                return List.of();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        return List.copyOf(activeBuffer);
    }

    private synchronized void ensureConnected() {
        EClientSocket existing = this.client;
        if (existing != null && existing.isConnected()) {
            return;
        }

        EJavaSignal sig = new EJavaSignal();
        this.signal = sig;
        DefaultEWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void historicalData(int reqId, com.ib.client.Bar bar) {
                long vol = bar.volume() != null && bar.volume().isValid() ? bar.volume().longValue() : 0L;
                activeBuffer.add(new RawIbkrBar(
                    bar.time(),
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    vol
                ));
            }

            @Override
            public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
                CountDownLatch latch = activeLatch;
                if (latch != null) {
                    latch.countDown();
                }
            }

            @Override
            public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (id != -1) {
                    log.warn("IbkrBarSource error id={} code={}: {}", id, errorCode, errorMsg);
                    if (errorCode >= 162 && errorCode <= 165 || errorCode == 200 || errorCode == 321) {
                        CountDownLatch latch = activeLatch;
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                }
            }
        };

        EClientSocket newClient = new EClientSocket(wrapper, sig);
        this.client = newClient;
        newClient.eConnect(config.host(), config.port(), config.clientId());
        if (!newClient.isConnected()) {
            throw new IllegalStateException(
                "IbkrBarSource failed to connect to " + config.host() + ":" + config.port()
                + " (clientId=" + config.clientId() + ")");
        }

        EReader r = new EReader(newClient, sig);
        this.reader = r;
        r.start();
        Thread processor = new Thread(() -> {
            while (newClient.isConnected()) {
                sig.waitForSignal();
                try {
                    r.processMsgs();
                } catch (IOException e) {
                    break;
                }
            }
        }, "ibkr-barsource-reader");
        processor.setDaemon(true);
        processor.start();
    }

    /**
     * Converts an IBKR bar timestamp string (which may carry an explicit timezone suffix such as
     * {@code US/Eastern} or {@code America/New_York}, or default to {@link CmeGlobexMarketCalendar#EASTERN_ZONE})
     * into a UTC {@link Instant}.
     */
    public static Instant parseIbkrTimestamp(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        String trimmed = rawTime.trim().replaceAll("\\s+", " ");
        // Epoch seconds format (formatDate=2)
        if (trimmed.matches("^\\d{10}$")) {
            return Instant.ofEpochSecond(Long.parseLong(trimmed));
        }
        // Date only (yyyyMMdd)
        if (trimmed.matches("^\\d{8}$")) {
            LocalDate date = LocalDate.parse(trimmed, IBKR_DATE_FORMAT);
            return date.atStartOfDay(CmeGlobexMarketCalendar.EASTERN_ZONE).toInstant();
        }
        ZoneId zone = CmeGlobexMarketCalendar.EASTERN_ZONE;
        String dateTimePart = trimmed;
        String[] tokens = trimmed.split(" ");
        if (tokens.length >= 3) {
            dateTimePart = tokens[0] + " " + tokens[1];
            String tzToken = tokens[2];
            if ("UTC".equalsIgnoreCase(tzToken) || "GMT".equalsIgnoreCase(tzToken)) {
                zone = ZoneId.of("UTC");
            } else if ("US/Eastern".equalsIgnoreCase(tzToken) || "America/New_York".equalsIgnoreCase(tzToken)) {
                zone = CmeGlobexMarketCalendar.EASTERN_ZONE;
            } else if ("US/Central".equalsIgnoreCase(tzToken) || "America/Chicago".equalsIgnoreCase(tzToken)) {
                zone = ZoneId.of("America/Chicago");
            } else {
                try {
                    zone = ZoneId.of(tzToken);
                } catch (Exception ignored) {
                    zone = CmeGlobexMarketCalendar.EASTERN_ZONE;
                }
            }
        }
        LocalDateTime ldt = LocalDateTime.parse(dateTimePart, IBKR_TIME_FORMAT);
        return ldt.atZone(zone).toInstant();
    }

    public static Duration parseTimeframeDuration(String timeframe) {
        String tf = timeframe.trim().toLowerCase(Locale.ROOT);
        return switch (tf) {
            case "1 min", "1 mins", "m1" -> Duration.ofMinutes(1);
            case "5 mins", "5 min", "m5" -> Duration.ofMinutes(5);
            case "15 mins", "15 min", "m15" -> Duration.ofMinutes(15);
            case "30 mins", "30 min", "m30" -> Duration.ofMinutes(30);
            case "1 hour", "1 hr", "60 mins", "h1" -> Duration.ofHours(1);
            case "4 hours", "h4" -> Duration.ofHours(4);
            case "1 day", "d1" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Unsupported IBKR timeframe: " + timeframe);
        };
    }

    public static String toIbkrBarSize(String timeframe) {
        String tf = timeframe.trim().toLowerCase(Locale.ROOT);
        return switch (tf) {
            case "1 min", "1 mins", "m1" -> "1 min";
            case "5 mins", "5 min", "m5" -> "5 mins";
            case "15 mins", "15 min", "m15" -> "15 mins";
            case "30 mins", "30 min", "m30" -> "30 mins";
            case "1 hour", "1 hr", "60 mins", "h1" -> "1 hour";
            case "4 hours", "h4" -> "4 hours";
            case "1 day", "d1" -> "1 day";
            default -> throw new IllegalArgumentException("Unsupported IBKR timeframe: " + timeframe);
        };
    }

    static String computeIbkrDurationString(Duration barDuration, int count) {
        long totalMinutes = barDuration.toMinutes() * Math.max(count, 10);
        // Add buffer for weekend/maintenance closures so we always get >= count bars
        long days = Math.max(5L, (totalMinutes / (60L * 16L)) + 4L);
        if (days <= 30) {
            return days + " D";
        }
        long months = Math.max(1L, (days / 20L) + 1L);
        return months + " M";
    }

    @Override
    public synchronized void close() {
        EClientSocket socket = this.client;
        this.client = null;
        this.reader = null;
        this.signal = null;
        if (socket != null) {
            socket.eDisconnect();
        }
    }
}
